package pe.edu.pucp.morapack.airscheduler.engine.scheduling.run;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import pe.edu.pucp.morapack.airscheduler.api.service.ReportesService;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.ArchivoManager;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.CargarPedidos;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.CargarPedidos.VentanaPedidos;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.ImpresorSolucion;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.LectorPedidoMultiArchivo;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.*;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.teg.TEGEventBuilder;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.teg.helpers.TEGParametros;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Aeropuerto;
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
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.service.VerificadorSLA;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.ssp.SSPGeneradorSeed;

@ApplicationScoped
public class RunManager {

    @Inject
    ArchivoManager archivoManager;
    private static final String AEROPUERTOS_FILENAME = "aereopuertos.txt";
    private static final String VUELOS_FILENAME = "vuelos.txt";
    //private static final String PEDIDOS_FILENAME = "pedidos.txt";
    private static boolean firstExecution = false;

    private static final String VUELOS_CANCELADOS_FILENAME = "cancelaciones.txt";

    private static final Duration PLANNING_LATENCY = Duration.ofSeconds(30);
    // DIARIO MAESTRO: Mapea ID_PEDIDO -> Su Último Plan Confirmado.
    // Esto evita duplicar carga si el algoritmo re-planifica el mismo pedido.
    private final Map<String, Map<Integer, PlanPedido>> masterPlanPorRun = new ConcurrentHashMap<>();

    @Inject
    ReportesService reportesService;

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

    //Almacena el ID del run activo, para reconexiones
    private final AtomicReference<String> activeRunId = new AtomicReference<>(null);

    // Guarda la ÚLTIMA ventana emitida por cada run (para rehidratación)
    private final Map<String, WindowPacket> lastWindows = new ConcurrentHashMap<>();

    // Para validar si se violó un SLA o no
    private final ConcurrentHashMap<String, AtomicBoolean> slaBroken = new ConcurrentHashMap<>();

    public WindowPacket getLastWindow(String runId) { return lastWindows.get(runId); }

    public String currentOperacionRunId(){ return operacionRunId.get(); }
    public boolean hasActiveOperacionRunId(){ return operacionRunId.get() != null; }

    public String currentActiveRunId(){ return activeRunId.get(); }
    public void setActiveRunIdRunId(String runId) { activeRunId.set(runId); }

    private SolucionProgramacion solucionGlobal = null;

    /// ///////////////////////////////////////////
    /// ///////////////////////////////////////////
    /// RETORNAR A ESTE PUNTO


    /**
     * Obtiene el último run activo (con estado RUNNING) de cualquier tipo.
     * Útil para obtener vuelos programados cuando no hay run de operación activo.
     * * @return ID del último run activo, o null si no hay ninguno
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

    //Para OD y poder forzar replanificación:
    private final Map<String, AtomicBoolean> forcePlanPorRun  = new ConcurrentHashMap<>();

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
                //Limpiamos los reportes previos
                reportesService.limpiarReportesPrevios();

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

        /// Acá obtenemos el directorio base según el entorno (para aclarar), al que luego le agregamos /archivosPedidos:
        /// local: src/main/resources
        /// prod:  uploads

        Path baseUploadDir = Paths.get(archivoManager.getUploadDir());

        /// Subcarpeta específica para los archivos de pedidos (/archivosPedidos)
        Path baseDir = baseUploadDir.resolve("archivosPedidos");

        System.out.println("[RunManager] Buscando archivos de pedidos en: " + baseDir.toAbsolutePath());

        for (String codigo: CODIGOS) {
            String fileName = "_pedidos_" + codigo + "_.txt";
            Path path = baseDir.resolve(fileName);

            if (Files.exists(path)) {
                paths.add(path);
            } else {
                System.err.println("[RunManager] Advertencia: no se encontró archivo de pedidos para código "
                        + codigo + " en " + path.toAbsolutePath());
            }
        }

        if (paths.isEmpty()) {
            System.err.println("[RunManager] No se encontró ningún archivo de pedidos en "
                    + baseDir.toAbsolutePath()
                    + " (¿ya se subieron los 27 archivos?)");
        } else {
            System.out.println("[RunManager] Archivos de pedidos encontrados: " + paths.size());
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

    ///Exclusivo para OD:
    private LocalDateTime currentSimNowLocal(String runId){
        Instant now = currentSimNow(runId);

        /// Llevamos a la hora de Perú
        ZoneId zonaPeru = ZoneId.of("America/Lima");

        return LocalDateTime.ofInstant(now, zonaPeru);

    }

    ///Exclusivo para OD:
    public void normalizarFechasOD(String runId, List<Pedido> pedidos){
        LocalDateTime fechaOD = currentSimNowLocal(runId);

        System.out.println("[RunManager] Fecha de OD: " + fechaOD);

        if (fechaOD == null || pedidos == null) return;

        /// Esto ancla TODA la fecha (como en crearPedido de PedidosController)
        for (Pedido p : pedidos){
            if (p == null) continue;
            LocalDateTime fecha = ajustarFechaPedidoPorDestino(fechaOD, p.getDestino());
            p.setFecha(fecha);
        }

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

    // ---- Listener (para SSE) --- MODIFICADO para soportar Loading
    public interface RunListener {
        void onWindow(WindowPacket packet);
        void onFinished(String runId, StopReason reason);
        void onLoading(String runId, String message); // NUEVO
    }
    
    public static final RunListener NOOP_LISTENER = new RunListener() {
        @Override public void onWindow(WindowPacket packet) {}
        @Override public void onFinished(String runId, StopReason reason) {}
        @Override public void onLoading(String runId, String message) {} // NUEVO
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


    public void start(RunId runId, RunConfig config, RunListener listener) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(listener, "listener");

        final String id = runId.value();
        
        // 1. Registrar Listeners
        listeners.computeIfAbsent(id, k -> new java.util.concurrent.CopyOnWriteArraySet<>());
        if (listener != null && listener != NOOP_LISTENER) {
            registerListener(runId, listener);
        }

        // 2. Estado Inicial: LOADING (Cargando archivos...)
        states.put(id, RunState.LOADING);
        
        // 3. Inicializar Flags de Control
        paused.put(id, new AtomicBoolean(false));
        cancelled.put(id, new AtomicBoolean(false));
        forcePlanPorRun.put(id, new AtomicBoolean(false));

        // Flag para detectar ruptura de SLA (Colapso)
        final AtomicBoolean slaFlag = slaBroken.computeIfAbsent(id, k -> new AtomicBoolean(false));
        slaFlag.set(false);

        // 4. Ejecución Asíncrona
        executor.submit(() -> {
            try {
                // Delegar al método específico según escenario
                switch (config.scenario()) {
                    case OPERACION -> runOperacion(runId, config);
                    case SIM_SEMANAL, COLAPSO -> runSimulacion(runId, config);
                    default -> throw new IllegalStateException("Unexpected value: " + config.scenario());
                }

                // --- Lógica Post-Ejecución (Cuando el bucle while termina) ---

                System.out.println("Salí del bucle de simulación, runId: " + id);

                // Determinar la razón de finalización
                if (slaFlag.get()) {
                    // Terminó por Colapso Logístico
                    states.put(id, RunState.COMPLETED);
                    broadcastFinished(id, StopReason.COLAPSO);
                } 
                else if (cancelled.get(id).get()) {
                    // Terminó por Cancelación Manual del Usuario
                    states.put(id, RunState.COMPLETED); // O STOPPED, según prefieras
                    System.out.println("Emitiendo StopReason.MANUAL para " + id);
                    broadcastFinished(id, StopReason.MANUAL);
                } 
                else {
                    // Terminó Naturalmente (Fin del horizonte de tiempo)
                    states.put(id, RunState.COMPLETED);
                    broadcastFinished(id, StopReason.FIN_DE_RANGO);
                }

                // Limpieza de referencias pesadas (GC friendly)
                aeropuertosMap = null;
                vuelosMap = null;
                pedidosCargados = null;

            } catch (Throwable e) {
                // Manejo de Errores no controlados
                System.err.println("Error fatal en el run " + id + ": " + e.getMessage());
                e.printStackTrace();
                states.put(id, RunState.FAILED);
                broadcastFinished(id, StopReason.ERROR);
            } finally {
                // Limpieza final del estado en el RunManager
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
        forcePlanPorRun.remove(id);

        // estructuras por run:
        ventanasEnviadas.remove(id);
        solucionesAnteriores.remove(id);
        ocupacionesPorRun.remove(id);
        lectorArchivoPorRun.remove(id);
        //queues.remove(id);          // si existe

        activeRunId.set(null);

        //Nuevo:
        operacionRunId.set(null);

        slaBroken.remove(id);
        masterPlanPorRun.remove(id);

        solucionGlobal = null;
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

            //a

            if (isCancelled(id)) return true;
        }
        return false;
    }

    private boolean sleepToEndWindowOD(String id, Instant wEnd){
        //Para revisar solicitud de planificación forzada
        AtomicBoolean forced = forcePlanPorRun.get(id);

        //Acá vamos a que el reloj simulado cruce el fin de ventana
        while (true){
            //En caso de existir pausa o cancelación (por ahora, esto no ocurrirá)
            if (isCancelled(id)) return true;

            while (paused.get(id).get() && !cancelled.get(id).get()) {
                sleepQuietly(Duration.ofMillis(100)); // dormimos cortito mientras esté pausado
            }
            if (isCancelled(id)) return true;

            /// Si se solicitó un replan:
            if (forced != null && forced.get()) {
                break;
            }

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

    /// 1. Run de Simulación (CON LOOKAHEAD & LOADING STATE)
    private void runSimulacion(RunId runId, RunConfig config) {
        System.out.println("Estamos en SIM SEMANAL (Con Lookahead & Loading State)");

        final String id = runId.value();
        final AtomicBoolean slaFlag = slaBroken.computeIfAbsent(id, k -> new AtomicBoolean(false));
        slaFlag.set(false);

        // 1. Notificar inicio de carga
        broadcastLoading(id, "Preparando simulación...");

        // 2. Calcular Buffer de Anticipación
        Duration simulatedBuffer = PLANNING_LATENCY.multipliedBy((long) config.speed());
        System.out.println("[RunManager] Buffer de anticipación calculado: " + simulatedBuffer);

        Instant wStart = config.fechaInicio();
        
        // wEmit: Fin de la ventana, momento en que el usuario debe recibir el resultado
        Instant wEmit = wStart.plus(config.horasVentana()); 

        int idx = 0;
        Instant lastWStart = wStart;

        System.out.println("[RunManager] Config: fechaInicio=" + config.fechaInicio() + ", fechaFin=" + config.fechaFin());
        System.out.println("[RunManager] Primera ventana de emisión: " + wEmit);

        // =================================================================================
        // 🛑 FASE DE CARGA PESADA (LOADING)
        // =================================================================================
        System.out.println("[RunManager] Estado LOADING: Inicializando lectores y catálogos...");
        
        long tLoadStart = System.currentTimeMillis();

        // Notificar al usuario que estamos leyendo archivos grandes
        broadcastLoading(id, "Procesando archivos de pedidos...");
        inicializarLectorMultiArchivo(id, wStart); 
        
        broadcastLoading(id, "Cargando catálogos de vuelos y aeropuertos...");
        inicializarCatalogos(config.scenario());

        long tLoadEnd = System.currentTimeMillis();
        System.out.println("[RunManager] Carga completada en " + (tLoadEnd - tLoadStart) + "ms.");

        // =================================================================================
        // 🚀 TRANSICIÓN A RUNNING
        // =================================================================================
        if (isCancelled(id)) return; // Si cancelaron mientras cargaba

        states.put(id, RunState.RUNNING);
        
        // =================================================================================

        List<VueloCancelado> vuelosCanceladosTeg = new ArrayList<>();

        while (!isCancelled(id) && (config.fechaFin() == null || !wStart.isAfter(config.fechaFin()))) {
            
            // Pausa cooperativa
            while (paused.get(id).get() && !isCancelled(id)) {
                sleepQuietly(Duration.ofMillis(80));
            }
            if (isCancelled(id)) break;

            if (firstExecution){ System.out.println("Soy true"); }

            // Verificar idempotencia (ventanas ya enviadas)
            String windowIdISO = wStart.toString();
            Set<String> ventanasEnviadasRun = ventanasEnviadas.computeIfAbsent(id, k -> new HashSet<>());
            if (ventanasEnviadasRun.contains(windowIdISO)) {
                System.out.println("[RunManager] Ventana ya enviada, saltando: " + windowIdISO);
                idx++;
                wStart = wEmit;
                wEmit = wEmit.plus(config.horasVentana());
                continue;
            }

            // ===== LÓGICA DE LOOKAHEAD =====
            
            // wCut: Tiempo de corte para los datos de entrada.
            // "Engañamos" al algoritmo dándole datos solo hasta (TiempoEmisión - Buffer).
            Instant wCut = wEmit.minus(simulatedBuffer);
            
            // Seguridad: No cortar antes del inicio absoluto
            if (wCut.isBefore(wStart)) wCut = wStart;

            System.out.println("[RunManager] Procesando ventana " + idx + 
                               " | T_Inicio: " + wStart + 
                               " | T_Corte (Input): " + wCut + 
                               " | T_Emision (Output): " + wEmit);

            try {
                // 1. Preparar estado anterior
                SolucionProgramacion solucionAnterior = solucionesAnteriores.get(id);
                Map<String, List<ArriboExogeno>> enVuelo = Map.of();
                List<OcupacionAlmacen> reservas = List.of();

                if (solucionAnterior != null) {
                    Set<VueloProgramadoId> vuelosCancelados = vuelosCanceladosPorRun.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet());
                    if (!vuelosCancelados.isEmpty()) {
                        List <VueloCancelado> vuelosCancelString = transformar(vuelosCancelados);
                        vuelosCanceladosTeg.addAll(vuelosCancelString);
                        procesarCancelaciones(id, vuelosCancelados, solucionAnterior);
                        vuelosCanceladosPorRun.get(id).clear();
                    }
                    
                    // Actualizar pedidos hasta wCut (no hasta wEmit)
                    pedidosCargados.eliminarYActualizarCumplidosHasta(wStart, solucionAnterior);
                    enVuelo = EstadoAnteriorExtractor.construirArribosEnVuelo(solucionAnterior, wStart);
                    reservas = EstadoAnteriorExtractor.reservasDesdeSolucionAnterior(solucionAnterior, wStart, Duration.ofHours(2));
                }

                // 2. Obtener pedidos hasta el tiempo de CORTE (wCut)
                cargarPedidosDesdeLector(id, pedidosCargados, wCut); // 🛑 Leer hasta wCut
                VentanaPedidos ventana = pedidosCargados.acumuladoHasta(wCut); // 🛑 Acumular hasta wCut
                List<Pedido> pedidosVentana = ventana.pedidos();

                System.out.println("[RunManager] Pedidos para planificar (hasta " + wCut + "): " + pedidosVentana.size());

                // Si no hay pedidos, igual esperamos a wEmit para mantener el ritmo
                if (pedidosVentana.isEmpty()) {
                    System.out.println("[RunManager] Ventana vacía (sin pedidos nuevos hasta " + wCut + ")");
                    
                    // Esperar sincronización con wEmit
                    if (sleepToEndWindow(id, wEmit)) break;

                    ventanasEnviadasRun.add(windowIdISO);
                    broadcastWindow(new WindowPacket(id, idx, wStart, wEmit, List.of(), convertirPedidosADTO(List.of(), null)));

                    idx++;
                    wStart = wEmit;
                    wEmit = wEmit.plus(config.horasVentana());
                    pedidosCargados.setUtcNormalizada(false);
                    continue;
                }

                if (isCancelled(id)) break;

                // Cargar cancelaciones hasta wCut
                List<VueloCancelado> vuelosCanceladosArch = cancelados.obtenerVuelosCancelados(wStart, wCut);
                vuelosCanceladosTeg.addAll(vuelosCanceladosArch);

                // 3. Construir TEG (Horizonte proyectado desde wEmit para consistencia futura)
                Instant finTEG = wEmit.plus(config.horizon());
                TEGParametros params = TEGParametros.builder()
                        .inicioUtc(wStart)
                        .finUtc(finTEG)
                        .sedes(sedes)
                        .arribosLibres(enVuelo)
                        .reservasWaitIniciales(reservas)
                        .vuelosCancelados(vuelosCanceladosTeg)
                        .build();

                VuelosTEG teg = new TEGEventBuilder(aeropuertosMap, vuelosMap).construir(params);

                // 4. Generar Seed y 5. Ejecutar ALNS (Tarda ~30s reales)
                OcupacionPorAeropuerto ocupacionPorAeropuerto = ocupacionesPorRun.computeIfAbsent(id, k -> new OcupacionPorAeropuerto(aeropuertosMap));
                SSPGeneradorSeed ssp = new SSPGeneradorSeed(sedes, Map.of(), ocupacionPorAeropuerto);
                SolucionProgramacion seed = ssp.generarSeed(teg, pedidosVentana, wStart);

                List<DestructionOperator> destructores = new ArrayList<>();
                destructores.add(new RandomRemoval(30));
                destructores.add(new WorstRemoval(15));
                destructores.add(new WarehouseCrisisRemoval(15,aeropuertosMap));
                destructores.add(new SlaBreachRemoval(10));
                List<RepairOperator> reparadores = new ArrayList<>();
                //reparadores.add(new RegretRepair(2, new ArrayList<>(sedes), teg));
                reparadores.add(new SplitRepair(new ArrayList<>(sedes), teg));
                reparadores.add(new Regret2RepairFast(new ArrayList<>(sedes), teg));
                reparadores.add(new UrgencySplitRepair(new ArrayList<>(sedes), teg));

                ALNS alns = new ALNS(teg, pedidosVentana, destructores, reparadores, wStart, ocupacionPorAeropuerto,aeropuertosMap);
                SolucionProgramacion solucionOptima = alns.ejecutar(seed);

                if (isCancelled(id)) break;

                // 6. Guardar solución
                actualizarOcupacionDesdeSolucion(id, solucionOptima, solucionAnterior, reservas, enVuelo, wStart);
                solucionesAnteriores.put(id, solucionOptima);

                boolean SlaOk = VerificadorSLA.assertBasicos(solucionOptima, Duration.ofHours(46), vuelosMap, aeropuertosMap);
                if (!SlaOk){
                    slaFlag.set(true);
                    break;
                }

                // 🛑 SINCRONIZACIÓN FINAL
                // El algoritmo terminó (usando datos del pasado). Ahora esperamos a que el reloj simulado
                // alcance el momento de emisión (wEmit).
                if (sleepToEndWindow(id, wEmit)) break;

                // 7. Extraer y Emitir
                // Ahora es wEmit. Emitimos los resultados.
                final Instant wStartFinal = wStart;
                final Instant wEndFinal = wEmit;
                
                // En operación diaria, incluir vuelos que salen dentro del horizonte completo (24 horas)
                // porque las ventanas son de 1 minuto pero los vuelos se planifican hasta 24h adelante
                final Instant finExtraccion = config.scenario() == RunConfig.Scenario.OPERACION 
                    ? wStart.plus(config.horizon())  // wStart + 24 horas
                    : wEndFinal;                      // Para otros escenarios, usar wEnd normal

                List<Object> vuelosVentana = extraerVuelosDeVentana(solucionOptima, wStartFinal, finExtraccion);

                // IMPORTANTE: Enviar TODOS los pedidos procesados (incluye parciales de ventanas anteriores)
                // para que el frontend vea el estado actualizado de cada pedido
                List<Object> pedidosVentanaDTO = convertirPedidosADTO(pedidosVentana, solucionOptima);

                ventanasEnviadasRun.add(windowIdISO);
                broadcastWindow(new WindowPacket(id, idx, wStart, wEmit, vuelosVentana, pedidosVentanaDTO));

                // 9. Reportes
                Path reportePath = reportesService.getReportesFilePath();
                ImpresorSolucion.imprimirEnArchivo(solucionOptima, reportePath.toString(), wStart);

                lastWStart = wStart;
                System.out.println("[RunManager] Ventana " + idx + " procesada y emitida en " + currentSimNow(id));

            } catch (Exception e) {
                System.err.println("[RunManager] Error procesando ventana " + idx + ": " + e.getMessage());
                e.printStackTrace();
            }

            // Siguiente ventana
            idx++;
            wStart = wEmit; // El nuevo inicio es el fin de la actual
            wEmit = wEmit.plus(config.horasVentana()); // El nuevo fin avanza una ventana
            pedidosCargados.setUtcNormalizada(false);
        }

        // Reporte final
        SolucionProgramacion ultimaPlan = solucionesAnteriores.get(id);
        if (ultimaPlan != null) {
            System.out.println("[RunManager]: Imprimiendo última planificación.");
            Path reportePath = reportesService.getLastPlanFilePath();
            ImpresorSolucion.imprimirUltimaPlanificacion(ultimaPlan, reportePath.toString(), lastWStart);
        }
    }
    /// 2. Run de Operación Diaria
    private void runOperacion(RunId runId, RunConfig config){
        final String id = runId.value();

        Instant wStart = config.fechaInicio();

        /// Nota: Dado que ahorita solo enviamos horasVentana (osea, horas), estoy comentando esto.
        /// Tenemos que hacer cambios para que soporte por minutos (no en el algoritmo, creo que ahí no,
        /// sino en RunConfig (línea 59 en dicho archivo))

        Duration minutosVentana = Duration.ofMinutes(3);

        //Instant wEnd = wStart.plus(config.horasVentana());
        Instant wEnd = wStart.plus(minutosVentana);

        int idx = 0;

        System.out.println("Estamos dentro de runOperacion");

        System.out.println("En esta iteración, wStart es: " + wStart + ", wEnd es: " + wEnd);
        System.out.println("Voy a entrar al bucle, mi id es:" + id);
        List<VueloCancelado> vuelosCanceladosTeg = new ArrayList<>();

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
                        List <VueloCancelado> vuelosCancelString = transformar(vuelosCancelados);
                        vuelosCanceladosTeg.addAll(vuelosCancelString);
                        procesarCancelaciones(id, vuelosCancelados, solucionAnterior);

                        /// Dejamos el set vacío (por ahora):
                        vuelosCanceladosPorRun.get(id).clear();
                    }

                    pedidosCargados.eliminarYActualizarCumplidosHasta(wStart, solucionAnterior, true);
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
                        sleepToEndWindowOD(id, wEnd);
                        wEnd = updateWindowEnd(id, wEnd);

                        // Avanzar a la siguiente ventana antes de continuar
                        idx++;
                        wStart = wEnd;

                        wEnd = wEnd.plus(minutosVentana);


                        /// 3. Construimos TEG                wEnd = wEnd.plus(config.horasVentana());

                        //Como se ha diseñado para que lea todo0 de un archivo, tenemos que hacer esto para que funcione por ventana
                        pedidosCargados.setUtcNormalizada(false);
                        continue;
                    }


                List<VueloCancelado> vuelosCanceladosArch = cancelados.obtenerVuelosCancelados(wStart,wEnd);
                vuelosCanceladosTeg.addAll(vuelosCanceladosArch);

                if(!vuelosCanceladosTeg.isEmpty()){
                    System.out.println("[RunManager] Vuelos cancelados en la ventana " + idx + ": " + vuelosCanceladosTeg);
                    //teg.cancelarVuelos(vuelosCanceladosTeg);
                }


                Instant finTEG = wEnd.plus(config.horizon());
                TEGParametros params = TEGParametros.builder()
                        .inicioUtc(wStart)
                        .finUtc(finTEG)
                        .sedes(sedes)
                        .arribosLibres(enVuelo)
                        .reservasWaitIniciales(reservas)
                        .vuelosCancelados(vuelosCanceladosTeg)
                        .build();

                VuelosTEG teg = new TEGEventBuilder(aeropuertosMap, vuelosMap).construir(params);
//Eliminar Vuelos
                /// 4. Generamos la solución inicial (seed)
                OcupacionPorAeropuerto ocupacionPorAeropuerto = ocupacionesPorRun.computeIfAbsent(id, k -> new OcupacionPorAeropuerto(aeropuertosMap));
                SSPGeneradorSeed ssp = new SSPGeneradorSeed(sedes, Map.of(), ocupacionPorAeropuerto);
                SolucionProgramacion seed = ssp.generarSeed(teg, pedidosVentana, wStart);

                /// 5. Ejecutamos ALNS
                List<DestructionOperator> destructores = new ArrayList<>();
                destructores.add(new RandomRemoval(30));
                destructores.add(new WorstRemoval(15));
                destructores.add(new WarehouseCrisisRemoval(15,aeropuertosMap));
                destructores.add(new SlaBreachRemoval(10));
                List<RepairOperator> reparadores = new ArrayList<>();
                //reparadores.add(new RegretRepair(2, new ArrayList<>(sedes), teg));
                reparadores.add(new SplitRepair(new ArrayList<>(sedes), teg));
                reparadores.add(new Regret2RepairFast(new ArrayList<>(sedes), teg));
                reparadores.add(new UrgencySplitRepair(new ArrayList<>(sedes), teg));

                ALNS alns = new ALNS(teg, pedidosVentana, destructores, reparadores, wStart, ocupacionPorAeropuerto,aeropuertosMap);
                SolucionProgramacion solucionOptima = alns.ejecutar(seed);

                //
                mergeSolucion(solucionOptima);

                /// 6. Guardar solución para la siguiente ventana y sincronizar ocupación
                actualizarOcupacionDesdeSolucion(id, solucionOptima, solucionAnterior, reservas, enVuelo, wStart);
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
                List<Pedido> pedidosUI = pedidosCargados.historicoHasta(wEnd);
                List<Object> pedidosVentanaDTO = convertirPedidosADTO(pedidosUI, solucionGlobal);

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
            sleepToEndWindowOD(id, wEnd);
            wEnd = updateWindowEnd(id, wEnd);

            // Siguiente ventana
            idx++;
            wStart = wEnd;
            wEnd   = wEnd.plus(minutosVentana);
            //Como se ha diseñado para que lea todo0 de un archivo, tenemos que hacer esto para que funcione por ventana
            pedidosCargados.setUtcNormalizada(false);

        }
    }

    private void mergeSolucion(
            SolucionProgramacion ventana
    ) {
        //Si es la primera ventana:
        if (solucionGlobal == null){
            solucionGlobal = new SolucionProgramacion(ventana);
            return;
        }

        //Si no, continúa

        if (ventana == null) return;

        // 1️⃣ Merge de planes por pedido
        for (var entry : ventana.getPlanPorPedido().entrySet()) {
            Integer pedidoId = entry.getKey();
            PlanPedido planVentana = entry.getValue();

            // Si el pedido no existía, lo agregamos
            solucionGlobal.getPlanPorPedido().put(
                    pedidoId,
                    new PlanPedido(planVentana)
            );
        }
    }

    public LocalDateTime ajustarFechaPedidoPorDestino(LocalDateTime fechaPeru, String codDes) {

        Aeropuerto origen = aeropuertosMap.obtener("SPIM");
        Aeropuerto destino = aeropuertosMap.obtener(codDes);

        if (origen == null || destino == null) {
            System.err.println("[RunManager]: No se encontró información de SPIM o" + codDes);
            return fechaPeru;
        }

        /// Hacemos la conversión para tener la hora en la que fue creado el pedido, pero del destino
        int gmtOrigen = origen.getGMT();
        int gmtDestino = destino.getGMT();

        /// Obtenemos diferencia horaria
        int diff = gmtDestino - gmtOrigen;

        LocalDateTime fechaDestino = fechaPeru.plus(diff, ChronoUnit.HOURS);

        System.out.println("[RunManager] Ajustando fecha pedido: origen GMT " + gmtOrigen + ", destino GMT " +
                gmtDestino + ", diff = " + diff +  "h => " + fechaPeru + " -> " + fechaDestino);

        return fechaDestino;
    }

    public void setForcedReplan(String runId){
        forcePlanPorRun
                .computeIfAbsent(runId, k -> new AtomicBoolean(false))
                .set(true);

    }

    public Instant updateWindowEnd(String id, Instant wEnd){
        AtomicBoolean forced = forcePlanPorRun.get(id);
        Instant newWEnd = wEnd;
        boolean fueForzado = forced != null && forced.getAndSet(false);

        /// Sí hubo cambio, se actualiza al tiempo actual. Si no, sigue siendo wEnd original
        if (fueForzado){
            System.out.println("[RunManager]: Fue forzado en el plan: " + id + ". Cambiando wEnd...");
            newWEnd = currentSimNow(id);
        }

        return newWEnd;
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
        pedidosCargados.agregarAHistorico();

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
                        int qTramo = t.getCantidad();                        // usa la cantidad efectiva del tramo
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


                System.out.println("[Cancel]    Pedido " + plan.getIdPedido()
                        + " -> rutas después de cancelar: " + rutasFiltradas.size());

                int cantidadPendiente = plan.getDemanda();

                for (RutaAsignada r : rutasFiltradas) {
                    cantidadPendiente -= r.getCantidad();  // restar rutas sobrevivientes
                }
                if (cantidadPendiente > 0) {
                    System.out.println("[Cancel]    Pedido " + plan.getIdPedido()
                            + " -> vuelve a cola con cantidad=" + cantidadPendiente);

                    pedidosCargados.reinsertarParcial(
                            plan.getIdPedido(),
                            plan.getCreadoUtc(),
                            plan.getAeropuertoDestino(),
                            cantidadPendiente
                    );

                }
            }
        }

        int x = 0;
    }



    private void broadcastWindow(WindowPacket pkt) {

        // 1) Guardamos la última ventana para este run
        lastWindows.put(pkt.runId, pkt);
        System.out.println("[RunManager] pkt guardado para runId:" + pkt.runId);

        var set = listeners.getOrDefault(pkt.runId, new java.util.concurrent.CopyOnWriteArraySet<>());
        set.forEach(l -> safe(() -> l.onWindow(pkt)));
    }

    // Nuevo Helper para Loading
    private void broadcastLoading(String runId, String message) {
        var set = listeners.getOrDefault(runId, new java.util.concurrent.CopyOnWriteArraySet<>());
        set.forEach(l -> safe(() -> l.onLoading(runId, message)));
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
    
    // =====================================================================
    // CORRECCIÓN DEFINITIVA V2: SIN DUPLICIDAD
    // =====================================================================
    private void actualizarOcupacionDesdeSolucion(String runId,
                                                  SolucionProgramacion solucionOptima,
                                                  SolucionProgramacion solucionAnterior,
                                                  List<OcupacionAlmacen> reservasPrevias,
                                                  Map<String, List<ArriboExogeno>> arribosEnVuelo,
                                                  Instant corteTiempo) {
        
        // 1. SINCRONIZAR EL MASTER PLAN
        // Obtenemos el mapa maestro de este run
        Map<Integer, PlanPedido> masterPlan = masterPlanPorRun.computeIfAbsent(runId, k -> new ConcurrentHashMap<>());
        
        // Actualizamos con los nuevos planes de esta ventana
        if (solucionOptima != null && solucionOptima.getPlanPorPedido() != null) {
            for (PlanPedido nuevoPlan : solucionOptima.getPlanPorPedido().values()) {
                masterPlan.put(nuevoPlan.getIdPedido(), nuevoPlan);
            }
        }

        // 2. Instancia base vacía
        OcupacionPorAeropuerto nuevaOcupacion = new OcupacionPorAeropuerto(aeropuertosMap);

        // 3. RECONSTRUIR OCUPACIÓN DESDE EL MASTER PLAN
        // Esta es la ÚNICA fuente de verdad. Al recorrer la ruta completa, cubrimos:
        // - Lo que ya llegó y está esperando (antes cubierto por reservasPrevias).
        // - Lo que está volando y va a llegar (antes cubierto por arribosEnVuelo).
        // - Lo que se acaba de planificar.
        
        for (PlanPedido plan : masterPlan.values()) {
            if (plan.getRutas() == null) continue;

            for (RutaAsignada ruta : plan.getRutas()) {
                if (ruta.getTramos() == null || ruta.getTramos().isEmpty()) continue;
                
                int cantidad = ruta.getCantidad();
                if (cantidad <= 0) continue;

                List<TramoAsignado> tramos = ruta.getTramos();

                // Analizamos la ruta completa para calcular intervalos precisos
                for (int i = 0; i < tramos.size(); i++) {
                    TramoAsignado tramoActual = tramos.get(i);
                    VueloProgramadoId vuelo = tramoActual.getVuelo();
                    
                    if (vuelo == null) continue;

                    Instant momentoLlegada = vuelo.getLlegadaUtc();
                    Instant momentoSalidaODesocupacion;

                    // CASO 1: Es una ESCALA (hay un tramo siguiente)
                    if (i < tramos.size() - 1) {
                        TramoAsignado tramoSiguiente = tramos.get(i + 1);
                        if (tramoSiguiente.getVuelo() != null) {
                            // La carga ocupa espacio desde que llega hasta que SALE el siguiente vuelo
                            momentoSalidaODesocupacion = tramoSiguiente.getVuelo().getSalidaUtc();
                        } else {
                            // Fallback
                            momentoSalidaODesocupacion = momentoLlegada.plus(PICKUP_WAIT);
                        }
                    } 
                    // CASO 2: Es el DESTINO FINAL
                    else {
                        // La carga ocupa espacio desde que llega hasta que el cliente la recoge (2h)
                        momentoSalidaODesocupacion = momentoLlegada.plus(PICKUP_WAIT);
                    }

                    // Registramos la reserva
                    if (momentoLlegada != null && momentoSalidaODesocupacion != null && 
                        momentoLlegada.isBefore(momentoSalidaODesocupacion)) {
                        
                        nuevaOcupacion.reservar(vuelo.getDestino(), momentoLlegada, momentoSalidaODesocupacion, cantidad);
                    }
                }
            }
        }

        // 4. ELIMINADO: Agregar reservas históricas previas
        // CAUSA DE DUPLICIDAD: El MasterPlan ya incluye estos pedidos y sus tiempos de llegada originales.
        // Si los agregamos de nuevo aquí, sumamos doble.
        /*
        if (reservasPrevias != null && !reservasPrevias.isEmpty()) {
             ... ELIMINADO ...
        }
        */

        // 5. ELIMINADO: Agregar arribos en vuelo
        // CAUSA DE DUPLICIDAD: El MasterPlan ya tiene estos vuelos programados con sus fechas futuras.
        /*
        if (arribosEnVuelo != null && !arribosEnVuelo.isEmpty()) {
             ... ELIMINADO ...
        }
        */

        // Guardamos la ocupación definitiva
        ocupacionesPorRun.put(runId, nuevaOcupacion);
    }
    private static void sleepQuietly(Duration d) {
        try { Thread.sleep(d.toMillis()); } catch (InterruptedException ignored) {}
    }
    
    private Map<String, Object> calcularEventosActuales(String runId, String codigoAeropuerto, Instant simNow) {
        Map<String, Object> eventos = new HashMap<>();
        int cargaLlegando = 0;
        int cargaSaliendo = 0;
        
        try {
            Duration ventana = Duration.ofMinutes(5);
            Instant simNowMinus = simNow.minus(ventana);
            Instant simNowPlus = simNow.plus(ventana);
            
            // Usamos el mapa maestro para ver eventos reales, no solo la solución parcial
            // Convertimos los planes en tramos para iterar (similar a la lógica anterior)
            Map<Integer, PlanPedido> masterPlan = masterPlanPorRun.get(runId);
            
            if (masterPlan != null) {
                for (PlanPedido plan : masterPlan.values()) {
                    if (plan.getRutas() == null) continue;
                    for (RutaAsignada ruta : plan.getRutas()) {
                        if (ruta.getTramos() == null) continue;
                        
                        // Iteramos sobre tramos para detectar vuelos activos
                        for (TramoAsignado tramo : ruta.getTramos()) {
                            VueloProgramadoId vuelo = tramo.getVuelo();
                            if (vuelo == null) continue;
                            int cantidad = tramo.getCantidad();
                            
                            // Salidas AHORA
                            if (codigoAeropuerto.equals(vuelo.getOrigen()) && 
                                !vuelo.getSalidaUtc().isBefore(simNowMinus) && 
                                !vuelo.getSalidaUtc().isAfter(simNowPlus)) {
                                cargaSaliendo += cantidad;
                            }
                            
                            // Llegadas AHORA
                            if (codigoAeropuerto.equals(vuelo.getDestino()) && 
                                !vuelo.getLlegadaUtc().isBefore(simNowMinus) && 
                                !vuelo.getLlegadaUtc().isAfter(simNowPlus)) {
                                cargaLlegando += cantidad;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[RunManager] Error calculando eventos actuales: " + e.getMessage());
        }
        
        eventos.put("cargaLlegando", cargaLlegando);
        eventos.put("cargaSaliendo", cargaSaliendo);
        return eventos;
    }
    
    private Map<String, Object> calcularEstadisticasFuturas(String runId, String codigoAeropuerto, Instant simNow, Instant simNow24h) {
        Map<String, Object> stats = new HashMap<>();
        int llegadasPrevistas = 0;
        int salidasPrevistas = 0;
        int cargaEntrante = 0;
        int cargaSaliente = 0;
        
        try {
            // Usamos el master plan para ver el futuro real
            Map<Integer, PlanPedido> masterPlan = masterPlanPorRun.get(runId);
            
            if (masterPlan != null) {
                for (PlanPedido plan : masterPlan.values()) {
                    if (plan.getRutas() == null) continue;
                    for (RutaAsignada ruta : plan.getRutas()) {
                        if (ruta.getTramos() == null) continue;
                        
                        for (TramoAsignado tramo : ruta.getTramos()) {
                            VueloProgramadoId vuelo = tramo.getVuelo();
                            if (vuelo == null) continue;
                            int cantidad = tramo.getCantidad();
                            
                            // Salidas Futuras
                            if (codigoAeropuerto.equals(vuelo.getOrigen()) && 
                                !vuelo.getSalidaUtc().isBefore(simNow) && 
                                !vuelo.getSalidaUtc().isAfter(simNow24h)) {
                                salidasPrevistas++;
                                cargaSaliente += cantidad;
                            }
                            
                            // Llegadas Futuras
                            if (codigoAeropuerto.equals(vuelo.getDestino()) && 
                                !vuelo.getLlegadaUtc().isBefore(simNow) && 
                                !vuelo.getLlegadaUtc().isAfter(simNow24h)) {
                                llegadasPrevistas++;
                                cargaEntrante += cantidad;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[RunManager] Error stats futuras: " + e.getMessage());
        }
        
        stats.put("llegadasPrevistas", llegadasPrevistas);
        stats.put("salidasPrevistas", salidasPrevistas);
        stats.put("cargaEntrante", cargaEntrante);
        stats.put("cargaSaliente", cargaSaliente);
        return stats;
    }
    
    private List<Object> extraerVuelosDeVentana(SolucionProgramacion solucion, Instant wStart, Instant wEnd) {
        List<Object> vuelosVentana = new ArrayList<>();
        if (solucion == null || solucion.getCargaPorVuelo() == null) return vuelosVentana;

        CargaPorVuelo cargaPorVuelo = solucion.getCargaPorVuelo();
        Set<VueloProgramadoId> vuelosSeleccionados = new LinkedHashSet<>();

        for (Map.Entry<VueloProgramadoId, Integer> entry : cargaPorVuelo.getAsignado().entrySet()) {
            VueloProgramadoId vueloId = entry.getKey();
            Instant salida = vueloId.getSalidaUtc();
            if (salida != null && !salida.isBefore(wStart) && salida.isBefore(wEnd)) {
                vuelosSeleccionados.add(vueloId);
            }
        }
        
        // Lógica original de extracción de rutas...
        Map<Integer, PlanPedido> planPorPedido = solucion.getPlanPorPedido();
        if (planPorPedido != null) {
            for (PlanPedido plan : planPorPedido.values()) {
                if (plan == null || plan.getRutas() == null) continue;
                for (RutaAsignada ruta : plan.getRutas()) {
                    if (ruta.getTramos() == null) continue;
                    VueloProgramadoId primerVuelo = ruta.getTramos().get(0).getVuelo();
                    Instant salidaPrimera = primerVuelo != null ? primerVuelo.getSalidaUtc() : null;
                    
                    if (salidaPrimera != null && !salidaPrimera.isBefore(wStart) && salidaPrimera.isBefore(wEnd)) {
                         for (TramoAsignado tramo : ruta.getTramos()) {
                             if (tramo.getCantidad() > 0 && tramo.getVuelo() != null) {
                                 vuelosSeleccionados.add(tramo.getVuelo());
                             }
                         }
                    }
                }
            }
        }

        for (VueloProgramadoId vueloId : vuelosSeleccionados) {
            int cantidadAsignada = cargaPorVuelo.getAsignado().getOrDefault(vueloId, 0);
            if (cantidadAsignada <= 0) continue;

            String vueloIdStr = vueloId.getOrigen() + "-" + vueloId.getDestino() + "-" + vueloId.getSalidaUtc().toString().replace(":", "");
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
            vueloDTO.put("carga", extraerCargaDelVuelo(solucion, vueloId));

            vuelosVentana.add(vueloDTO);
        }
        return vuelosVentana;
    }
    
    private List<Map<String, Object>> extraerCargaDelVuelo(SolucionProgramacion solucion, VueloProgramadoId vuelo) {
        List<Map<String, Object>> carga = new ArrayList<>();
        if (solucion == null || solucion.getPlanPorPedido() == null) return carga;

        for (Map.Entry<Integer, PlanPedido> entry : solucion.getPlanPorPedido().entrySet()) {
            PlanPedido plan = entry.getValue();
            if (plan == null || plan.getRutas() == null) continue;

            for (RutaAsignada ruta : plan.getRutas()) {
                if (ruta.getTramos() == null) continue;
                for (int i = 0; i < ruta.getTramos().size(); i++) {
                    TramoAsignado tramo = ruta.getTramos().get(i);
                    if (esElMismoVuelo(tramo.getVuelo(), vuelo)) {
                        boolean esConexion = i < ruta.getTramos().size() - 1;
                        Map<String, Object> itemCarga = new HashMap<>();
                        itemCarga.put("pedidoId", plan.getIdPedido());
                        itemCarga.put("cantidad", tramo.getCantidad());
                        itemCarga.put("destinoFinal", plan.getAeropuertoDestino());
                        itemCarga.put("esConexion", esConexion);
                        itemCarga.put("creadoUtc", plan.getCreadoUtc() != null ? plan.getCreadoUtc().toString() : null);
                        carga.add(itemCarga);
                        break; 
                    }
                }
            }
        }
        return carga;
    }

    private boolean esElMismoVuelo(VueloProgramadoId v1, VueloProgramadoId v2) {
        if (v1 == null || v2 == null) return false;
        return Objects.equals(v1.getOrigen(), v2.getOrigen()) &&
               Objects.equals(v1.getDestino(), v2.getDestino()) &&
               Objects.equals(v1.getSalidaUtc(), v2.getSalidaUtc());
    }

    private List<Object> convertirPedidosADTO(List<Pedido> pedidos, SolucionProgramacion solucion) {
        List<Object> pedidosDTO = new ArrayList<>();
        Duration TIEMPO_ESPERA_CLIENTE = Duration.ofHours(2); 

        for (Pedido pedido : pedidos) {
            Map<String, Object> pedidoDTO = new HashMap<>();
            pedidoDTO.put("id", pedido.getIdPedido());
            pedidoDTO.put("idCliente", pedido.getIdCliente());
            pedidoDTO.put("destino", pedido.getDestino());
            
            List<Map<String, Object>> listaRecojos = new ArrayList<>();
            List<String> origenes = new ArrayList<>();
            int cantidadAsignada = 0;
            List<Map<String, Object>> rutasDetalle = new ArrayList<>();
            int cantidadTotal = pedido.getCantidad(); 
            
            if (solucion != null) {
                var plan = solucion.planDe(pedido.getIdPedido());
                if (plan != null) {
                    cantidadTotal = plan.getDemanda();
                    if (plan.getRutas() != null) {
                        for (var ruta : plan.getRutas()) {
                            if (ruta.getTramos() != null && !ruta.getTramos().isEmpty()) {
                                var primerTramo = ruta.getTramos().get(0);
                                String origen = primerTramo.getVuelo().getOrigen();
                                if (origen != null && !origenes.contains(origen)) origenes.add(origen);
                                cantidadAsignada += ruta.getCantidad();

                                var ultimoTramo = ruta.getTramos().get(ruta.getTramos().size() - 1);
                                var vueloFinal = ultimoTramo.getVuelo();
                                
                                if (vueloFinal != null && 
                                    vueloFinal.getDestino().equals(pedido.getDestino()) && 
                                    vueloFinal.getLlegadaUtc() != null) {
                                    
                                    Instant llegadaReal = vueloFinal.getLlegadaUtc();
                                    Map<String, Object> recojoInfo = new HashMap<>();
                                    recojoInfo.put("cantidad", ruta.getCantidad()); 
                                    recojoInfo.put("inicioRecojo", llegadaReal.toString()); 
                                    recojoInfo.put("finRecojo", llegadaReal.plus(TIEMPO_ESPERA_CLIENTE).toString());
                                    listaRecojos.add(recojoInfo);
                                }
                                
                                Map<String, Object> rutaDetalle = new HashMap<>();
                                rutaDetalle.put("cantidad", ruta.getCantidad());
                                rutaDetalle.put("origen", origen);
                                rutaDetalle.put("destinoFinal", pedido.getDestino());
                                
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
                                    vueloRuta.put("cantidad", ruta.getCantidad()); 
                                    vuelosRuta.add(vueloRuta);
                                }
                                rutaDetalle.put("vuelos", vuelosRuta);
                                rutasDetalle.add(rutaDetalle);
                            }
                        }
                    }
                }
            }
            
            pedidoDTO.put("recojos", listaRecojos);
            pedidoDTO.put("cantidad", cantidadTotal);
            pedidoDTO.put("fechaCreacion", pedido.getCreatedAtUtc() != null ? pedido.getCreatedAtUtc().toString() : null);
            pedidoDTO.put("fechaLocal", pedido.getFecha() != null ? pedido.getFecha().toString() : null);
            
            String continenteDestino = pedido.getContinenteDestino();
            if (continenteDestino == null && aeropuertosMap != null) {
                var aeropuerto = aeropuertosMap.obtener(pedido.getDestino());
                if (aeropuerto != null) continenteDestino = aeropuerto.getContinente();
            }
            pedidoDTO.put("continenteDestino", continenteDestino);
            
            if (origenes.isEmpty()) pedidoDTO.put("origen", null);
            else if (origenes.size() == 1) pedidoDTO.put("origen", origenes.get(0));
            else pedidoDTO.put("origen", origenes);
            
            pedidoDTO.put("cantidadAsignada", cantidadAsignada);
            pedidoDTO.put("estadoAsignacion", cantidadAsignada >= cantidadTotal ? "COMPLETO" : 
                                              cantidadAsignada > 0 ? "PARCIAL" : "PENDIENTE");
            pedidoDTO.put("rutas", rutasDetalle);
            
            pedidosDTO.add(pedidoDTO);
        }
        return pedidosDTO;
    }

    public List<VueloCancelado> transformar(Set<VueloProgramadoId> vuelosCancelados) {
        List<VueloCancelado> resultado = new ArrayList<>();
        ZoneId zone = ZoneOffset.UTC;
        for (VueloProgramadoId v : vuelosCancelados) {
            Instant salidaUtc = v.getSalidaUtc();
            if (salidaUtc == null) continue;
            String origen  = v.getOrigen();
            String destino = v.getDestino();
            LocalDate fecha = salidaUtc.atZone(zone).toLocalDate();
            LocalTime hora  = salidaUtc.atZone(zone).toLocalTime();
            resultado.add(new VueloCancelado(origen, destino, fecha, hora, salidaUtc));
        }
        return resultado;
    }
    
    /**
     * RESTAURADO: Obtiene todos los vuelos planificados del día siguiente.
     * Necesario para VuelosSseController.
     */
    public List<Map<String, Object>> getScheduledFlightsNextDay(String runId) {
        List<Map<String, Object>> vuelosPlanificados = new ArrayList<>();
        
        try {
            // Obtener el tiempo actual de simulación
            Instant simNow = currentSimNow(runId);
            
            // Calcular el límite del día siguiente (simNow + 24 horas)
            Instant simNowNextDay = simNow.plus(Duration.ofHours(24));
            
            // Obtener la solución actual
            SolucionProgramacion solucion = solucionesAnteriores.get(runId);
            if (solucion == null || solucion.getCargaPorVuelo() == null) {
                return vuelosPlanificados; 
            }
            
            CargaPorVuelo cargaPorVuelo = solucion.getCargaPorVuelo();
            
            // Iterar sobre todos los vuelos asignados en la solución
            for (Map.Entry<VueloProgramadoId, Integer> entry : cargaPorVuelo.getAsignado().entrySet()) {
                VueloProgramadoId vueloId = entry.getKey();
                int cantidadAsignada = entry.getValue(); // Cantidad total en el vuelo
                
                // Incluir vuelos planificados del día siguiente
                if (vueloId.getSalidaUtc() != null) {
                    Instant salida = vueloId.getSalidaUtc();
                    
                    // IMPORTANTE: Solo incluir vuelos cuya salida está en el futuro (>= simNow)
                    // y dentro de las próximas 24 horas (<= simNow + 24h)
                    if (!salida.isBefore(simNow) && !salida.isAfter(simNowNextDay)) {
                        
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
                        vueloDTO.put("cantidadAsignada", cantidadAsignada);
                        vueloDTO.put("capacidad", cargaPorVuelo.capacidad(vueloId));
                        vueloDTO.put("residual", cargaPorVuelo.residual(vueloId));
                        vueloDTO.put("costo", vueloId.getCosto());
                        
                        // Reutilizamos el método existente para extraer la carga
                        vueloDTO.put("carga", extraerCargaDelVuelo(solucion, vueloId));
                        
                        vuelosPlanificados.add(vueloDTO);
                    }
                }
            }
            
            // Ordenar por hora de salida
            vuelosPlanificados.sort((a, b) -> {
                String salidaA = (String) a.get("salidaUtc");
                String salidaB = (String) b.get("salidaUtc");
                if (salidaA == null || salidaB == null) return 0;
                return salidaA.compareTo(salidaB);
            });
            
        } catch (Exception e) {
            System.err.println("[RunManager] Error obteniendo vuelos planificados: " + e.getMessage());
            e.printStackTrace();
        }
        
        return vuelosPlanificados;
    }
    public List<Object> getActiveStateSnapshot(String runId) {
        List<Object> activeFlights = new ArrayList<>();
        
        try {
            // 1. Obtener tiempo actual y plan maestro
            Instant simNow = currentSimNow(runId);
            Map<Integer, PlanPedido> masterPlan = masterPlanPorRun.get(runId);
            SolucionProgramacion ultimaSolucion = solucionesAnteriores.get(runId); // Para capacidades

            if (masterPlan == null || ultimaSolucion == null || ultimaSolucion.getCargaPorVuelo() == null) {
                return activeFlights;
            }

            CargaPorVuelo cargaPorVuelo = ultimaSolucion.getCargaPorVuelo();
            Set<VueloProgramadoId> vuelosProcesados = new HashSet<>();

            // 2. Recorrer el plan maestro buscando vuelos activos
            for (PlanPedido plan : masterPlan.values()) {
                if (plan.getRutas() == null) continue;
                for (RutaAsignada ruta : plan.getRutas()) {
                    if (ruta.getTramos() == null) continue;
                    
                    for (TramoAsignado tramo : ruta.getTramos()) {
                        VueloProgramadoId vuelo = tramo.getVuelo();
                        if (vuelo == null) continue;

                        // Un vuelo es relevante para el snapshot si:
                        // A) Ya despegó (salida <= now)
                        // B) Aún no ha "terminado" visualmente (llegada >= now - buffer)
                        //    (Le damos un buffer de 30 mins post-llegada para que no desaparezca de golpe si hay lag)
                        Instant salida = vuelo.getSalidaUtc();
                        Instant llegada = vuelo.getLlegadaUtc();
                        
                        // Buffer visual: mostrar aviones que acaban de aterrizar hace poco
                        Instant corteVisual = simNow.minus(Duration.ofMinutes(30));

                        if (salida != null && llegada != null && 
                            salida.isBefore(simNow) && // Ya salió
                            llegada.isAfter(corteVisual)) { // Aún es relevante
                            
                            if (vuelosProcesados.contains(vuelo)) continue;
                            vuelosProcesados.add(vuelo);

                            // Construir DTO del vuelo (reutilizando lógica existente)
                             String vueloIdStr = vuelo.getOrigen() + "-" + vuelo.getDestino() + "-" + vuelo.getSalidaUtc().toString().replace(":", "");
                             
                             int cantidadAsignada = cargaPorVuelo.getAsignado().getOrDefault(vuelo, 0);

                             Map<String, Object> vueloDTO = new HashMap<>();
                             vueloDTO.put("id", vueloIdStr);
                             vueloDTO.put("origen", vuelo.getOrigen());
                             vueloDTO.put("destino", vuelo.getDestino());
                             vueloDTO.put("salidaUtc", vuelo.getSalidaUtc().toString());
                             vueloDTO.put("llegadaUtc", vuelo.getLlegadaUtc() != null ? vuelo.getLlegadaUtc().toString() : null);
                             vueloDTO.put("cantidadAsignada", cantidadAsignada);
                             vueloDTO.put("capacidad", cargaPorVuelo.capacidad(vuelo));
                             vueloDTO.put("carga", extraerCargaDelVuelo(ultimaSolucion, vuelo));
                             
                             activeFlights.add(vueloDTO);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[RunManager] Error generando snapshot: " + e.getMessage());
        }
        return activeFlights;
    }
}