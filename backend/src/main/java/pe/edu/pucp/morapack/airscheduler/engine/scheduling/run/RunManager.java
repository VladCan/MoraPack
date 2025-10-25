package pe.edu.pucp.morapack.airscheduler.engine.scheduling.run;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Scanner;

// Imports para la lógica de planificación
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.ArchivoUtils;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.CargarPedidos;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.CargarPedidos.VentanaPedidos;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.EstadoAnteriorExtractor;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosMap;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.teg.TEGEventBuilder;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.teg.helpers.TEGParametros;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.ArriboExogeno;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.OcupacionAlmacen;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators.*;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.OcupacionPorAeropuerto;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.SolucionProgramacion;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.ssp.SSPGeneradorSeed;

@ApplicationScoped
public class RunManager {
    private final ExecutorService executor = Executors.newCachedThreadPool((r -> {
        Thread t = new Thread(r, "run-" + UUID.randomUUID());
        t.setDaemon(true);
        return t;
    }));

    private final Map<String, RunContext> contexts = new ConcurrentHashMap<>();
    private final Map<String, RunState> states = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> paused = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelled = new ConcurrentHashMap<>();
    
    // Catálogos compartidos (se cargan una vez)
    private volatile AeropuertosMap aeropuertosMap;
    private volatile VuelosMap vuelosMap;
    private volatile CargarPedidos pedidosCargados;
    private volatile Set<String> sedes;
    
    // Estado de planificación por run
    private final Map<String, SolucionProgramacion> solucionesAnteriores = new ConcurrentHashMap<>();
    private final Map<String, OcupacionPorAeropuerto> ocupacionesPorRun = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> ventanasEnviadas = new ConcurrentHashMap<>();

    public void addContext(String idRun, RunContext context) {
        contexts.put(idRun, context);
    }
    
    /**
     * Inicializa los catálogos compartidos si no están cargados
     */
    private synchronized void inicializarCatalogos() {
        if (aeropuertosMap == null) {
            System.out.println("[RunManager] Inicializando catálogos...");
            
            // Cargar aeropuertos
            aeropuertosMap = new AeropuertosMap();
            try (Scanner sc = ArchivoUtils.getScannerFromResource(
                    "c.1inf54.25.2.Aeropuerto.husos.v1.20250818__estudiantes.txt")) {
                if (sc != null) {
                    aeropuertosMap.leerDatos(sc);
                    System.out.println("[RunManager] Aeropuertos cargados: " + aeropuertosMap.size());
                } else {
                    System.err.println("[RunManager] No se encontró archivo de aeropuertos");
                }
            }
            
            // Cargar vuelos
            vuelosMap = new VuelosMap(aeropuertosMap);
            try (Scanner sc = ArchivoUtils.getScannerFromResource(
                    "c.1inf54.25.2.planes_vuelo.v4.20250818.txt")) {
                if (sc != null) {
                    vuelosMap.leerDatos(sc);
                    System.out.println("[RunManager] Vuelos cargados");
                } else {
                    System.err.println("[RunManager] No se encontró archivo de vuelos");
                }
            }
            
            // Cargar pedidos
            pedidosCargados = new CargarPedidos();
            try (Scanner sc = ArchivoUtils.getScannerFromResource("pedidosProfe.txt")) {
                if (sc != null) {
                    pedidosCargados.leerDatosProfe(sc);
                    pedidosCargados.normalizarUtc(aeropuertosMap);
                    pedidosCargados.ordenarPorUTC();
                    System.out.println("[RunManager] Pedidos cargados: " + pedidosCargados.getLista().size());
                } else {
                    System.err.println("[RunManager] No se encontró archivo de pedidos");
                }
            }
            
            // Definir sedes
            sedes = new HashSet<>(Arrays.asList("SPIM", "EBCI", "UBBB"));
            
            System.out.println("[RunManager] Catálogos inicializados correctamente");
        }
    }

    /*Devolvemos el ahora simulado del run*/
    /*public Instant currentSimNow(RunId runId){
        return currentSimNow(runId);
    }*/

    /*Overload, ya que vamos a usar el string y no el RunId*/
    public Instant currentSimNow(String runId){
        RunContext ctx = requireContext(runId);
        //Acá calculamos el tiempo real transcurrido desde que arrancó el run
        long deltaMs = Duration.between(ctx.wallAnchor(), Instant.now()).toMillis();
        //Acá calculamos la velocidad en segundos simulados por segundo real
        long simDeltaMs = (long) Math.floor(deltaMs * ctx.speed());

        //Retornamos el ahora simulado
        return ctx.simStartUtc().plusMillis(simDeltaMs);
    }

    /** Obtiene el contexto o lanza un error claro si no existe. */
    public RunContext requireContext(String runId) {
        RunContext ctx = contexts.get(runId);
        if (ctx == null) {
            throw new IllegalStateException("Run no encontrado: " + runId);
        }
        return ctx;
    }

    // Suscriptores por run (para el SSE)
    private final Map<String, java.util.concurrent.CopyOnWriteArraySet<RunListener>> listeners
            = new java.util.concurrent.ConcurrentHashMap<>();

    // ---- Listener (para SSE). Podemos dejar NOOP por ahora.
    public interface RunListener {
        void onWindow(WindowPacket packet);
        void onFinished(String runId, StopReason reason);
    }
    public static final RunListener NOOP_LISTENER = new RunListener() {
        @Override public void onWindow(WindowPacket packet) {}
        @Override public void onFinished(String runId, StopReason reason) {}
    };


    // ---- Snapshot simple para status()
    public static final class Status {
        public final String runId;
        public final RunState state;
        public final int windowIndex;
        public Status(String runId, RunState state, int windowIndex) {
            this.runId = runId; this.state = state; this.windowIndex = windowIndex;
        }
    }

    public void registerListener(RunId runId, RunListener l) {
        listeners.computeIfAbsent(runId.value(), k -> new java.util.concurrent.CopyOnWriteArraySet<>()).add(l);
    }

    public void removeListener(RunId runId, RunListener l) {
        var set = listeners.get(runId.value());
        if (set != null) set.remove(l);
    }

    // ---- Overload conveniente (sin listener)
    public void start(RunId runId, RunConfig config) {
        start(runId, config, NOOP_LISTENER);
    }


    public void start (RunId runId, RunConfig config, RunListener listener) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(listener, "listener");

        final String id = runId.value();
        listeners.computeIfAbsent(id, k -> new java.util.concurrent.CopyOnWriteArraySet<>());

        //Por si se pasa un listener como parámetro
        if (listener != null && listener != NOOP_LISTENER) {
            registerListener(runId, listener);
        }

        states.put(id, RunState.RUNNING);
        paused.put(id, new AtomicBoolean(false));
        cancelled.put(id, new AtomicBoolean(false));

        executor.submit(() -> {
            try{
                Instant wStart = config.fechaInicio();
                Instant wEnd = wStart.plus(config.horasVentana());
                int idx = 0;

                System.out.println("En esta iteración, wStart es: " + wStart + ", wEnd es: " + wEnd);
                System.out.println("Voy a entrar al bucle, mi id es:" + id);

                while (!cancelled.get(id).get() && (config.fechaFin() == null || !wStart.isAfter(config.fechaFin()))) {
                    /// Revisar esto:
                    // Pausa cooperativa entre ventanas
                    while (paused.get(id).get() && !cancelled.get(id).get()) {
                        sleepQuietly(Duration.ofMillis(80));
                    }
                    if (cancelled.get(id).get()) break;

                    // ===== LÓGICA DE PLANIFICACIÓN POR VENTANAS =====
                    
                    // Inicializar catálogos si es necesario
                    inicializarCatalogos();
                    
                    // Verificar si ya enviamos esta ventana (idempotencia)
                    String windowIdISO = wStart.toString();
                    Set<String> ventanasEnviadasRun = ventanasEnviadas.computeIfAbsent(id, k -> new HashSet<>());
                    if (ventanasEnviadasRun.contains(windowIdISO)) {
                        System.out.println("[RunManager] Ventana ya enviada, saltando: " + windowIdISO);
                        // Avanzar a la siguiente ventana antes de continuar
                        idx++;
                        wStart = wEnd;
                        wEnd = wEnd.plus(config.horasVentana());
                        continue;
                    }
                    
                    System.out.println("[RunManager] Procesando ventana " + idx + ": " + wStart + " - " + wEnd);
                    
                    try {
                        // 1. Obtener pedidos de la ventana actual
                        VentanaPedidos ventana = pedidosCargados.acumuladoHasta(wEnd);
                        List<Pedido> pedidosVentana = ventana.pedidos();
                        
                        if (pedidosVentana.isEmpty()) {
                            System.out.println("[RunManager] No hay pedidos en la ventana " + idx);
                            // Marcar ventana como enviada aunque esté vacía
                            ventanasEnviadasRun.add(windowIdISO);
                            broadcastWindow(new WindowPacket(id, idx, wStart, wEnd, List.of(), List.of()));
                            // Avanzar a la siguiente ventana antes de continuar
                            idx++;
                            wStart = wEnd;
                            wEnd = wEnd.plus(config.horasVentana());
                            continue;
                        }
                        
                        // 2. Preparar estado anterior si existe
                        SolucionProgramacion solucionAnterior = solucionesAnteriores.get(id);
                        Map<String, List<ArriboExogeno>> enVuelo = Map.of();
                        List<OcupacionAlmacen> reservas = List.of();
                        
                        if (solucionAnterior != null) {
                            enVuelo = EstadoAnteriorExtractor.construirArribosEnVuelo(solucionAnterior, wStart);
                            reservas = EstadoAnteriorExtractor.reservasDesdeSolucionAnterior(solucionAnterior, wStart, Duration.ofHours(2));
                        }
                        
                        // 3. Construir TEG para la ventana
                        Instant finTEG = wEnd.plus(config.horizon());
                        TEGParametros params = TEGParametros.builder()
                                .inicioUtc(wStart)
                                .finUtc(finTEG)
                                .sedes(sedes)
                                .arribosLibres(enVuelo)
                                .reservasWaitIniciales(reservas)
                                .build();
                        
                        VuelosTEG teg = new TEGEventBuilder(aeropuertosMap, vuelosMap).construir(params);
                        
                        // 4. Generar solución inicial (seed)
                        OcupacionPorAeropuerto ocupacionPorAeropuerto = ocupacionesPorRun.computeIfAbsent(id, k -> new OcupacionPorAeropuerto(aeropuertosMap));
                        SSPGeneradorSeed ssp = new SSPGeneradorSeed(sedes, Map.of(), ocupacionPorAeropuerto);
                        SolucionProgramacion seed = ssp.generarSeed(teg, pedidosVentana, wStart);
                        
                        // 5. Ejecutar ALNS
                        List<DestructionOperator> destructores = new ArrayList<>();
                        destructores.add(new RandomRemoval(20));
                        destructores.add(new WorstRemoval(20));
                        
                        List<RepairOperator> reparadores = new ArrayList<>();
                        reparadores.add(new RegretRepair(2, new ArrayList<>(sedes), teg));
                        reparadores.add(new SplitRepair(new ArrayList<>(sedes), teg, 50));
                        
                        ALNS alns = new ALNS(teg, pedidosVentana, destructores, reparadores, wStart, ocupacionPorAeropuerto);
                        SolucionProgramacion solucionOptima = alns.ejecutar(seed);
                        
                        // 6. Guardar solución para la siguiente ventana
                        solucionesAnteriores.put(id, solucionOptima);
                        
                        // 7. Extraer vuelos y pedidos de la ventana actual para broadcasting
                        List<Object> vuelosVentana = extraerVuelosDeVentana(solucionOptima, wStart, wEnd);
                        List<Object> pedidosVentanaDTO = convertirPedidosADTO(pedidosVentana);
                        
                        // 8. Marcar ventana como enviada y hacer broadcast
                        ventanasEnviadasRun.add(windowIdISO);
                        broadcastWindow(new WindowPacket(id, idx, wStart, wEnd, vuelosVentana, pedidosVentanaDTO));
                        
                        System.out.println("[RunManager] Ventana " + idx + " procesada exitosamente. Vuelos: " + vuelosVentana.size() + ", Pedidos: " + pedidosVentanaDTO.size());
                        
                    } catch (Exception e) {
                        System.err.println("[RunManager] Error procesando ventana " + idx + ": " + e.getMessage());
                        e.printStackTrace();
                        // Continuar con la siguiente ventana en caso de error
                    }

                    //Acá vamos a que el reloj simulado cruce el fin de ventana
                    while (true){
                        //En caso de existir pausa o cancelación (por ahora, esto no ocurrirá)
                        if (cancelled.get(id).get()) break;

                        while (paused.get(id).get() && !cancelled.get(id).get()) {
                            sleepQuietly(Duration.ofMillis(100)); // dormimos cortito mientras esté pausado
                        }
                        if (cancelled.get(id).get()) break;

                        Instant simNow = currentSimNow(id);

                        //Verificamos si ya cruzó el fin de ventana
                        if (!simNow.isBefore(wEnd)){
                            break;
                        }

                        //Lo que viene acá abajo es para evitar busy-wait, osea
                        //que el CPU no este ejecutando a cada rato lo de arriba

                        long remainingSimMs = Duration.between(simNow, wEnd).toMillis();
                        if (remainingSimMs <= 0) break;

                        //Acá calculamos lo que falta simular a "cuanto dormir"
                        RunContext ctx = requireContext(id);
                        double speed = ctx.speed();
                        long remainingRealMs = (long) Math.ceil(remainingSimMs / speed);

                        //Dormimos por tramos cortos para poder reaccionar a pausa o cancel
                        long napMs = Math.min(Math.max(remainingRealMs, 50L), 500L);
                        sleepQuietly(Duration.ofMillis(napMs));

                    }

                    /*System.out.println("Vamos a dormir 6 segundos, mi id es:" + id);
                    sleepQuietly(Duration.ofSeconds(6));
                    System.out.println("Ya desperté 6, mi id es:" + id);*/


                    // Siguiente ventana
                    idx++;
                    wStart = wEnd;
                    wEnd   = wEnd.plus(config.horasVentana());
                }

                System.out.println("Salí del bucle, mi id es:" + id);

                // Fin normal
                states.put(id, RunState.COMPLETED);
                broadcastFinished(id, StopReason.FIN_DE_RANGO);

            }
            catch (Throwable e){
                states.put(id, RunState.FAILED);
                broadcastFinished(id, StopReason.ERROR);
            }

        });
    }

    private void broadcastWindow(WindowPacket pkt) {
        var set = listeners.getOrDefault(pkt.runId, new java.util.concurrent.CopyOnWriteArraySet<>());
        set.forEach(l -> safe(() -> l.onWindow(pkt)));
    }

    private void broadcastFinished(String runId, StopReason reason) {
        var set = listeners.getOrDefault(runId, new java.util.concurrent.CopyOnWriteArraySet<>());
        set.forEach(l -> safe(() -> l.onFinished(runId, reason)));
    }

    // para no romper el stream si un listener falla
    private static void safe(Runnable r) { try { r.run(); } catch (Throwable ignored) {} }

    public void pause(RunId id){ paused.getOrDefault(id.value(), new AtomicBoolean()).set(true); }
    public void resume(RunId id){ paused.getOrDefault(id.value(), new AtomicBoolean()).set(false); }
    public void cancel(RunId id){ cancelled.getOrDefault(id.value(), new AtomicBoolean()).set(true); }
    public Status status(RunId id) {
        RunState st = states.getOrDefault(id.value(), RunState.PENDING);
        // Si quieres, guarda el índice real; por ahora 0 (suficiente para compilar).
        return new Status(id.value(), st, 0);
    }

    private static void sleepQuietly(Duration d) {
        try { Thread.sleep(d.toMillis()); } catch (InterruptedException ignored) {}
    }
    
    /**
     * Extrae los vuelos que caen dentro de la ventana temporal especificada
     */
    private List<Object> extraerVuelosDeVentana(SolucionProgramacion solucion, Instant wStart, Instant wEnd) {
        List<Object> vuelosVentana = new ArrayList<>();
        
        // Por ahora retornamos una lista vacía, pero aquí se implementaría
        // la lógica para extraer vuelos de la solución que caen en [wStart, wEnd)
        // Esto requeriría examinar la estructura de SolucionProgramacion y CargaPorVuelo
        
        return vuelosVentana;
    }
    
    /**
     * Convierte los pedidos a DTOs para el frontend
     */
    private List<Object> convertirPedidosADTO(List<Pedido> pedidos) {
        List<Object> pedidosDTO = new ArrayList<>();
        
        for (Pedido pedido : pedidos) {
            Map<String, Object> pedidoDTO = new HashMap<>();
            pedidoDTO.put("id", pedido.getIdPedido());
            pedidoDTO.put("idCliente", pedido.getIdCliente());
            pedidoDTO.put("destino", pedido.getDestino());
            pedidoDTO.put("origen", pedido.getOrigen());
            pedidoDTO.put("cantidad", pedido.getCantidad());
            pedidoDTO.put("fechaCreacion", pedido.getCreatedAtUtc() != null ? pedido.getCreatedAtUtc().toString() : null);
            pedidoDTO.put("fechaLocal", pedido.getFecha() != null ? pedido.getFecha().toString() : null);
            pedidoDTO.put("continenteDestino", pedido.getContinenteDestino());
            
            pedidosDTO.add(pedidoDTO);
        }
        
        return pedidosDTO;
    }

}
