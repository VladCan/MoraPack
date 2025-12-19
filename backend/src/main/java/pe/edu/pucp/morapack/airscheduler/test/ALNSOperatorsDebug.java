package pe.edu.pucp.morapack.airscheduler.test;

import jakarta.enterprise.context.ApplicationScoped;
import pe.edu.pucp.morapack.airscheduler.api.controllers.debug.ALNSDebugManager;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.ArchivoUtils;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.*;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.teg.TEGEventBuilder;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.teg.helpers.TEGParametros;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.ArriboExogeno;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.OcupacionAlmacen;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators.*;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.ssp.SSPGeneradorSeed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class ALNSOperatorsDebug {
    private AeropuertosMap aeropuertosMap = null;
    private VuelosTEG teg;
    private OcupacionPorAeropuerto ocupacionGlobal;
    private volatile Set<String> sedes = new HashSet<>(Arrays.asList("SPIM", "EBCI", "UBBB"));

    private Instant presenteUTC = Instant.parse("2025-12-15T05:00:00Z");
    private Instant wEnd;
    private Duration horizon = Duration.ofHours(72);

    private SolucionProgramacion SSPInicial = null;

    private List<Pedido> pedidosVentana = new ArrayList<>();
    private SolucionProgramacion ultimaSeed;
    private SolucionProgramacion ultimaSolucion;

    private final AtomicBoolean forceReplan = new AtomicBoolean(false);

    private String aeropuertoDebug = "SCEL"; // cámbialo por el que quieras
    private Path logFile = Path.of("alns-ops-debug.log");

    /// Lo actual:
    private SolucionProgramacion solucionActual;
    private OcupacionPorAeropuerto ocupacionActual;
    private ALNS.Journal journalActual;

    private final Path baseDir;

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    public ALNSOperatorsDebug() {
        this.baseDir = Path.of("src/main/resources");
    }

    public synchronized void cargarBase() throws Exception {
        if (aeropuertosMap != null) return;

        // === 0) Limpiamos archivos de Log ===
        borrarSiExiste("alns-ops-debug.log");

        // === 1) Aeropuertos ===
        this.aeropuertosMap = new AeropuertosMap();
        try (Scanner sc = ArchivoUtils.getScannerFromFilePath("aereopuertos.txt")) {
            if (sc == null) throw new IllegalStateException("No se pudo leer aeropuertos.txt");
            aeropuertosMap.leerDatos(sc);
        }

        // === 2) Vuelos (catálogo + TEG) ===
        // OJO: aquí tú ya tienes la forma exacta en RunManager/Test.java.
        // Copia tal cual esa parte.
        //
        // Ejemplo típico:
        VuelosMap vuelosMap = new VuelosMap(aeropuertosMap);
        try (Scanner sc = ArchivoUtils.getScannerFromFilePath("vuelos.txt")) {
            if (sc == null) throw new IllegalStateException("No se pudo leer vuelos.txt");
            vuelosMap.leerDatos(sc);
        }

        VuelosCancelados cancelados = new VuelosCancelados();
        try (Scanner sc = ArchivoUtils.getScannerFromFilePath("vuelos_cancelados.txt")) {
            if (sc != null) cancelados.leerDatos(sc);
        }

        Duration minutosVentana = Duration.ofMinutes(20);

        //Instant wEnd = wStart.plus(config.horasVentana());
        wEnd = presenteUTC.plus(minutosVentana);

        // === 3) Construir TEG ===
        Map<String, List<ArriboExogeno>> enVuelo = Map.of();
        List<OcupacionAlmacen> reservas = List.of();
        List<VueloCancelado> vuelosCanceladosTeg = List.of();

        Instant finTEG = wEnd.plus(horizon);
        TEGParametros params = TEGParametros.builder()
                .inicioUtc(presenteUTC)
                .finUtc(finTEG)
                .sedes(sedes)
                .arribosLibres(enVuelo)
                .reservasWaitIniciales(reservas)
                .vuelosCancelados(vuelosCanceladosTeg)
                .build();

        this.teg = new TEGEventBuilder(aeropuertosMap, vuelosMap).construir(params);

        // === 4) Ocupación global inicial ===
        this.ocupacionGlobal = new OcupacionPorAeropuerto(aeropuertosMap); // AJUSTA si tu ctor difiere

        // === 5) Pedidos de la ventana ===
        this.pedidosVentana = inicializarPedidosSCEL();

        this.ultimaSeed = null;
        this.ultimaSolucion = null;
    }

    private List<Pedido> inicializarPedidosSCEL() {
        List<Pedido> pedidos = new ArrayList<>();

        LocalDateTime fechaLocal = LocalDateTime.parse("2025-12-15T02:00:10.627");
        Instant fechaUTC = Instant.parse("2025-12-15T05:00:10.627Z");

        pedidos.add(new Pedido(1, 7729, "SCEL", fechaLocal, fechaUTC, 990));
        pedidos.add(new Pedido(2, 29194, "SCEL", fechaLocal, fechaUTC, 990));
        pedidos.add(new Pedido(3, 28315, "SCEL", fechaLocal, fechaUTC, 990));
        pedidos.add(new Pedido(4, 10563, "SCEL", fechaLocal, fechaUTC, 990));
        pedidos.add(new Pedido(5, 29949, "SCEL", fechaLocal, fechaUTC, 990));
        pedidos.add(new Pedido(6, 17890, "SCEL", fechaLocal, fechaUTC, 990));
        pedidos.add(new Pedido(7, 31553, "SCEL", fechaLocal, fechaUTC, 990));
        pedidos.add(new Pedido(8, 18092, "SCEL", fechaLocal, fechaUTC, 990));
        pedidos.add(new Pedido(9, 19671, "SCEL", fechaLocal, fechaUTC, 990));
        pedidos.add(new Pedido(10, 7729, "SCEL", fechaLocal, fechaUTC, 990));
        pedidos.add(new Pedido(11, 7729, "SCEL", fechaLocal, fechaUTC, 990));
        pedidos.add(new Pedido(12, 29194, "SCEL", fechaLocal, fechaUTC, 990));
        pedidos.add(new Pedido(13, 28315, "SCEL", fechaLocal, fechaUTC, 990));
        pedidos.add(new Pedido(14, 10563, "SCEL", fechaLocal, fechaUTC, 990));
        pedidos.add(new Pedido(15, 29949, "SCEL", fechaLocal, fechaUTC, 990));

        return pedidos;
    }

    public synchronized void ejecutarSSP() throws Exception{
        if (SSPInicial != null) return;

        SSPGeneradorSeed ssp = new SSPGeneradorSeed(sedes, Map.of(), ocupacionGlobal);
        this.SSPInicial = ssp.generarSeed(teg, pedidosVentana, presenteUTC);
        //El presenteUTC de arriba es el wStart

        /// Soluciones actuales:
        this.solucionActual = new SolucionProgramacion(SSPInicial);
        this.ocupacionActual = ocupacionGlobal.copiaProfunda();
        this.journalActual = new ALNS.Journal(ocupacionActual);
    }

    public synchronized void reestablecer() throws Exception{
        this.solucionActual = new SolucionProgramacion(SSPInicial);
        this.ocupacionActual = ocupacionGlobal.copiaProfunda();
        this.journalActual = new ALNS.Journal(ocupacionActual);

        imprimirReestablecer();
    }

    public synchronized void ejecutarDestructor(ALNSDebugManager.DestructorType type) throws Exception{
        DestructionOperator destrOp = switch (type) {
            case RANDOM_REMOVAL -> new RandomRemoval(30);
            case WORST_REMOVAL -> new WorstRemoval(15);
            case WAREHOUSE_CRISIS_REMOVAL -> new WarehouseCrisisRemoval(3,aeropuertosMap);
            case SLA_BREACH_REMOVAL -> new SlaBreachRemoval(3);
        };

        /// Imprimimos ocupaciones ANTES:
        dumpVuelosConCarga("BEFORE DESTROY " + type);
        dumpOcupacionAeropuerto("BEFORE DESTROY " + type);
        dumpRutasPorPedido("BEFORE DESTROY " + type);

        //Ejecutamos el operador:
        destrOp.destroy(solucionActual, journalActual, presenteUTC);

        /// Imprimimos ocupaciones DESPUÉS:
        dumpVuelosConCarga("AFTER DESTROY " + type);
        dumpOcupacionAeropuerto("AFTER DESTROY " + type);
        dumpRutasPorPedido("AFTER DESTROY " + type);

        imprimirFinSeccion();
    }

    public synchronized void ejecutarConstructor(ALNSDebugManager.ConstructorType type) throws Exception{
        RepairOperator repairOp = switch (type) {
            case SPLIT_REPAIR ->
                    new SplitRepair(new ArrayList<>(sedes), teg);
            case REGRET_2 ->
                    new Regret2RepairFast(new ArrayList<>(sedes), teg);
            case URGENCY_SPLIT_REPAIR ->
                    new UrgencySplitRepair(new ArrayList<>(sedes), teg);
        };

        /// Imprimimos ocupaciones ANTES:
        dumpVuelosConCarga("BEFORE REPAIR " + type);
        dumpOcupacionAeropuerto("BEFORE REPAIR " + type);
        dumpRutasPorPedido("BEFORE DESTROY " + type);

        repairOp.repair(solucionActual, journalActual, presenteUTC);

        /// Imprimimos ocupaciones DESPUÉS:
        dumpVuelosConCarga("AFTER REPAIR " + type);
        dumpOcupacionAeropuerto("AFTER REPAIR " + type);
        dumpRutasPorPedido("AFTER DESTROY " + type);

        imprimirFinSeccion();
    }

    private void log(String msg) {
        System.out.print(msg);
        try {
            Files.writeString(
                    logFile,
                    msg,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (Exception e) {
            System.err.println("[ALNSOperatorsDebug] No pude escribir log: " + e.getMessage());
        }
    }

    private void sanitizarSolucion(SolucionProgramacion sol) {
        for (PlanPedido plan : sol.getPlanPorPedido().values()) {
            plan.removerRutasInvalidas();
        }
    }

    private void limpiarMapaGlobal(SolucionProgramacion sol) {
        if (sol.getCargaPorVuelo() != null && sol.getCargaPorVuelo().getAsignado() != null) {
            sol.getCargaPorVuelo().getAsignado().entrySet().removeIf(entry -> entry.getValue() <= 0);
        }
    }

    private void dumpVuelosConCarga(String tag) {
        StringBuilder sb = new StringBuilder();

        sb.append("\n==============================\n");
        sb.append("[").append(tag).append("] VUELOS CON CARGA > 0\n");
        sb.append("PresenteUTC: ").append(presenteUTC).append("\n");

        var asignadoMap = solucionActual.getCargaPorVuelo().getAsignado();
        if (asignadoMap == null || asignadoMap.isEmpty()) {
            sb.append("(sin mapa de asignados)\n");
            sb.append("==============================\n");
            log(sb.toString());
            return;
        }

        long count = asignadoMap.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .count();

        sb.append("Total vuelos con carga: ").append(count).append("\n");
        int cargaTotal = 0;
        int cargaTotalAeropuerto = 0;

        var entries = asignadoMap.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .sorted(Comparator.comparing(e -> e.getKey().getLlegadaUtc()))
                .toList();

        for (var e : entries) {
            VueloProgramadoId vid = e.getKey();
            int carga = e.getValue();
            int cap = solucionActual.getCargaPorVuelo().capacidad(vid);

            cargaTotal += carga;
            if (vid.getDestino().equals(aeropuertoDebug)) cargaTotalAeropuerto += carga;

            sb.append("- ")
                    .append(vid.getOrigen()).append("->").append(vid.getDestino())
                    .append(" | salida=").append(vid.getSalidaUtc())
                    .append(" | llegada=").append(vid.getLlegadaUtc())
                    .append(" | carga=").append(carga).append("/").append(cap)
                    .append("\n");
        }

        sb.append("Carga total=").append(cargaTotal).append("\n");
        sb.append("Carga total llegando a SCEL=").append(cargaTotalAeropuerto).append("\n");


        sb.append("==============================\n");
        log(sb.toString());
    }

    private void dumpOcupacionAeropuerto(String tag) {
        StringBuilder sb = new StringBuilder();

        sb.append("\n==============================\n");
        sb.append("[").append(tag).append("] OCUPACIÓN AEROPUERTO ").append(aeropuertoDebug).append("\n");

        // Cambia esta línea al getter real que tengas
        Map<String, List<OcupacionPorAeropuerto.IntervaloOcupacion>> snap =
                ocupacionActual.snapshotActual();

        List<OcupacionPorAeropuerto.IntervaloOcupacion> intervalos =
                snap.get(aeropuertoDebug);

        if (intervalos == null || intervalos.isEmpty()) {
            sb.append("(sin ocupación registrada)\n");
            sb.append("==============================\n");
            log(sb.toString());
            return;
        }

        sb.append("Intervalos de ocupación:\n");
        for (var i : intervalos) {
            sb.append("- ")
                    .append(i.inicio()).append(" -> ")
                    .append(i.fin())
                    .append(" | qty=").append(i.cantidad())
                    .append("\n");
        }

        int maxOcc = ocupacionActual.getMaxOcupacionGlobal(aeropuertoDebug);
        sb.append("PICO MÁXIMO REGISTRADO: ").append(maxOcc).append("\n");

        sb.append("==============================\n");
        log(sb.toString());
    }

    private void dumpRutasPorPedido(String tag){
        StringBuilder sb = new StringBuilder();

        sb.append("\n==============================\n");
        sb.append("[").append(tag).append("] PEDIDOS Y RUTAS:\n");

        var planes = solucionActual.getPlanPorPedido().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();

        sb.append("Total pedidos en solucion: ").append(planes.size()).append("\n");

        for (var e : planes) {
            Integer id = e.getKey();
            PlanPedido p = e.getValue();

            sb.append("\nPedido #").append(id)
                    .append(" | destino=").append(p.getAeropuertoDestino())
                    .append(" | creado=").append(FMT.format(p.getCreadoUtc()))
                    .append(" | demanda=").append(p.getDemanda())
                    .append(" | rutas=").append(p.getRutas() == null ? 0 : p.getRutas().size())
                    .append("\n");

            if (p.getRutas() == null || p.getRutas().isEmpty()) {
                sb.append("  (sin rutas)\n");
                continue;
            }

            int idxRuta = 1;
            for (RutaAsignada r : p.getRutas()) {
                sb.append("  Ruta ").append(idxRuta++)
                        .append(" | cantidad=").append(r.getCantidad())
                        .append(" | tramos=").append(r.getTramos() == null ? 0 : r.getTramos().size())
                        .append("\n");

                if (r.getTramos() == null || r.getTramos().isEmpty()) {
                    sb.append("    (sin tramos)\n");
                    continue;
                }

                for (TramoAsignado t : r.getTramos()) {
                    VueloProgramadoId v = t.getVuelo();
                    sb.append("    ")
                            .append(v.getOrigen()).append("->").append(v.getDestino())
                            .append(" | sal=").append(FMT.format(v.getSalidaUtc()))
                            .append(" | lleg=").append(FMT.format(v.getLlegadaUtc()))
                            .append("\n");
                }
            }
        }
        sb.append("==============================\n");
        log(sb.toString());
    }

    private void imprimirFinSeccion(){
        StringBuilder sb = new StringBuilder();

        sb.append("\n\uD83D\uDD1A\uD83D\uDD1A\uD83D\uDD1A\uD83D\uDD1A\uD83D\uDD1A\n");

        log(sb.toString());
    }

    private void imprimirReestablecer(){
        StringBuilder sb = new StringBuilder();

        sb.append("\n♻\uFE0F ♻\uFE0F ♻\uFE0F ♻\uFE0F ♻\uFE0F\n");
        sb.append("\n♻\uFE0F SOLUCIÓN REESTABLECIDA A LOS VALORES DEL SSP INICIAL ♻\uFE0F\n");
        sb.append("\n♻\uFE0F ♻\uFE0F ♻\uFE0F ♻\uFE0F ♻\uFE0F\n");

        log(sb.toString());
    }

    private void borrarSiExiste(String nombre) {
        try {
            Path p = Paths.get(nombre);
            if (Files.exists(p)) {
                Files.delete(p);
                System.out.println("🗑️ Eliminado: " + p.toAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("⚠️ No se pudo borrar " + nombre + ": " + e.getMessage());
        }
    }



}
