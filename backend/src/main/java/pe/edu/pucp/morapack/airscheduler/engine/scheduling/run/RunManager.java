package pe.edu.pucp.morapack.airscheduler.engine.scheduling.run;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.ArchivoManager;
// Imports para la lógica de planificación
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.CargarPedidos;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.CargarPedidos.VentanaPedidos;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.LectorPedidoMultiArchivo;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.EstadoAnteriorExtractor;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosCancelados;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosMap;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.teg.TEGEventBuilder;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.teg.helpers.TEGParametros;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.ArriboExogeno;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.OcupacionAlmacen;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators.*;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.CargaPorVuelo;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.OcupacionPorAeropuerto;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.PlanPedido;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.RutaAsignada;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.SolucionProgramacion;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.TramoAsignado;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.VueloProgramadoId;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.ssp.SSPGeneradorSeed;

@ApplicationScoped
public class RunManager {

    @Inject
    ArchivoManager archivoManager;
    private static final String AEROPUERTOS_FILENAME = "aereopuertos.txt";
    private static final String VUELOS_FILENAME = "vuelos.txt";
    private static final String PEDIDOS_FILENAME = "pedidos.txt";
    private static boolean firstExecution = false;

    private static final String VUELOS_CANCELADOS_FILENAME = "vuelos_cancelados.txt";


    private final ExecutorService executor = Executors.newCachedThreadPool((r -> {
        Thread t = new Thread(r, "run-" + UUID.randomUUID());
        t.setDaemon(true);
        return t;
    }));

    private final Map<String, RunContext> contexts = new ConcurrentHashMap<>();
    private final Map<String, RunState> states = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> paused = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelled = new ConcurrentHashMap<>();

    //Permite mantener un ÚNICO run de Operación a la vez
    private final AtomicReference<String> operacionRunId = new AtomicReference<>(null);
    //La cola de pedidos de dicho ÚNICO run de Operación
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Pedido>> queues = new ConcurrentHashMap<>();

    public String currentOperacionRunId(){ return operacionRunId.get(); }
    public boolean hasActiveOperacionRunId(){ return operacionRunId.get() != null; }
    
    /**
     * Obtiene el último run activo (con estado RUNNING) de cualquier tipo.
     * Útil para obtener vuelos programados cuando no hay run de operación activo.
     * 
     * @return ID del último run activo, o null si no hay ninguno
     */
    public String getLastActiveRunId() {
        // Buscar el último run con estado RUNNING
        return states.entrySet().stream()
                .filter(entry -> entry.getValue() == RunState.RUNNING)
                .map(Map.Entry::getKey)
                .max((id1, id2) -> {
                    // Ordenar por fecha de inicio del contexto (más reciente primero)
                    RunContext ctx1 = contexts.get(id1);
                    RunContext ctx2 = contexts.get(id2);
                    if (ctx1 == null || ctx2 == null) return 0;
                    return ctx1.wallAnchor().compareTo(ctx2.wallAnchor());
                })
                .orElse(null);
    }

    public boolean requestCancel(String runId){
        AtomicBoolean flag = cancelled.get(runId);
        if (flag == null) {
            return false;      // runId desconocido (ya terminó o nunca existió)
        }
        flag.set(true);
        return true;
    }

    public void pushOrder(String runId, Pedido p){
        //queues.computeIfAbsent(runId, k -> new ConcurrentLinkedQueue<>()).add(p);

        ///Dejamos esto así solo para depuración (ver que la cola se actualiza en tiempo real). Cuando n
        ///ya no sea necesario, descomentar lo de arribita y borra esto de abajo:
        var queue = queues.computeIfAbsent(runId, k -> new ConcurrentLinkedQueue<>());
        queue.add(p);

        System.out.printf(
                "[RunManager] Pedido agregado a cola (runId=%s). Tamaño actual: %d%n",
                runId, queue.size()
        );
    }

    public void pushOrders(String runId, List<Pedido> pedidos){
        var queue = queues.computeIfAbsent(runId, k -> new ConcurrentLinkedQueue<>());
        for (Pedido p : pedidos) queue.add(p);
        System.out.printf("[RunManager] Se encolaron %d pedidos (runId=%s). Tamaño actual: %d%n",
                pedidos.size(), runId, queue.size());
    }

    //
    public List<Pedido> drainOrders(String runId) {
        var q = queues.getOrDefault(runId, new ConcurrentLinkedQueue<>());
        var list = new ArrayList<Pedido>();
        for (Pedido x; (x = q.poll()) != null; ) list.add(x);
        return list;
    }

    public void setOperacionRunId(String operacionRunId) {
        this.operacionRunId.set(operacionRunId);
    }

    //Con esto estamos creando el run si no existe. Si ya existe, lo devolvemos:
    public String ensureOperacionStarted(){
        String existing = operacionRunId.get();
        if (existing != null) return existing;

        /*
        //Evita que 2 primeros pedidos creen 2 runs. (Para efectos del curso nunca pasará, pero porseaca)
        synchronized (this){
            existing = operacionRunId.get();
            if (existing != null) return existing;

            RunId runId = RunId.create();

            Set<String> sedes = new HashSet<>(Arrays.asList("SPIM", "EBCI", "UBBB"));
            //Pd: Tenemos que usar minutos, en la clase RunConfig este inicializador es de 1 hora.
            RunConfig config = RunConfig.operacion(sedes);

            addContext(runId.value(), new RunContext(runId, config));
            operacionRunId.set(runId.value());

            System.out.println("Revisa: http://localhost:8080/runs/" + runId.value() + "/stream ");

            start(runId, config);

            return runId.value();
        }
        */

        return null;
    }


    /// //////////////////////////////////////////////
    /// Todo0 lo que había sobre las planificaciones:
    /// //////////////////////////////////////////////

    // Catálogos compartidos (se cargan una vez)
    private volatile AeropuertosMap aeropuertosMap;
    private volatile VuelosMap vuelosMap;
    private volatile CargarPedidos pedidosCargados;
    private volatile Set<String> sedes;
    private volatile VuelosCancelados cancelados;
    // Estado de planificación por run
    private final Map<String, SolucionProgramacion> solucionesAnteriores = new ConcurrentHashMap<>();
    private final Map<String, OcupacionPorAeropuerto> ocupacionesPorRun = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> ventanasEnviadas = new ConcurrentHashMap<>();
    private final Map<String, Set<VueloProgramadoId>> vuelosCanceladosPorRun = new ConcurrentHashMap<>();
    private final Map<String, LectorPedidoMultiArchivo> lectorArchivoPorRun = new ConcurrentHashMap<>();
    private static final Duration PICKUP_WAIT = Duration.ofHours(2);

    public void addContext(String idRun, RunContext context) {
        contexts.put(idRun, context);
    }
    
    /**
     * Inicializa los catálogos compartidos si no están cargados
     */
    private synchronized void inicializarCatalogos(RunConfig.Scenario scenario) {
        if (aeropuertosMap == null) {
            System.out.println("[RunManager] Inicializando catálogos...");
            
            // Cargar aeropuertos
            aeropuertosMap = new AeropuertosMap();
            
            // **USO DE ARCHIVOMANAGER:** Usar el manager para el archivo de aeropuertos
            try (Scanner sc = archivoManager.getScannerForDataFile(AEROPUERTOS_FILENAME).orElse(null)) {
                if (sc != null) {
                    aeropuertosMap.leerDatos(sc);
                    System.out.println("[RunManager] Aeropuertos cargados: " + aeropuertosMap.size());
                } else {
                    // El manager ya imprimió el error, pero reconfirmamos
                    System.err.println("[RunManager] Falló la carga del archivo de aeropuertos."); 
                }
            } catch (Exception e) {
                System.err.println("[RunManager] Error procesando archivo de aeropuertos: " + e.getMessage());
            }
            
            // Cargar vuelos
            vuelosMap = new VuelosMap(aeropuertosMap);
            
            // **USO DE ARCHIVOMANAGER:** Usar el manager para el archivo de vuelos
            try (Scanner sc = archivoManager.getScannerForDataFile(VUELOS_FILENAME).orElse(null)) {
                if (sc != null) {
                    vuelosMap.leerDatos(sc);
                    System.out.println("[RunManager] Vuelos cargados");
                } else {
                    System.err.println("[RunManager] Falló la carga del archivo de vuelos.");
                }
            } catch (Exception e) {
                 System.err.println("[RunManager] Error procesando archivo de vuelos: " + e.getMessage());
            }
            
            // Cargar pedidos (solo fuera de OPERACION)
            pedidosCargados = new CargarPedidos();

            if (scenario != RunConfig.Scenario.OPERACION){
                //Ya no cargamos un único archivo de pedidos aquí
                //Solamente nos vamos a asegurar de tener pedidosCargados inicializados

                if (pedidosCargados == null) pedidosCargados = new CargarPedidos();
            }
            
            // Definir sedes
            sedes = new HashSet<>(Arrays.asList("SPIM", "EBCI", "UBBB"));

            // Cargar vuelos cancelados de archivo
            cancelados = new VuelosCancelados();
            try (Scanner sc = archivoManager.getScannerForDataFile(VUELOS_CANCELADOS_FILENAME).orElse(null)) {
                if (sc != null) {
                    cancelados.leerDatos(sc);
                    System.out.println("[RunManager] Vuelos cancelados cargados");
                } else {
                    System.err.println("[RunManager] Falló la carga del archivo de vuelos cancelados.");
                }
            } catch (Exception e) {
                 System.err.println("[RunManager] Error procesando archivo de vuelos cancelados: " + e.getMessage());
            }

            System.out.println("[RunManager] Catálogos inicializados correctamente");
        }
    }

    /**
     * Inicializa los catálogos compartidos PARA OPERACIÓN DIARIA si no están cargados
     */

    private static final String[] CODIGOS = {
            "EDDI", "EHAM", "EKCH", "LATI", "LBSF", "LDZA", "LKPR","LOWW", "OAKB", "OERK", "OJAI", "OMDB", "OOMS",
            "OPKC", "OSDI", "OYSN", "SABE", "SBBR", "SCEL", "SEQM", "SGAS", "SKBO", "SLLP", "SUAA", "SVMI", "UMMS", "VIDP"
    };


    private List<Path> construirRutasArchivosPedidos() {
        List<Path> paths = new ArrayList<>();
        Path baseDir = Paths.get("src", "main", "resources", "archivosPedidos");
        for (String codigo : CODIGOS) {
            String fileName = "_pedidos_" + codigo + "_.txt";
            paths.add(baseDir.resolve(fileName));
        }
        return paths;
    }

    private void inicializarLectorMultiArchivo(String id, Instant fechaInicio){
        try {
            // Si ya existe para este run, no hacemos nada
            if (lectorArchivoPorRun.containsKey(id)) {
                return;
            }

            List<Path> paths = construirRutasArchivosPedidos();
            LectorPedidoMultiArchivo lectorPedidoMultiArchivo = new LectorPedidoMultiArchivo(paths);

            /// Cuidado con el zoneOffset
            LocalDateTime fechaInicioLT =
                    LocalDateTime.ofInstant(fechaInicio, ZoneOffset.UTC);

            long t0 = System.nanoTime();
            lectorPedidoMultiArchivo.saltarHasta(fechaInicioLT);
            long t1 = System.nanoTime();

            System.out.printf(
                    "[RunManager] LectorPedidosSimulacion saltarHasta(%s) = %.3f ms%n",
                    fechaInicioLT, (t1 - t0) / 1_000_000.0
            );

            lectorArchivoPorRun.put(id, lectorPedidoMultiArchivo);
        }
        catch (Exception e){
            System.err.println("[RunManager] Error inicializando lector de pedidos: " + e.getMessage());
            e.printStackTrace();
            return;
        }
    }


    //Registramos las cancelaciones de vuelos por runId
    public void registrarCancelacionVuelo(String runId, VueloProgramadoId vueloProgramadoId){
        vuelosCanceladosPorRun
                .computeIfAbsent(runId, k -> ConcurrentHashMap.newKeySet())
                .add(vueloProgramadoId);

        System.out.println("[RunManager] \uD83D\uDEA9 Vuelo cancelado registrado para run "
                + runId + ": " + vueloProgramadoId);
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

        // Retornamos el ahora simulado
        Instant simulatedNow = ctx.simStartUtc().plusMillis(simDeltaMs);
        
        // Limitar el simNow al final de la última llegada de vuelo
        SolucionProgramacion ultimaSolucion = solucionesAnteriores.get(runId);
        if (ultimaSolucion != null) {
            Instant ultimaLlegada = obtenerUltimaLlegada(ultimaSolucion);
            if (ultimaLlegada != null && simulatedNow.isAfter(ultimaLlegada)) {
                return ultimaLlegada;
            }
        }
        
        return simulatedNow;
    }
    
    private Instant obtenerUltimaLlegada(SolucionProgramacion solucion) {
        if (solucion == null || solucion.getCargaPorVuelo() == null) {
            return null;
        }
        
        return solucion.getCargaPorVuelo().getAsignado().keySet().stream()
                .map(VueloProgramadoId::getLlegadaUtc)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
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
                switch (config.scenario()){
                    case OPERACION ->
                        runOperacion(runId, config);

                    case SIM_SEMANAL, COLAPSO ->
                        runSimulacion(runId, config);

                    default -> throw new IllegalStateException("Unexpected value: " + config.scenario());
                }

                System.out.println("Salí del bucle, mi id es:" + id);


                firstExecution = true;

                if (firstExecution) {
                    System.out.println("Ahora firstExecution es:" + firstExecution);
                }

                // Fin normal
                states.put(id, RunState.COMPLETED);
                broadcastFinished(id, StopReason.FIN_DE_RANGO);

                // Limpiamos
                // Solución rápida:
                aeropuertosMap = null;
                vuelosMap = null;
                pedidosCargados = null;

            }
            catch (Throwable e){
                states.put(id, RunState.FAILED);
                broadcastFinished(id, StopReason.ERROR);
            }
            finally {
                cleanupRunState(id);
            }

        });
    }

    private void cleanupRunState(String id) {
        //try { unregisterAllListeners(id); } catch (Throwable ignored) {}

        contexts.remove(id);
        states.remove(id);
        paused.remove(id);
        cancelled.remove(id);

        // estructuras por run:
        ventanasEnviadas.remove(id);
        solucionesAnteriores.remove(id);
        ocupacionesPorRun.remove(id);
        lectorArchivoPorRun.remove(id);
        //queues.remove(id);          // si existe
    }

    private boolean isCancelled(String id){
        return cancelled.get(id).get();
    }

    private boolean sleepToEndWindow(String id, Instant wEnd){
        //Acá vamos a que el reloj simulado cruce el fin de ventana
        while (true){
            //En caso de existir pausa o cancelación (por ahora, esto no ocurrirá)
            if (isCancelled(id)) return true;

            while (paused.get(id).get() && !cancelled.get(id).get()) {
                sleepQuietly(Duration.ofMillis(100)); // dormimos cortito mientras esté pausado
            }
            if (isCancelled(id)) return true;

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

            if (isCancelled(id)) return true;
        }
        return false;
    }

    /// Funciones para cada escenario.

    /// 1. Run de Simulación
    private void runSimulacion(RunId runId, RunConfig config){
        System.out.println("Estamos en SIM SEMANAL");

        final String id = runId.value();

        Instant wStart = config.fechaInicio();
        Instant wEnd = wStart.plus(config.horasVentana());
        int idx = 0;

        System.out.println("[RunManager] Config: fechaInicio=" + config.fechaInicio() + ", fechaFin=" + config.fechaFin());
        System.out.println("[RunManager] Ventana inicial: wStart=" + wStart + ", wEnd=" + wEnd);

        System.out.println("Dentro de runSimulación firstExecution es:" + firstExecution);

        inicializarLectorMultiArchivo(id, wStart);

        while (!isCancelled(id) && (config.fechaFin() == null || !wStart.isAfter(config.fechaFin()))) {
            /// Revisar esto:
            // Pausa cooperativa entre ventanas
            while (paused.get(id).get() && !isCancelled(id)) {
                sleepQuietly(Duration.ofMillis(80));
            }
            if (isCancelled(id)) break;

            // ===== LÓGICA DE PLANIFICACIÓN POR VENTANAS =====

            if (firstExecution){
                System.out.println("Soy true");
                int a = 0;
            }

            // Inicializar catálogos si es necesario
            inicializarCatalogos(config.scenario());

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
                // 1. Preparar estado anterior si existe
                SolucionProgramacion solucionAnterior = solucionesAnteriores.get(id);
                Map<String, List<ArriboExogeno>> enVuelo = Map.of();
                List<OcupacionAlmacen> reservas = List.of();

                // Actualizar pedidos: eliminar completados y ajustar cantidades de los en progreso
                if (solucionAnterior != null) {
                    System.out.println("[RunManager] Antes de eliminarYActualizarCumplidosHasta: " + 
                        pedidosCargados.getLista().size() + " pedidos en cola");

                    Set<VueloProgramadoId> vuelosCancelados =
                            vuelosCanceladosPorRun.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet());
                    //

                    if (!vuelosCancelados.isEmpty()) {
                        System.out.println("[RunManager]: Procesando cancelaciones: " + vuelosCancelados.size());

                        //Considerar si hay que colocar los vuelos cancelados en algun otro lado para enchufar en el TEG

                        procesarCancelaciones(id, vuelosCancelados, solucionAnterior);

                        /// Dejamos el set vacío (por ahora):
                        vuelosCanceladosPorRun.get(id).clear();
                    }

                    pedidosCargados.eliminarYActualizarCumplidosHasta(wStart, solucionAnterior);
                    System.out.println("[RunManager] Después de eliminarYActualizarCumplidosHasta: " + 
                        pedidosCargados.getLista().size() + " pedidos en cola");
                    enVuelo = EstadoAnteriorExtractor.construirArribosEnVuelo(solucionAnterior, wStart);
                    reservas = EstadoAnteriorExtractor.reservasDesdeSolucionAnterior(solucionAnterior, wStart, Duration.ofHours(2));
                }

                // 2. Obtener pedidos de la ventana actual (ya actualizados)
                cargarPedidosDesdeLector(id, pedidosCargados, wEnd);
                //VentanaPedidos ventana = pedidosCargados.acumuladoHasta(wEnd);
                VentanaPedidos ventana = pedidosCargados.acumuladoEntre(wStart,wEnd);
                List<Pedido> pedidosVentana = ventana.pedidos();


                System.out.println("[RunManager] Los pedidos para esta ventana son: " + pedidosVentana.size());

                if (pedidosVentana.isEmpty()) {
                    System.out.println("[RunManager] No hay pedidos en la ventana " + idx);
                    // Marcar ventana como enviada aunque esté vacía
                    ventanasEnviadasRun.add(windowIdISO);
                    broadcastWindow(new WindowPacket(id, idx, wStart, wEnd, List.of(),
                            convertirPedidosADTO(List.of(), null)));

                    //Llamamos al sleep (para que el reloj simulado cruce fin de ventana):
                    //Si se cancela durante el sleep, salimos del bucle
                    if (sleepToEndWindow(id, wEnd)) break;

                    // Avanzar a la siguiente ventana antes de continuar
                    idx++;
                    wStart = wEnd;
                    wEnd = wEnd.plus(config.horasVentana());
                    pedidosCargados.setUtcNormalizada(false);
                    continue;
                }

                if (isCancelled(id)) break;

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
                // 3.5 Cancelar vuelos de archivo

                List<String> vuelosCancelados = cancelados.obtenerVuelosCancelados(wStart,wEnd);
                if(!vuelosCancelados.isEmpty()){
                    System.out.println("[RunManager] Vuelos cancelados en la ventana " + idx + ": " + vuelosCancelados);
                    teg.cancelarVuelos(vuelosCancelados);
                }

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
                reparadores.add(new SplitRepair(new ArrayList<>(sedes), teg));

                ALNS alns = new ALNS(teg, pedidosVentana, destructores, reparadores, wStart, ocupacionPorAeropuerto);
                SolucionProgramacion solucionOptima = alns.ejecutar(seed);

                if (isCancelled(id)) break;

                // 6. Guardar solución para la siguiente ventana y sincronizar ocupación
                actualizarOcupacionDesdeSolucion(id, solucionOptima, reservas, enVuelo);
                solucionesAnteriores.put(id, solucionOptima);

                // 7. Extraer vuelos y pedidos de la ventana actual para broadcasting
                final Instant wStartFinal = wStart;
                final Instant wEndFinal = wEnd;

                if (isCancelled(id)) break;

                List<Object> vuelosVentana = extraerVuelosDeVentana(solucionOptima, wStartFinal, wEndFinal);
                
                // IMPORTANTE: Enviar TODOS los pedidos procesados (incluye parciales de ventanas anteriores)
                // para que el frontend vea el estado actualizado de cada pedido
                List<Object> pedidosVentanaDTO = convertirPedidosADTO(pedidosVentana, solucionOptima);

                //Esto es para depurar

                // 8. Marcar ventana como enviada y hacer broadcast
                ventanasEnviadasRun.add(windowIdISO);
                broadcastWindow(new WindowPacket(id, idx, wStart, wEnd, vuelosVentana, pedidosVentanaDTO));

                System.out.println("[RunManager] Ventana " + idx + " procesada exitosamente. Vuelos: " + vuelosVentana.size() + ", Pedidos: " + pedidosVentanaDTO.size());

            } catch (Exception e) {
                System.err.println("[RunManager] Error procesando ventana " + idx + ": " + e.getMessage());
                e.printStackTrace();
                // Continuar con la siguiente ventana en caso de error
            }

            //Llamamos al sleep (para que el reloj simulado cruce fin de ventana):
            if (sleepToEndWindow(id, wEnd)) break;

            // Siguiente ventana
            idx++;
            wStart = wEnd;
            wEnd   = wEnd.plus(config.horasVentana());
            pedidosCargados.setUtcNormalizada(false);
        }


    }

    /// 2. Run de Operación Diaria
    private void runOperacion(RunId runId, RunConfig config){
        final String id = runId.value();

        Instant wStart = config.fechaInicio();

        /// Nota: Dado que ahorita solo enviamos horasVentana (osea, horas), estoy comentando esto.
        /// Tenemos que hacer cambios para que soporte por minutos (no en el algoritmo, creo que ahí no,
        /// sino en RunConfig (línea 59 en dicho archivo))

        Duration minutosVentana = Duration.ofMinutes(1);

        //Instant wEnd = wStart.plus(config.horasVentana());
        Instant wEnd = wStart.plus(minutosVentana);

        int idx = 0;

        System.out.println("Estamos dentro de runOperacion");

        System.out.println("En esta iteración, wStart es: " + wStart + ", wEnd es: " + wEnd);
        System.out.println("Voy a entrar al bucle, mi id es:" + id);

        while (!cancelled.get(id).get() && (config.fechaFin() == null || !wStart.isAfter(config.fechaFin()))){
            while (paused.get(id).get() && !cancelled.get(id).get()) {
                sleepQuietly(Duration.ofMillis(80));
            }
            if (cancelled.get(id).get()) break;

            /// 1. Inicializar catálogos (vuelos + aeropuertos ONLY)
            inicializarCatalogos(config.scenario());

            /// Cuidado con esto, no se que tanto rompa
            // Verificar si ya enviamos esta ventana (idempotencia)
            String windowIdISO = wStart.toString();
            Set<String> ventanasEnviadasRun = ventanasEnviadas.computeIfAbsent(id, k -> new HashSet<>());
            if (ventanasEnviadasRun.contains(windowIdISO)) {
                System.out.println("[RunManager] Ventana ya enviada, saltando: " + windowIdISO);
                // Avanzar a la siguiente ventana antes de continuar
                idx++;
                wStart = wEnd;
                wEnd = wEnd.plus(minutosVentana);
                continue;
            }

            System.out.println("[RunManager] Procesando ventana " + idx + ": " + wStart + " - " + wEnd);

            try {

                ///  1. Preparar estado anterior
                // 1. Preparar estado anterior si existe
                SolucionProgramacion solucionAnterior = solucionesAnteriores.get(id);
                Map<String, List<ArriboExogeno>> enVuelo = Map.of();
                List<OcupacionAlmacen> reservas = List.of();

                    /// Actualizar el estado de los pedidos con dicho estadoAnterior
                if (solucionAnterior != null) {
                    System.out.println("[RunManager] Antes de eliminarYActualizarCumplidosHasta: " +
                            pedidosCargados.getLista().size() + " pedidos en cola");

                    Set<VueloProgramadoId> vuelosCancelados =
                            vuelosCanceladosPorRun.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet());

                    if (!vuelosCancelados.isEmpty()) {
                        System.out.println("[RunManager]: Procesando cancelaciones: " + vuelosCancelados.size());

                        //Considerar si hay que colocar los vuelos cancelados en algun otro lado para enchufar en el TEG

                        procesarCancelaciones(id, vuelosCancelados, solucionAnterior);

                        /// Dejamos el set vacío (por ahora):
                        vuelosCanceladosPorRun.get(id).clear();
                    }

                    pedidosCargados.eliminarYActualizarCumplidosHasta(wStart, solucionAnterior);
                    System.out.println("[RunManager] Después de eliminarYActualizarCumplidosHasta: " +
                            pedidosCargados.getLista().size() + " pedidos en cola");
                    enVuelo = EstadoAnteriorExtractor.construirArribosEnVuelo(solucionAnterior, wStart);
                    reservas = EstadoAnteriorExtractor.reservasDesdeSolucionAnterior(solucionAnterior, wStart, Duration.ofHours(2));
                }

                ///  2. Obtener pedidos de la ventana actual

                    /// Primero, cargamos de queue a pedidosCargados y limpiamos queue
                    cargarPedidosDesdeQueue(id, pedidosCargados);
                    VentanaPedidos ventana = pedidosCargados.acumuladoHasta(wEnd);
                    List<Pedido> pedidosVentana = ventana.pedidos();

                    /// Si no hay nada en la ventana, duerme
                    // sleepToEndWindow(id, wEnd);
                    if (pedidosVentana.isEmpty()) {
                        System.out.println("[RunManager] No hay pedidos en la ventana " + idx);
                        // Marcar ventana como enviada aunque esté vacía
                        ventanasEnviadasRun.add(windowIdISO);
                        broadcastWindow(new WindowPacket(id, idx, wStart, wEnd, List.of(),
                                convertirPedidosADTO(List.of(), null)));

                        //Llamamos al sleep (para que el reloj simulado cruce fin de ventana):
                        sleepToEndWindow(id, wEnd);

                        // Avanzar a la siguiente ventana antes de continuar
                        idx++;
                        wStart = wEnd;
                        wEnd = wEnd.plus(minutosVentana);
                        //Como se ha diseñado para que lea todo0 de un archivo, tenemos que hacer esto para que funcione por ventana
                        pedidosCargados.setUtcNormalizada(false);
                        continue;
                    }

                /// 3. Construimos TEG

                Instant finTEG = wEnd.plus(config.horizon());
                TEGParametros params = TEGParametros.builder()
                        .inicioUtc(wStart)
                        .finUtc(finTEG)
                        .sedes(sedes)
                        .arribosLibres(enVuelo)
                        .reservasWaitIniciales(reservas)
                        .build();

                VuelosTEG teg = new TEGEventBuilder(aeropuertosMap, vuelosMap).construir(params);

                /// 4. Generamos la solución inicial (seed)
                OcupacionPorAeropuerto ocupacionPorAeropuerto = ocupacionesPorRun.computeIfAbsent(id, k -> new OcupacionPorAeropuerto(aeropuertosMap));
                SSPGeneradorSeed ssp = new SSPGeneradorSeed(sedes, Map.of(), ocupacionPorAeropuerto);
                SolucionProgramacion seed = ssp.generarSeed(teg, pedidosVentana, wStart);

                /// 5. Ejecutamos ALNS
                List<DestructionOperator> destructores = new ArrayList<>();
                destructores.add(new RandomRemoval(20));
                destructores.add(new WorstRemoval(20));

                List<RepairOperator> reparadores = new ArrayList<>();
                reparadores.add(new RegretRepair(2, new ArrayList<>(sedes), teg));
                reparadores.add(new SplitRepair(new ArrayList<>(sedes), teg));

                ALNS alns = new ALNS(teg, pedidosVentana, destructores, reparadores, wStart, ocupacionPorAeropuerto);
                SolucionProgramacion solucionOptima = alns.ejecutar(seed);

                /// 6. Guardar solución para la siguiente ventana y sincronizar ocupación
                actualizarOcupacionDesdeSolucion(id, solucionOptima, reservas, enVuelo);
                solucionesAnteriores.put(id, solucionOptima);

                /// 7. Extraer vuelos y pedidos de la ventana actual para broadcasting
                final Instant wStartFinal = wStart;
                final Instant wEndFinal = wEnd;
                
                // En operación diaria, incluir vuelos que salen dentro del horizonte completo (24 horas)
                // porque las ventanas son de 1 minuto pero los vuelos se planifican hasta 24h adelante
                final Instant finExtraccion = config.scenario() == RunConfig.Scenario.OPERACION 
                    ? wStart.plus(config.horizon())  // wStart + 24 horas
                    : wEndFinal;                      // Para otros escenarios, usar wEnd normal

                List<Object> vuelosVentana = extraerVuelosDeVentana(solucionOptima, wStartFinal, finExtraccion);

                // IMPORTANTE: Enviar TODOS los pedidos procesados (incluye parciales de ventanas anteriores)
                // para que el frontend vea el estado actualizado de cada pedido
                List<Object> pedidosVentanaDTO = convertirPedidosADTO(pedidosVentana, solucionOptima);

                /// 8. Marcar ventana como enviada y hacer broadcast

                ventanasEnviadasRun.add(windowIdISO);
                broadcastWindow(new WindowPacket(id, idx, wStart, wEnd, vuelosVentana, pedidosVentanaDTO));

                System.out.println("[RunManager] Ventana " + idx + " procesada exitosamente. Vuelos: " + vuelosVentana.size() + ", Pedidos: " + pedidosVentanaDTO.size());


                System.out.println("Esto es operación diaria y estoy dentro del bucle. No hago nada más. El " +
                        "tiempo actual es:" + currentSimNow(id));
            }
            catch (Exception e){
                System.err.println("[RunManager] Error procesando ventana " + idx + ": " + e.getMessage());
                e.printStackTrace();
            }


            //Llamamos al sleep (para que el reloj simulado cruce fin de ventana):
            sleepToEndWindow(id, wEnd);

            // Siguiente ventana
            idx++;
            wStart = wEnd;
            wEnd   = wEnd.plus(minutosVentana);
            //Como se ha diseñado para que lea todo0 de un archivo, tenemos que hacer esto para que funcione por ventana
            pedidosCargados.setUtcNormalizada(false);

        }

    }

    private void cargarPedidosDesdeQueue(String id, CargarPedidos pedidosCargados){
        ConcurrentLinkedQueue<Pedido> queue = queues.computeIfAbsent(id, k -> new ConcurrentLinkedQueue<>());

        //Retiramos el pedido de la cola, y lo agregamos a pedidosCargados
        while(!queue.isEmpty()){
            Pedido p = queue.poll();
            pedidosCargados.agregar(p);
        }

        //Realizamos el mismo proceso de normalizar y ordenar
        pedidosCargados.normalizarUtc(aeropuertosMap);
            pedidosCargados.ordenarPorUTC();
        System.out.println("[cargarPedidosDesdeQueue] Pedidos cargados: " + pedidosCargados.getLista().size());
    }

    private void cargarPedidosDesdeLector(String id, CargarPedidos pedidosCargados, Instant wEnd){
        LectorPedidoMultiArchivo lectorArchivo = lectorArchivoPorRun.get(id);

        if (lectorArchivo != null) {
            // Convertimos wEnd (Instant) a LocalDateTime en UTC para el lector
            try{
                LocalDateTime finVentanaLT = LocalDateTime.ofInstant(wEnd, ZoneOffset.UTC);

                List<Pedido> nuevosArchivo = lectorArchivo.leerHasta(finVentanaLT);

                for (Pedido p : nuevosArchivo) {
                    pedidosCargados.agregar(p);
                }

                if (!nuevosArchivo.isEmpty()) {
                    System.out.println("[RunManager] Pedidos leídos desde archivos en esta ventana: "
                            + nuevosArchivo.size());
                }

            } catch (IOException e) {
                System.err.println("[RunManager] Error leyendo pedidos desde archivos para run " + id
                        + " hasta ventana " + wEnd + ": " + e.getMessage());
                e.printStackTrace();
            }

        }

        //Realizamos el mismo proceso de normalizar y ordenar
        pedidosCargados.normalizarUtc(aeropuertosMap);
        pedidosCargados.ordenarPorUTC();
        System.out.println("[cargarPedidosDesdeLector] Pedidos cargados: " + pedidosCargados.getLista().size());

    }

    private void procesarCancelaciones(String runId, Set<VueloProgramadoId> vuelosCancelados, SolucionProgramacion solucionAnterior){
        /// Aca hay que poner la lógica
        /// 1) Para cada vuelo de vuelosCancelados, recorrer toda la solucionProgramacion.
        /// Podríamos también, para ahorrar tiempo, revisar si tiene carga asignada en CargaPorVuelo
        /// 2) En cualquier caso, si tiene una ruta simplemente eliminarla como en los destructores del ALNS.


        CargaPorVuelo cargaPorVuelo = solucionAnterior.getCargaPorVuelo();
        Map<VueloProgramadoId, Integer> asignados = solucionAnterior.getCargaPorVuelo().getAsignado();
        Map<Integer, PlanPedido> planes = solucionAnterior.asMap();
        OcupacionPorAeropuerto ocupacionPorAeropuerto = ocupacionesPorRun.get(runId);


        /// 1) Con esto nos quedamos solamente con los vuelos cancelados que tienen carga asignada
        Set<VueloProgramadoId> canceladosConCarga = vuelosCancelados.stream()
                .filter(v -> cargaPorVuelo.getAsignado().containsKey(v))
                .collect(Collectors.toSet());

        //Si no hay vuelos con carga:
        if (canceladosConCarga.isEmpty()) {
            System.out.println("[Cancel] No hay vuelos cancelados con carga. Nada que hacer.");
            return;
        }

        //Caso contrario:
        System.out.println("[Cancel] Vuelos cancelados con carga: " + canceladosConCarga.size());

        /// 2) Ahora, recorremos cada pedido una única vez
        for (PlanPedido plan : planes.values()) {
            List<RutaAsignada> rutas = plan.getRutas();
            List<RutaAsignada> rutasFiltradas = new ArrayList<>();

            //Para trackear si se eliminó como mínimo 1 ruta
            boolean deleted = false;

            //Recorremos cada ruta (ej: una ruta es A->B->C->D)

            for (RutaAsignada ruta : rutas) {
                boolean rutaVueloCancelado = ruta.getTramos().stream()
                        .map(TramoAsignado::getVuelo)
                        .anyMatch(canceladosConCarga::contains);

                //Si es que tiene un vuelo cancelado
                if (rutaVueloCancelado) {
                    int qRuta = ruta.getCantidad();
                    final List<TramoAsignado> tr = ruta.getTramos();

                    //Acá liberamos las capacidades de los almacenes que están en la ruta + 2h de espera

                    //1) ESCALAS: liberar [llegada(tr i), salida(tr i+1)) en aeropuerto destino del tramo
                    for (int i = 0; i < tr.size() - 1; i++) {
                        TramoAsignado tPrev = tr.get(i);
                        TramoAsignado tNext = tr.get(i + 1);

                        String apEscala = tPrev.getVuelo().getDestino();
                        Instant ini = tPrev.getLlegadaUtc();
                        Instant fin = tNext.getVuelo().getSalidaUtc();

                        if (ini != null && fin != null && ini.isBefore(fin)) {
                            ocupacionPorAeropuerto.liberar(apEscala, ini, fin, qRuta);
                        }
                    }

                    // 2) DESTINO FINAL: liberar +2h
                    TramoAsignado last = tr.get(tr.size() - 1);
                    String apFinal = last.getVuelo().getDestino();
                    Instant arr = last.getLlegadaUtc();
                    if (arr != null) {
                        Instant fin2h = arr.plus(Duration.ofHours(2));
                        ocupacionPorAeropuerto.liberar(apFinal, arr, fin2h, qRuta);
                    }

                    // 3) CARGA EN VUELOS: revertir asignaciones por tramo
                    /// Igual sospecho que esto es innecesario, ya que el vuelo se va a cancelar xd
                    for (TramoAsignado t : tr) {
                        int qTramo = t.getCantidad();                      // usa la cantidad efectiva del tramo
                        if (qTramo != 0) {
                            solucionAnterior.getCargaPorVuelo().asignar(t.getVuelo(), -qTramo);
                        }
                    }

                    deleted = true;
                }
                else {
                    rutasFiltradas.add(ruta);
                }

            }

            //Si se eliminó una ruta como mínimo
            if (deleted){
                PlanPedido nuevo = PlanPedido.builder()
                        .idPedido(plan.getIdPedido())
                        .aeropuertoDestino(plan.getAeropuertoDestino())
                        .creadoUtc(plan.getCreadoUtc())
                        .demanda(plan.getDemanda())
                        .rutas(rutasFiltradas)
                        .build();
                solucionAnterior.getPlanPorPedido().put(nuevo.getIdPedido(), nuevo);


                System.out.println("[Cancel]   Pedido " + plan.getIdPedido()
                        + " → rutas después de cancelar: " + rutasFiltradas.size());
            }
        }

        int x = 0;

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

    /**
     * Calcula la ocupación actual de todos los aeropuertos para un run específico
     */
    public Map<String, Map<String, Object>> getCurrentAirportOccupancy(String runId) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        
        if (aeropuertosMap == null || aeropuertosMap.size() == 0) {
            return result;
        }
        
        // Obtener ocupación por aeropuerto para este run
        OcupacionPorAeropuerto ocupacion = ocupacionesPorRun.get(runId);
        if (ocupacion == null) {
            //System.out.println("[RunManager.getCurrentAirportOccupancy] Ocupacion null para runId: " + runId);
            return result;
        }
        
        // Obtener el tiempo simulado actual
        Instant simNow = currentSimNow(runId);
        
        // Iterar sobre todos los aeropuertos
        for (String codigo : aeropuertosMap.keys()) {
            try {
                int ocupacionActual = ocupacion.ocupacion(codigo, simNow);
                int capacidadTotal = aeropuertosMap.getCapBodega(codigo);
                
                // Calcular carga llegando y saliendo AHORA MISMO
                Map<String, Object> eventosActuales = calcularEventosActuales(runId, codigo, simNow);
                int cargaLlegando = (Integer) eventosActuales.getOrDefault("cargaLlegando", 0);
                int cargaSaliendo = (Integer) eventosActuales.getOrDefault("cargaSaliendo", 0);
                
                // Ocupación "efectiva" incluyendo lo que está llegando (pero no lo que está saliendo, ya no está)
                // La ocupación actual ya refleja lo que salió, pero podemos mostrar lo que está llegando
                int disponible = ocupacionActual < capacidadTotal ? capacidadTotal - ocupacionActual : 0;
                double porcentaje = capacidadTotal > 0 ? (double) ocupacionActual / capacidadTotal : 0.0;
                
                // Calcular estadísticas de vuelos futuros (próximas 24 horas)
                Instant simNow24h = simNow.plus(24, java.time.temporal.ChronoUnit.HOURS);
                Map<String, Object> estadisticasFuturas = calcularEstadisticasFuturas(runId, codigo, simNow, simNow24h);
                
                Map<String, Object> airportData = new HashMap<>();
                airportData.put("ocupacionActual", ocupacionActual);
                airportData.put("capacidadTotal", capacidadTotal);
                airportData.put("disponible", disponible);
                airportData.put("porcentaje", porcentaje);
                airportData.put("cargaLlegando", cargaLlegando);
                airportData.put("cargaSaliendo", cargaSaliendo);
                airportData.put("estadisticasFuturas", estadisticasFuturas);
                
                result.put(codigo, airportData);
            } catch (Exception e) {
                // Ignorar errores individuales de aeropuertos
                System.err.println("[RunManager] Error calculando ocupación para " + codigo + ": " + e.getMessage());
            }
        }
        
        return result;
    }
    
    private void actualizarOcupacionDesdeSolucion(String runId,
                                                  SolucionProgramacion solucionOptima,
                                                  List<OcupacionAlmacen> reservasPrevias,
                                                  Map<String, List<ArriboExogeno>> arribosEnVuelo) {
        OcupacionPorAeropuerto nuevaOcupacion = construirOcupacionDesdeSolucion(solucionOptima);
        if (reservasPrevias != null && !reservasPrevias.isEmpty()) {
            for (OcupacionAlmacen reserva : reservasPrevias) {
                if (reserva == null || reserva.cantidad() <= 0) continue;
                Instant inicio = reserva.desde();
                Instant fin = reserva.hasta();
                if (inicio != null && fin != null && inicio.isBefore(fin)) {
                    nuevaOcupacion.reservar(reserva.aeropuerto(), inicio, fin, reserva.cantidad());
                }
            }
        }
        if (arribosEnVuelo != null && !arribosEnVuelo.isEmpty()) {
            for (Map.Entry<String, List<ArriboExogeno>> entry : arribosEnVuelo.entrySet()) {
                String aeropuerto = entry.getKey();
                if (aeropuerto == null) continue;
                List<ArriboExogeno> llegadas = entry.getValue();
                if (llegadas == null) continue;
                for (ArriboExogeno arribo : llegadas) {
                    if (arribo == null || arribo.cantidad() <= 0) continue;
                    Instant llegada = arribo.arriboUtc();
                    if (llegada == null) continue;
                    Instant fin = llegada.plus(PICKUP_WAIT);
                    nuevaOcupacion.reservar(aeropuerto, llegada, fin, arribo.cantidad());
                }
            }
        }
        ocupacionesPorRun.put(runId, nuevaOcupacion);
    }

    private OcupacionPorAeropuerto construirOcupacionDesdeSolucion(SolucionProgramacion solucionOptima) {
        OcupacionPorAeropuerto nueva = new OcupacionPorAeropuerto(aeropuertosMap);
        if (solucionOptima == null || solucionOptima.getPlanPorPedido() == null) {
            return nueva;
        }

        for (PlanPedido plan : solucionOptima.getPlanPorPedido().values()) {
            if (plan == null || plan.getRutas() == null) {
                continue;
            }

            for (RutaAsignada ruta : plan.getRutas()) {
                if (ruta == null) continue;
                int cantidad = ruta.getCantidad();
                if (cantidad <= 0) continue;

                List<TramoAsignado> tramos = ruta.getTramos();
                if (tramos == null || tramos.isEmpty()) {
                    continue;
                }

                for (int i = 0; i < tramos.size() - 1; i++) {
                    TramoAsignado actual = tramos.get(i);
                    TramoAsignado siguiente = tramos.get(i + 1);
                    if (actual == null || siguiente == null) continue;
                    VueloProgramadoId vueloActual = actual.getVuelo();
                    VueloProgramadoId vueloSiguiente = siguiente.getVuelo();
                    if (vueloActual == null || vueloSiguiente == null) continue;

                    Instant inicio = vueloActual.getLlegadaUtc();
                    Instant fin = vueloSiguiente.getSalidaUtc();
                    if (inicio != null && fin != null && inicio.isBefore(fin)) {
                        nueva.reservar(vueloActual.getDestino(), inicio, fin, cantidad);
                    }
                }

                TramoAsignado ultimo = tramos.get(tramos.size() - 1);
                if (ultimo != null && ultimo.getVuelo() != null) {
                    Instant llegadaFinal = ultimo.getVuelo().getLlegadaUtc();
                    if (llegadaFinal != null) {
                        Instant fin = llegadaFinal.plus(PICKUP_WAIT);
                        nueva.reservar(ultimo.getVuelo().getDestino(), llegadaFinal, fin, cantidad);
                    }
                }
            }
        }

        return nueva;
    }

    private static void sleepQuietly(Duration d) {
        try { Thread.sleep(d.toMillis()); } catch (InterruptedException ignored) {}
    }
    
    /**
     * Calcula carga que está llegando y saliendo EN ESTE MOMENTO (ventana de ±5 minutos)
     */
    private Map<String, Object> calcularEventosActuales(String runId, String codigoAeropuerto, Instant simNow) {
        Map<String, Object> eventos = new HashMap<>();
        int cargaLlegando = 0;
        int cargaSaliendo = 0;
        
        try {
            // Ventana de tiempo para considerar "ahora mismo" (±5 minutos)
            Duration ventana = Duration.ofMinutes(5);
            Instant simNowMinus = simNow.minus(ventana);
            Instant simNowPlus = simNow.plus(ventana);
            
            // Obtener la solución actual
            SolucionProgramacion solucion = solucionesAnteriores.get(runId);
            if (solucion == null || solucion.getCargaPorVuelo() == null) {
                eventos.put("cargaLlegando", 0);
                eventos.put("cargaSaliendo", 0);
                return eventos;
            }
            
            CargaPorVuelo cargaPorVuelo = solucion.getCargaPorVuelo();
            
            // Iterar sobre todos los vuelos asignados en la solución
            for (java.util.Map.Entry<VueloProgramadoId, Integer> entry : cargaPorVuelo.getAsignado().entrySet()) {
                VueloProgramadoId vueloId = entry.getKey();
                int cantidadAsignada = entry.getValue();
                
                if (cantidadAsignada > 0 && vueloId.getSalidaUtc() != null && vueloId.getLlegadaUtc() != null) {
                    // Vuelos que están saliendo AHORA desde este aeropuerto
                    if (codigoAeropuerto.equals(vueloId.getOrigen()) &&
                        !vueloId.getSalidaUtc().isBefore(simNowMinus) &&
                        !vueloId.getSalidaUtc().isAfter(simNowPlus)) {
                        cargaSaliendo += cantidadAsignada;
                    }
                    
                    // Vuelos que están llegando AHORA a este aeropuerto
                    if (codigoAeropuerto.equals(vueloId.getDestino()) &&
                        !vueloId.getLlegadaUtc().isBefore(simNowMinus) &&
                        !vueloId.getLlegadaUtc().isAfter(simNowPlus)) {
                        cargaLlegando += cantidadAsignada;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[RunManager] Error calculando eventos actuales para " + codigoAeropuerto + ": " + e.getMessage());
        }
        
        eventos.put("cargaLlegando", cargaLlegando);
        eventos.put("cargaSaliendo", cargaSaliendo);
        
        return eventos;
    }
    
    /**
     * Calcula estadísticas de vuelos futuros para un aeropuerto específico
     */
    private Map<String, Object> calcularEstadisticasFuturas(String runId, String codigoAeropuerto, Instant simNow, Instant simNow24h) {
        Map<String, Object> stats = new HashMap<>();
        int llegadasPrevistas = 0;
        int salidasPrevistas = 0;
        int cargaEntrante = 0;
        int cargaSaliente = 0;
        
        try {
            // Obtener la solución actual
            SolucionProgramacion solucion = solucionesAnteriores.get(runId);
            if (solucion == null || solucion.getCargaPorVuelo() == null) {
                stats.put("llegadasPrevistas", 0);
                stats.put("salidasPrevistas", 0);
                stats.put("cargaEntrante", 0);
                stats.put("cargaSaliente", 0);
                return stats;
            }
            
            CargaPorVuelo cargaPorVuelo = solucion.getCargaPorVuelo();
            
            // Iterar sobre todos los vuelos asignados en la solución
            for (java.util.Map.Entry<VueloProgramadoId, Integer> entry : cargaPorVuelo.getAsignado().entrySet()) {
                VueloProgramadoId vueloId = entry.getKey();
                int cantidadAsignada = entry.getValue();
                
                // Solo contar vuelos con carga asignada que están en el futuro
                if (cantidadAsignada > 0 && vueloId.getSalidaUtc() != null && vueloId.getLlegadaUtc() != null) {
                    // Contar salidas futuras desde este aeropuerto
                    if (codigoAeropuerto.equals(vueloId.getOrigen()) && 
                        !vueloId.getSalidaUtc().isBefore(simNow) && 
                        !vueloId.getSalidaUtc().isAfter(simNow24h)) {
                        salidasPrevistas++;
                        cargaSaliente += cantidadAsignada;
                    }
                    
                    // Contar llegadas futuras a este aeropuerto
                    if (codigoAeropuerto.equals(vueloId.getDestino()) && 
                        !vueloId.getLlegadaUtc().isBefore(simNow) && 
                        !vueloId.getLlegadaUtc().isAfter(simNow24h)) {
                        llegadasPrevistas++;
                        cargaEntrante += cantidadAsignada;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[RunManager] Error calculando estadísticas futuras para " + codigoAeropuerto + ": " + e.getMessage());
        }
        
        stats.put("llegadasPrevistas", llegadasPrevistas);
        stats.put("salidasPrevistas", salidasPrevistas);
        stats.put("cargaEntrante", cargaEntrante);
        stats.put("cargaSaliente", cargaSaliente);
        
        return stats;
    }
    
    /**
     * Extrae los vuelos que caen dentro de la ventana temporal especificada,
     * incluyendo el manifiesto de carga (qué pedidos van en cada vuelo)
     */
    private List<Object> extraerVuelosDeVentana(SolucionProgramacion solucion, Instant wStart, Instant wEnd) {
        List<Object> vuelosVentana = new ArrayList<>();
        
        if (solucion == null || solucion.getCargaPorVuelo() == null) {
            return vuelosVentana;
        }
        
        CargaPorVuelo cargaPorVuelo = solucion.getCargaPorVuelo();
        
        // Iterar sobre todos los vuelos asignados
        for (Map.Entry<VueloProgramadoId, Integer> entry : cargaPorVuelo.getAsignado().entrySet()) {
            VueloProgramadoId vueloId = entry.getKey();
            int cantidadAsignada = entry.getValue();
            
            // Solo incluir vuelos que tienen carga asignada
            if (cantidadAsignada > 0) {
                // Verificar si el vuelo cae dentro de la ventana temporal
                // Un vuelo cae en la ventana si su salida está dentro de [wStart, wEnd)
                if (vueloId.getSalidaUtc() != null && 
                    !vueloId.getSalidaUtc().isBefore(wStart) && 
                    vueloId.getSalidaUtc().isBefore(wEnd)) {
                    
                    // Generar ID único para el vuelo
                    String vueloIdStr = vueloId.getOrigen() + "-" + 
                                       vueloId.getDestino() + "-" + 
                                       vueloId.getSalidaUtc().toString().replace(":", "");
                    
                    // Crear DTO del vuelo para el frontend
                    Map<String, Object> vueloDTO = new HashMap<>();
                    vueloDTO.put("id", vueloIdStr);
                    vueloDTO.put("origen", vueloId.getOrigen());
                    vueloDTO.put("destino", vueloId.getDestino());
                    vueloDTO.put("salidaUtc", vueloId.getSalidaUtc().toString());
                    vueloDTO.put("llegadaUtc", vueloId.getLlegadaUtc() != null ? vueloId.getLlegadaUtc().toString() : null);
                    vueloDTO.put("cantidadAsignada", cantidadAsignada);
                    vueloDTO.put("capacidad", cargaPorVuelo.capacidad(vueloId));
                    vueloDTO.put("residual", cargaPorVuelo.residual(vueloId));
                    vueloDTO.put("costo", vueloId.getCosto());
                    
                    // NUEVO: Extraer manifiesto de carga (qué pedidos van en este vuelo)
                    List<Map<String, Object>> carga = extraerCargaDelVuelo(solucion, vueloId);
                    vueloDTO.put("carga", carga);
                    
                    vuelosVentana.add(vueloDTO);
                }
            }
        }
        
        return vuelosVentana;
    }
    
    /**
     * Extrae el manifiesto de carga de un vuelo específico:
     * qué pedidos van en ese vuelo y cuánta cantidad de cada uno
     */
    private List<Map<String, Object>> extraerCargaDelVuelo(SolucionProgramacion solucion, VueloProgramadoId vuelo) {
        List<Map<String, Object>> carga = new ArrayList<>();
        
        if (solucion == null || solucion.getPlanPorPedido() == null) {
            return carga;
        }
        
        // Iterar sobre todos los planes de pedidos
        for (Map.Entry<Integer, PlanPedido> entry : solucion.getPlanPorPedido().entrySet()) {
            PlanPedido plan = entry.getValue();
            
            if (plan == null || plan.getRutas() == null) {
                continue;
            }
            
            // Para cada ruta del pedido
            for (RutaAsignada ruta : plan.getRutas()) {
                if (ruta.getTramos() == null) {
                    continue;
                }
                
                // Buscar si algún tramo de esta ruta usa este vuelo
                for (int i = 0; i < ruta.getTramos().size(); i++) {
                    TramoAsignado tramo = ruta.getTramos().get(i);
                    
                    // Comparar si este tramo corresponde al vuelo actual
                    if (esElMismoVuelo(tramo.getVuelo(), vuelo)) {
                        
                        // Determinar si es una conexión o el destino final
                        boolean esConexion = i < ruta.getTramos().size() - 1;
                        
                        // Crear entrada de carga
                        Map<String, Object> itemCarga = new HashMap<>();
                        itemCarga.put("pedidoId", plan.getIdPedido());
                        itemCarga.put("cantidad", tramo.getCantidad());
                        itemCarga.put("destinoFinal", plan.getAeropuertoDestino());
                        itemCarga.put("esConexion", esConexion);
                        
                        // Información adicional útil para el frontend
                        itemCarga.put("creadoUtc", plan.getCreadoUtc() != null ? plan.getCreadoUtc().toString() : null);
                        
                        carga.add(itemCarga);
                        break; // Un tramo por ruta en este vuelo
                    }
                }
            }
        }
        
        return carga;
    }
    
    /**
     * Compara dos vuelos para ver si son el mismo
     */
    private boolean esElMismoVuelo(VueloProgramadoId v1, VueloProgramadoId v2) {
        if (v1 == null || v2 == null) return false;
        
        // Comparar origen, destino y hora de salida
        return Objects.equals(v1.getOrigen(), v2.getOrigen()) &&
               Objects.equals(v1.getDestino(), v2.getDestino()) &&
               Objects.equals(v1.getSalidaUtc(), v2.getSalidaUtc());
    }
    
    /**
     * Convierte los pedidos a DTOs para el frontend, incluyendo información de orígenes desde la solución
     */
    private List<Object> convertirPedidosADTO(List<Pedido> pedidos, SolucionProgramacion solucion) {
        List<Object> pedidosDTO = new ArrayList<>();
        
        for (Pedido pedido : pedidos) {
            Map<String, Object> pedidoDTO = new HashMap<>();
            pedidoDTO.put("id", pedido.getIdPedido());
            pedidoDTO.put("idCliente", pedido.getIdCliente());
            pedidoDTO.put("destino", pedido.getDestino());
            
            // Extraer orígenes desde la solución (un pedido puede tener múltiples orígenes si se divide)
            List<String> origenes = new ArrayList<>();
            int cantidadAsignada = 0;
            List<Map<String, Object>> rutasDetalle = new ArrayList<>(); // NUEVO: desglose de rutas
            int cantidadTotal = pedido.getCantidad(); // por defecto, usar cantidad del pedido
            
            if (solucion != null) {
                var plan = solucion.planDe(pedido.getIdPedido());
                if (plan != null) {
                    // Si hay un plan, usar la demanda ORIGINAL del PlanPedido
                    cantidadTotal = plan.getDemanda();
                    if (plan.getRutas() != null) {
                        for (var ruta : plan.getRutas()) {
                            if (ruta.getTramos() != null && !ruta.getTramos().isEmpty()) {
                                // El primer tramo de cada ruta contiene el origen (sede)
                                var primerTramo = ruta.getTramos().get(0);
                                String origen = primerTramo.getVuelo().getOrigen();
                                if (origen != null && !origenes.contains(origen)) {
                                    origenes.add(origen);
                                }
                                cantidadAsignada += ruta.getCantidad();
                                
                                // NUEVO: Construir detalle de la ruta
                                Map<String, Object> rutaDetalle = new HashMap<>();
                                rutaDetalle.put("cantidad", ruta.getCantidad());
                                rutaDetalle.put("origen", origen);
                                rutaDetalle.put("destinoFinal", pedido.getDestino());
                                
                                // Extraer vuelos de la ruta
                                List<Map<String, Object>> vuelosRuta = new ArrayList<>();
                                for (var tramo : ruta.getTramos()) {
                                    Map<String, Object> vueloRuta = new HashMap<>();
                                    vueloRuta.put("id", tramo.getVuelo().getOrigen() + "-" + 
                                                  tramo.getVuelo().getDestino() + "-" + 
                                                  tramo.getVuelo().getSalidaUtc().toString().replace(":", ""));
                                    vueloRuta.put("origen", tramo.getVuelo().getOrigen());
                                    vueloRuta.put("destino", tramo.getVuelo().getDestino());
                                    vueloRuta.put("salidaUtc", tramo.getVuelo().getSalidaUtc().toString());
                                    vueloRuta.put("llegadaUtc", tramo.getVuelo().getLlegadaUtc() != null ? 
                                                  tramo.getVuelo().getLlegadaUtc().toString() : null);
                                    vueloRuta.put("cantidad", ruta.getCantidad()); // Misma cantidad para todo el tramo
                                    vuelosRuta.add(vueloRuta);
                                }
                                rutaDetalle.put("vuelos", vuelosRuta);
                                rutasDetalle.add(rutaDetalle);
                            }
                        }
                    }
                }
            }
            
            // Agregar campos faltantes al DTO
            pedidoDTO.put("cantidad", cantidadTotal);
            pedidoDTO.put("fechaCreacion", pedido.getCreatedAtUtc() != null ? pedido.getCreatedAtUtc().toString() : null);
            pedidoDTO.put("fechaLocal", pedido.getFecha() != null ? pedido.getFecha().toString() : null);
            
            // Calcular continenteDestino si no está establecido
            String continenteDestino = pedido.getContinenteDestino();
            if (continenteDestino == null && aeropuertosMap != null) {
                var aeropuerto = aeropuertosMap.obtener(pedido.getDestino());
                if (aeropuerto != null) {
                    continenteDestino = aeropuerto.getContinente();
                }
            }
            pedidoDTO.put("continenteDestino", continenteDestino);
            
            // Si hay un solo origen, ponerlo como string; si hay múltiples, como array
            if (origenes.isEmpty()) {
                pedidoDTO.put("origen", null);
            } else if (origenes.size() == 1) {
                pedidoDTO.put("origen", origenes.get(0));
            } else {
                pedidoDTO.put("origen", origenes); // Múltiples sedes
            }
            
            // Información adicional útil
            pedidoDTO.put("cantidadAsignada", cantidadAsignada);
            pedidoDTO.put("estadoAsignacion", cantidadAsignada >= cantidadTotal ? "COMPLETO" : 
                                              cantidadAsignada > 0 ? "PARCIAL" : "PENDIENTE");
            pedidoDTO.put("rutas", rutasDetalle); // NUEVO: desglose de rutas
            
            pedidosDTO.add(pedidoDTO);
        }
        
        return pedidosDTO;
    }

    /**
     * Obtiene todos los vuelos planificados del día siguiente desde el tiempo actual de simulación.
     * Incluye vuelos planificados incluso si ya despegaron o no, siempre que su salida esté
     * dentro de las próximas 24 horas desde el tiempo actual.
     * 
     * @param runId ID del run para obtener la solución y el tiempo actual
     * @return Lista de vuelos planificados (DTOs) sin límite de cantidad
     */
    public List<Map<String, Object>> getScheduledFlightsNextDay(String runId) {
        List<Map<String, Object>> vuelosPlanificados = new ArrayList<>();
        
        try {
            System.out.println("[RunManager] getScheduledFlightsNextDay - runId: " + runId);
            
            // Obtener el tiempo actual de simulación
            Instant simNow = currentSimNow(runId);
            System.out.println("[RunManager] simNow: " + simNow);
            
            // Calcular el límite del día siguiente (simNow + 24 horas)
            Instant simNowNextDay = simNow.plus(Duration.ofHours(24));
            System.out.println("[RunManager] simNowNextDay: " + simNowNextDay);
            
            // Obtener la solución actual
            SolucionProgramacion solucion = solucionesAnteriores.get(runId);
            if (solucion == null || solucion.getCargaPorVuelo() == null) {
                System.out.println("[RunManager] No hay solución para runId: " + runId);
                return vuelosPlanificados; // Retornar lista vacía si no hay solución
            }
            
            CargaPorVuelo cargaPorVuelo = solucion.getCargaPorVuelo();
            System.out.println("[RunManager] Total vuelos en solución: " + cargaPorVuelo.getAsignado().size());
            
            // Iterar sobre todos los vuelos planificados (incluso sin carga asignada para mostrar todos)
            // Pero para simplificar, solo incluimos vuelos con carga asignada (vuelos realmente usados)
            for (Map.Entry<VueloProgramadoId, Integer> entry : cargaPorVuelo.getAsignado().entrySet()) {
                VueloProgramadoId vueloId = entry.getKey();
                
                // Incluir vuelos planificados del día siguiente
                // Solo incluimos vuelos cuya salida está en el futuro desde simNow hasta simNow + 24 horas
                // Esto permite cancelar vuelos antes de que despeguen
                // Nota: Si un vuelo ya despegó (salida < simNow), no tiene sentido mostrarlo para cancelarlo
                if (vueloId.getSalidaUtc() != null) {
                    Instant salida = vueloId.getSalidaUtc();
                    
                    // Incluir vuelos planificados del día siguiente (desde simNow hasta simNow + 24 horas)
                    // Solo vuelos futuros que aún no han despegado
                    // IMPORTANTE: Solo incluir vuelos cuya salida está en el futuro (>= simNow)
                    // y dentro de las próximas 24 horas (<= simNow + 24h)
                    if (!salida.isBefore(simNow) && !salida.isAfter(simNowNextDay)) {
                        // Generar ID único para el vuelo
                        String vueloIdStr = vueloId.getOrigen() + "-" + 
                                           vueloId.getDestino() + "-" + 
                                           vueloId.getSalidaUtc().toString().replace(":", "");
                        
                        // Crear DTO del vuelo
                        Map<String, Object> vueloDTO = new HashMap<>();
                        vueloDTO.put("id", vueloIdStr);
                        vueloDTO.put("origen", vueloId.getOrigen());
                        vueloDTO.put("destino", vueloId.getDestino());
                        vueloDTO.put("salidaUtc", vueloId.getSalidaUtc().toString());
                        vueloDTO.put("llegadaUtc", vueloId.getLlegadaUtc() != null ? vueloId.getLlegadaUtc().toString() : null);
                        vueloDTO.put("cantidadAsignada", entry.getValue());
                        vueloDTO.put("capacidad", cargaPorVuelo.capacidad(vueloId));
                        vueloDTO.put("residual", cargaPorVuelo.residual(vueloId));
                        vueloDTO.put("costo", vueloId.getCosto());
                        
                        // Extraer manifiesto de carga (qué pedidos van en este vuelo)
                        List<Map<String, Object>> carga = extraerCargaDelVuelo(solucion, vueloId);
                        vueloDTO.put("carga", carga);
                        
                        vuelosPlanificados.add(vueloDTO);
                    }
                }
            }
            
            System.out.println("[RunManager] Vuelos planificados del día siguiente encontrados: " + vuelosPlanificados.size());
            
            // Ordenar por hora de salida
            vuelosPlanificados.sort((a, b) -> {
                String salidaA = (String) a.get("salidaUtc");
                String salidaB = (String) b.get("salidaUtc");
                if (salidaA == null || salidaB == null) return 0;
                return salidaA.compareTo(salidaB);
            });
            
        } catch (Exception e) {
            System.err.println("[RunManager] Error obteniendo vuelos planificados del día siguiente para runId " + runId + ": " + e.getMessage());
            e.printStackTrace();
        }
        
        return vuelosPlanificados;
    }

}
