package pe.edu.pucp.morapack.airscheduler.engine.scheduling.run;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

    //
    public List<Pedido> drainOrders(String runId) {
        var q = queues.getOrDefault(runId, new ConcurrentLinkedQueue<>());
        var list = new ArrayList<Pedido>();
        for (Pedido x; (x = q.poll()) != null; ) list.add(x);
        return list;
    }

    //Con esto estamos creando el run si no existe. Si ya existe, lo devolvemos:
    public String ensureOperacionStarted(){
        String existing = operacionRunId.get();
        if (existing != null) return existing;

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

    }


    /// //////////////////////////////////////////////
    /// Todo0 lo que había sobre las planificaciones:
    /// //////////////////////////////////////////////

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
    private synchronized void inicializarCatalogos(RunConfig.Scenario scenario) {
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
            
            // Cargar pedidos (solo fuera de OPERACION)
            pedidosCargados = new CargarPedidos();

            if (scenario != RunConfig.Scenario.OPERACION){
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
            }

            
            // Definir sedes
            sedes = new HashSet<>(Arrays.asList("SPIM", "EBCI", "UBBB"));
            
            System.out.println("[RunManager] Catálogos inicializados correctamente");
        }
    }

    /**
     * Inicializa los catálogos compartidos PARA OPERACIÓN DIARIA si no están cargados
     */


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

    private void sleepToEndWindow(String id, Instant wEnd){
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

        while (!cancelled.get(id).get() && (config.fechaFin() == null || !wStart.isAfter(config.fechaFin()))) {
            /// Revisar esto:
            // Pausa cooperativa entre ventanas
            while (paused.get(id).get() && !cancelled.get(id).get()) {
                sleepQuietly(Duration.ofMillis(80));
            }
            if (cancelled.get(id).get()) break;

            // ===== LÓGICA DE PLANIFICACIÓN POR VENTANAS =====

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
                    pedidosCargados.eliminarYActualizarCumplidosHasta(wStart, solucionAnterior);
                    System.out.println("[RunManager] Después de eliminarYActualizarCumplidosHasta: " + 
                        pedidosCargados.getLista().size() + " pedidos en cola");
                    enVuelo = EstadoAnteriorExtractor.construirArribosEnVuelo(solucionAnterior, wStart);
                    reservas = EstadoAnteriorExtractor.reservasDesdeSolucionAnterior(solucionAnterior, wStart, Duration.ofHours(2));
                }

                // 2. Obtener pedidos de la ventana actual (ya actualizados)
                VentanaPedidos ventana = pedidosCargados.acumuladoHasta(wEnd);
                List<Pedido> pedidosVentana = ventana.pedidos();

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
                    wEnd = wEnd.plus(config.horasVentana());
                    continue;
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
                reparadores.add(new SplitRepair(new ArrayList<>(sedes), teg));

                ALNS alns = new ALNS(teg, pedidosVentana, destructores, reparadores, wStart, ocupacionPorAeropuerto);
                SolucionProgramacion solucionOptima = alns.ejecutar(seed);

                // 6. Guardar solución para la siguiente ventana
                solucionesAnteriores.put(id, solucionOptima);

                // 7. Extraer vuelos y pedidos de la ventana actual para broadcasting
                final Instant wStartFinal = wStart;
                final Instant wEndFinal = wEnd;
                
                List<Object> vuelosVentana = extraerVuelosDeVentana(solucionOptima, wStartFinal, wEndFinal);
                
                // IMPORTANTE: Enviar TODOS los pedidos procesados (incluye parciales de ventanas anteriores)
                // para que el frontend vea el estado actualizado de cada pedido
                List<Object> pedidosVentanaDTO = convertirPedidosADTO(pedidosVentana, solucionOptima);

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
            sleepToEndWindow(id, wEnd);

            // Siguiente ventana
            idx++;
            wStart = wEnd;
            wEnd   = wEnd.plus(config.horasVentana());
        }


    }

    /// 2. Run de Operación Diaria
    private void runOperacion(RunId runId, RunConfig config){
        final String id = runId.value();

        Instant wStart = config.fechaInicio();

        /// Nota: Dado que ahorita solo enviamos horasVentana (osea, horas), estoy comentando esto.
        /// Tenemos que hacer cambios para que soporte por minutos (no en el algoritmo, creo que ahí no,
        /// sino en RunConfig (línea 59 en dicho archivo))
        //Instant wEnd = wStart.plus(config.horasVentana());
        Instant wEnd = wStart.plus(Duration.ofMinutes(1));

        int idx = 0;

        System.out.println("En esta iteración, wStart es: " + wStart + ", wEnd es: " + wEnd);
        System.out.println("Voy a entrar al bucle, mi id es:" + id);

        while (!cancelled.get(id).get() && (config.fechaFin() == null || !wStart.isAfter(config.fechaFin()))){
            while (paused.get(id).get() && !cancelled.get(id).get()) {
                sleepQuietly(Duration.ofMillis(80));
            }
            if (cancelled.get(id).get()) break;

            /// 1. Inicializar catálogos (vuelos + aeropuertos ONLY)
            inicializarCatalogos(config.scenario());

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
                    pedidosCargados.eliminarYActualizarCumplidosHasta(wStart, solucionAnterior);
                    System.out.println("[RunManager] Después de eliminarYActualizarCumplidosHasta: " +
                            pedidosCargados.getLista().size() + " pedidos en cola");
                    enVuelo = EstadoAnteriorExtractor.construirArribosEnVuelo(solucionAnterior, wStart);
                    reservas = EstadoAnteriorExtractor.reservasDesdeSolucionAnterior(solucionAnterior, wStart, Duration.ofHours(2));
                }

                ///  2. Obtener pedidos de la ventana actual

                    /// Primero, cargamos de queue a pedidosCargados y limpiamos queue
                    cargarPedidosDesdeQueue(id, pedidosCargados);

                    /// Si no hay nada en la ventana, duerme
                    // sleepToEndWindow(id, wEnd);

                /// 3. Construimos TEG

                /// 4. Generamos la solución inicial (seed)

                /// 5. Ejecutamos ALNS

                /// 6. Guardar solución para la siguiente ventana

                /// 7. Extraer vuelos y pedidos de la ventana actual para broadcasting

                /// 8. Marcar ventana como enviada y hacer broadcast

                System.out.println("Esto es operación diaria y estoy dentro del bucle. No hago nada más. El " +
                        "tiempo actual es:" + currentSimNow(id));
            }
            catch (Exception e){
                e.printStackTrace();
            }


            //Llamamos al sleep (para que el reloj simulado cruce fin de ventana):
            sleepToEndWindow(id, wEnd);

            // Siguiente ventana
            idx++;
            wStart = wEnd;
            wEnd   = wEnd.plus(Duration.ofMinutes(1));
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

}
