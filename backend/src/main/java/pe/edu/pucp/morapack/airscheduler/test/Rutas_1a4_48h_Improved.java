package pe.edu.pucp.morapack.airscheduler.test;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.ArchivoUtils;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosMap;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.teg.TEGEventBuilder;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.teg.helpers.TEGParametros;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.OcupacionPorAeropuerto;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.SolucionProgramacion;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.VueloProgramadoId;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.service.IndexVuelos;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.service.VueloFicha;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.ssp.SSPGeneradorSeed;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Runner mejorado:
 * - Enumera rutas de 1..4 tramos (validadas contra catálogo original).
 * - Planifica en conjunto con cuota (packing por tandas) y reordenamiento dinámico por escasez.
 * - Respeta capacidad de vuelos y de bodegas (escalas y +2h en SEQM).
 * - Multi-start con distintos seeds/pesos y se queda con la mejor solución.
 * - Exporta CSV y (opcional) un LP para certificar óptimo sobre el set enumerado.
 */
public class Rutas_1a4_48h_Improved {

    private static final String DESTINO = "SVMI";
    private static final Set<String> SEDES = new HashSet<>(Arrays.asList("SPIM", "EBCI", "UBBB"));

    private static final Instant INICIO = Instant.parse("2025-10-24T06:22:18Z");
    private static final Instant FIN = INICIO.plus(Duration.ofHours(48));
    private static final Duration PICKUP_FINAL = Duration.ofHours(2);
    private static final int K_MAX = 4;

    // ===== Parámetros de empaquetado / ordenamiento =====
    private static final int QUOTA = 200;               // tamaño de lote por asignación (sube/baja según pruebas)
    private static final int MULTISTART_RUNS = 30;       // corridas con diferentes seeds/pesos
    private static final double W_EARLY = 0.5;          // peso por llegada temprana
    private static final double W_SCARCITY = 4.0;       // peso por escasez de vuelos
    private static final double W_WH_TIGHT = 2.0;       // peso por estrechez de bodega
    private static final boolean EXPORT_LP = false;     // exportar .lp del set enumerado

    public static void main(String[] args) {
        try {
            new Rutas_1a4_48h_Improved().run();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void run() throws Exception {
        // ===== Catálogos base =====
        AeropuertosMap aeropuertosMap = new AeropuertosMap();
        try (Scanner sc = ArchivoUtils.getScannerFromResource(
                "c.1inf54.25.2.Aeropuerto.husos.v1.20250818__estudiantes.txt")) {
            if (sc == null) throw new IllegalStateException("No se encontró archivo de husos.");
            aeropuertosMap.leerDatos(sc);
        }

        VuelosMap vuelosMaestro = new VuelosMap(aeropuertosMap);
        try (Scanner sc = ArchivoUtils.getScannerFromResource(
                "c.1inf54.25.2.planes_vuelo.v4.20250818.txt")) {
            if (sc == null) throw new IllegalStateException("No se encontró catálogo de vuelos.");
            vuelosMaestro.leerDatos(sc);
        }

        // ===== TEG =====
        TEGParametros params = TEGParametros.builder()
                .inicioUtc(INICIO)
                .finUtc(FIN)
                .sedes(SEDES)
                .arribosLibres(Map.of())
                .reservasWaitIniciales(List.of())
                .build();

        VuelosTEG teg = new TEGEventBuilder(aeropuertosMap, vuelosMaestro).construir(params);
        IndexVuelos index = new IndexVuelos(teg);

        // ===== Capacidades por vuelo programado =====
        OcupacionPorAeropuerto occDummy = new OcupacionPorAeropuerto(aeropuertosMap);
        SSPGeneradorSeed ssp = new SSPGeneradorSeed(SEDES, Map.of(), occDummy);
        SolucionProgramacion seed = ssp.generarSeed(teg, List.of(), INICIO); // sin pedidos
        final Map<VueloProgramadoId, Integer> capVuelo = new HashMap<>(seed.getCargaPorVuelo().getCapacidad());

        // ===== Cache de validación contra catálogo original =====
        CatalogLookupCache catalogCache = new CatalogLookupCache(vuelosMaestro.getVuelosPorOrigen());

        // ===== Enumeración de candidatos (1..4 tramos) =====
        Map<String, Route> candidatos = new LinkedHashMap<>();
        for (String origen : SEDES) {
            enumerateRoutes(origen, DESTINO, INICIO, FIN, 1,
                    new ArrayList<>(), new HashSet<>(List.of(origen)),
                    index, capVuelo, catalogCache, candidatos);
        }

        if (candidatos.isEmpty()) {
            System.out.println("No se encontraron rutas candidatas.");
            return;
        }

        // ===== Multi-start: ejecutamos varias corridas con diferentes pesos/semillas y nos quedamos con la mejor =====
        Result best = null;
        for (int run = 0; run < MULTISTART_RUNS; run++) {
            long seedRun = ThreadLocalRandom.current().nextLong();
            double jScar = 0.8 + 0.4 * Math.random();
            double jTight = 0.8 + 0.4 * Math.random();
            double jEarly = 0.8 + 0.4 * Math.random();

            Result r = planificarEnConjunto(
                new ArrayList<>(candidatos.values()),
                capVuelo, aeropuertosMap, seedRun,
                W_SCARCITY * jScar,
                W_WH_TIGHT * jTight,
                W_EARLY    * jEarly
            );
            if (best == null || r.total > best.total) best = r;
        }

        if (best == null) {
            System.out.println("No se pudo asignar nada factible.");
            return;
        }

        // ===== Exportes =====
        Path outDir = Paths.get("out");
        Files.createDirectories(outDir);
        Path outCsv = outDir.resolve("seqm_rutas_1a4_48h_improved.csv");
        Files.writeString(outCsv, buildCsv(best.usados), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        if (EXPORT_LP) {
            Path outLp = outDir.resolve("seqm_rutas_1a4_48h_improved.lp");
            LPExporter.exportLP(best.candidatosOrdenados, capVuelo, aeropuertosMap, outLp, PICKUP_FINAL);
            System.out.println("• LP exportado a: " + outLp.toAbsolutePath());
        }

        // ===== Telemetría (opcional) =====
        RouteTelemetry.printSummary(best.usados, aeropuertosMap, PICKUP_FINAL);

        // ===== Consola =====
        long total1 = best.usados.stream().filter(r -> r.legs().size()==1).mapToLong(Route::assigned).sum();
        long total2 = best.usados.stream().filter(r -> r.legs().size()==2).mapToLong(Route::assigned).sum();
        long total3 = best.usados.stream().filter(r -> r.legs().size()==3).mapToLong(Route::assigned).sum();
        long total4 = best.usados.stream().filter(r -> r.legs().size()==4).mapToLong(Route::assigned).sum();
        System.out.println("✅ Archivo generado: " + outCsv.toAbsolutePath());
        System.out.println("• TOTAL_ENVIADO_K1: " + total1);
        System.out.println("• TOTAL_ENVIADO_K2: " + total2);
        System.out.println("• TOTAL_ENVIADO_K3: " + total3);
        System.out.println("• TOTAL_ENVIADO_K4: " + total4);
        System.out.println("• TOTAL_ENVIADO: " + best.total);
    }

    // =========================================================
    // Enumeración DFS de rutas (1..K_MAX tramos)
    // - Filtra por ventana, catálogo, cap>0 y evita ciclos simples
    // - Deja rutas únicas (key canónica)
    // =========================================================
    private void enumerateRoutes(String current,
                                 String destino,
                                 Instant tMin,
                                 Instant tMax,
                                 int depth,
                                 List<VueloProgramadoId> prefix,
                                 Set<String> visitados,
                                 IndexVuelos index,
                                 Map<VueloProgramadoId, Integer> capVuelo,
                                 CatalogLookupCache catalog,
                                 Map<String, Route> out) {

        if (depth > K_MAX) return;

        List<VueloFicha> salidas = index.porOrigen(current);
        if (salidas == null || salidas.isEmpty()) return;

        for (VueloFicha vf : salidas) {
            VueloProgramadoId id = vf.id();
            Instant dep = id.getSalidaUtc();
            Instant arr = id.getLlegadaUtc();
            if (dep == null || arr == null) continue;
            if (dep.isBefore(tMin)) continue;
            if (!arr.isBefore(tMax)) continue;

            // Validación contra catálogo original por hora UTC (TOD)
            if (!catalog.exists(id.getOrigen(), id.getDestino(), dep, arr)) continue;

            // Evitar ciclos (no repetir aeropuerto)
            if (visitados.contains(id.getDestino())) continue;

            // Conexión temporal con el tramo anterior si existe
            if (!prefix.isEmpty()) {
                VueloProgramadoId last = prefix.get(prefix.size()-1);
                if (dep.isBefore(last.getLlegadaUtc())) continue;
            }

            prefix.add(id);
            visitados.add(id.getDestino());

            if (DESTINO.equals(id.getDestino())) {
                String key = routeKey(prefix);
                out.putIfAbsent(key, new Route(new ArrayList<>(prefix), 0));
            }

            if (depth < K_MAX && !DESTINO.equals(id.getDestino())) {
                enumerateRoutes(id.getDestino(), destino, arr, tMax,
                        depth + 1, prefix, visitados, index, capVuelo, catalog, out);
            }

            prefix.remove(prefix.size()-1);
            visitados.remove(id.getDestino());
        }
    }

    private static String routeKey(List<VueloProgramadoId> legs) {
        return legs.stream()
                .map(v -> v.getOrigen()+"|"+v.getDestino()+"|"+v.getSalidaUtc()+"|"+v.getLlegadaUtc())
                .collect(Collectors.joining("->"));
    }

    // =========================================================
    // Planificación conjunta (cuotas + orden dinámico por escasez)
    // =========================================================
    private Result planificarEnConjunto(List<Route> candidatos,
                                    Map<VueloProgramadoId, Integer> capVuelo,
                                    AeropuertosMap apMap,
                                    long seed,
                                    double wScarcity,
                                    double wWhTight,
                                    double wEarly) {

    // Estado vivo
    Map<VueloProgramadoId, Integer> residual = new HashMap<>(capVuelo);
    OcupacionPorAeropuerto occ = new OcupacionPorAeropuerto(apMap);

    // Semilla estable por corrida + mezcla inicial
    Random rnd = new Random(seed);
    Collections.shuffle(candidatos, rnd);

    // Tie-break estable por ruta (NO usar random dentro del comparator)
    final IdentityHashMap<Route, Double> tieBreak = new IdentityHashMap<>();
    for (Route r : candidatos) tieBreak.put(r, rnd.nextDouble());

    // Bucle de asignación por cuotas
    List<Route> usados = new ArrayList<>();
    boolean avanzo;
    int guard = 0;

    do {
        avanzo = false;
        guard++;

        // Reordenamiento dinámico por “escasez/temprano/ajuste bodega”
        candidatos.sort(
            Comparator
                .comparingDouble((Route r) ->
                    safeScore(r, residual, capVuelo, occ, apMap, wEarly, wScarcity, wWhTight)
                )
                .thenComparingDouble(tieBreak::get)
                .thenComparingInt(r -> r.legs().size())
                .thenComparing(r -> r.legs().get(0).getSalidaUtc())
        );

        for (Route r : candidatos) {
            int q = asignable(r, residual, occ, apMap);
            if (q <= 0) continue;

            int lote = Math.max(1, Math.min(QUOTA, q));
            reservarBodegas(r, lote, occ);
            for (VueloProgramadoId f : r.legs()) residual.merge(f, -lote, Integer::sum);
            usados.add(r.withAssigned(r.assigned() + lote));
            avanzo = true;
        }

        // Micro-packing final para exprimir huecos
        for (int mop : new int[]{20, 5, 1}) {
            boolean moved = false;

            candidatos.sort(
                Comparator
                    .comparingDouble((Route r) ->
                        safeScore(r, residual, capVuelo, occ, apMap, wEarly, wScarcity, wWhTight)
                    )
                    .thenComparingDouble(tieBreak::get)
                    .thenComparingInt(r -> r.legs().size())
                    .thenComparing(r -> r.legs().get(0).getSalidaUtc())
            );

            for (Route r : candidatos) {
                int q = asignable(r, residual, occ, apMap);
                if (q <= 0) continue;
                int lote = Math.min(q, mop);
                if (lote <= 0) continue;

                reservarBodegas(r, lote, occ);
                for (VueloProgramadoId f : r.legs()) residual.merge(f, -lote, Integer::sum);
                usados.add(r.withAssigned(r.assigned() + lote));
                moved = true;
            }
            if (!moved) break;
        }

    } while (avanzo && guard < 2000); // guard antipánico

    long total = usados.stream().mapToLong(Route::assigned).sum();
    usados.sort(Comparator.comparing(Route::arrival));
    return new Result(total, usados, candidatos);
}

    // =========================================================
    // Scoring heurístico (menor mejor)
    // =========================================================
    private double scoreRuta(Route r,
                            Map<VueloProgramadoId, Integer> residual,
                            Map<VueloProgramadoId, Integer> capVuelo,   // usa capVuelo
                            OcupacionPorAeropuerto occ,
                            AeropuertosMap apMap,
                            double wEarly,
                            double wScarcity,
                            double wWhTight) {

        // 1) EARLY: normaliza 0..1 respecto a la ventana [INICIO, FIN)
        double horizonMin = Math.max(1, Duration.between(INICIO, FIN).toMinutes());
        double early = Duration.between(INICIO, r.arrival()).toMinutes() / horizonMin;
        if (early < 0) early = 0;            // por seguridad
        if (early > 1) early = 1;

        // 2) SCARCITY (vuelos): promedio de (1 - residual/capacidad) en cada tramo
        double scarcity = 0.0;
        for (VueloProgramadoId f : r.legs()) {
            int cap = Math.max(1, capVuelo.getOrDefault(f, 0));    // evita /0 y cap=0
            int res = Math.max(0, residual.getOrDefault(f, 0));
            double fracFree = Math.min(1.0, res / (double) cap);   // 0..1
            scarcity += (1.0 - fracFree);                          // 0..1 (más alto = más escaso)
        }
        scarcity /= Math.max(1, r.legs().size());

        // 3) WH_TIGHT (bodegas): promedio de (1 - allowed/cap_bodega) en cada espera
        double tight = 0.0;
        int intervals = 0;
        List<VueloProgramadoId> legs = r.legs();

        // Escalas
        for (int i = 0; i < legs.size() - 1; i++) {
            VueloProgramadoId a = legs.get(i);
            VueloProgramadoId b = legs.get(i + 1);
            String ap = a.getDestino();
            int capAp = Math.max(1, apMap.getCapBodega(ap)); // evita /0
            int allow = maxReservableCapped(occ, apMap, ap, a.getLlegadaUtc(), b.getSalidaUtc()); // <= capAp
            double fracFreeWh = Math.max(0.0, Math.min(1.0, allow / (double) capAp));
            tight += (1.0 - fracFreeWh);  // 0..1 (más alto = más “apretado”)
            intervals++;
        }

        // Destino (+2h)
        VueloProgramadoId last = legs.get(legs.size() - 1);
        {
            String ap = last.getDestino();
            int capAp = Math.max(1, apMap.getCapBodega(ap));
            int allow = maxReservableCapped(occ, apMap, ap, last.getLlegadaUtc(), last.getLlegadaUtc().plus(PICKUP_FINAL));
            double fracFreeWh = Math.max(0.0, Math.min(1.0, allow / (double) capAp));
            tight += (1.0 - fracFreeWh);
            intervals++;
        }

        if (intervals > 0) tight /= intervals;

        // 4) Score final (menor es mejor)
        return wEarly * early + wScarcity * scarcity + wWhTight * tight;
    }
    private double safeScore(Route r,
                            Map<VueloProgramadoId, Integer> residual,
                            Map<VueloProgramadoId, Integer> capVuelo,
                            OcupacionPorAeropuerto occ,
                            AeropuertosMap apMap,
                            double wEarly,
                            double wScarcity,
                            double wWhTight) {
        double s = scoreRuta(r, residual, capVuelo, occ, apMap, wEarly, wScarcity, wWhTight);
        if (!Double.isFinite(s)) return Double.MAX_VALUE / 2.0;
        return s;
    }


    // =========================================================
    // Asignación factible para una ruta concreta r
    // =========================================================
    private int asignable(Route r,
                          Map<VueloProgramadoId, Integer> residualVuelo,
                          OcupacionPorAeropuerto occ,
                          AeropuertosMap apMap) {

        int q = Integer.MAX_VALUE;

        // vuelos
        for (VueloProgramadoId v : r.legs()) {
            int res = Math.max(0, residualVuelo.getOrDefault(v, 0));
            q = Math.min(q, res);
        }

        // bodegas en escala
        List<VueloProgramadoId> legs = r.legs();
        for (int i = 0; i < legs.size() - 1; i++) {
            VueloProgramadoId a = legs.get(i);
            VueloProgramadoId b = legs.get(i + 1);
            int allow = maxReservableCapped(occ, apMap, a.getDestino(), a.getLlegadaUtc(), b.getSalidaUtc());
            q = Math.min(q, allow);
        }

        // bodega en destino (+2h)
        VueloProgramadoId last = legs.get(legs.size() - 1);
        int allowFinal = maxReservableCapped(occ, apMap, last.getDestino(), last.getLlegadaUtc(), last.getLlegadaUtc().plus(PICKUP_FINAL));
        q = Math.min(q, allowFinal);

        return Math.max(0, q);
    }

    private void reservarBodegas(Route r, int q, OcupacionPorAeropuerto occ) {
        List<VueloProgramadoId> legs = r.legs();
        for (int i = 0; i < legs.size() - 1; i++) {
            VueloProgramadoId a = legs.get(i);
            VueloProgramadoId b = legs.get(i + 1);
            occ.reservar(a.getDestino(), a.getLlegadaUtc(), b.getSalidaUtc(), q);
        }
        VueloProgramadoId last = legs.get(legs.size() - 1);
        occ.reservar(last.getDestino(), last.getLlegadaUtc(), last.getLlegadaUtc().plus(PICKUP_FINAL), q);
    }

    // Cap disponible real (estado actual), acotada por cap declarada del aeropuerto
    private int maxReservableCapped(OcupacionPorAeropuerto occ, AeropuertosMap apMap, String ap, Instant ini, Instant fin) {
        if (ap == null || ini == null || fin == null || !fin.isAfter(ini)) return 0;
        int capAp = Math.max(0, apMap.getCapBodega(ap));
        if (capAp == 0) return 0;
        int mr = occ.maxReservable(ap, ini, fin);
        if (mr < 0) mr = 0;
        if (mr > capAp) mr = capAp;
        return mr;
    }

    // CSV
    private String buildCsv(List<Route> usados) {
        StringBuilder sb = new StringBuilder(256_000);
        sb.append("# Ventana: ").append(ts(INICIO)).append(" .. ").append(ts(FIN)).append('\n');
        sb.append("# Destino: ").append(DESTINO).append('\n');
        sb.append("# Sedes: ").append(String.join(",", SEDES)).append('\n');
        sb.append("# Reglas: sin sobrecargar vuelos, ni bodegas de escalas, ni bodega destino (+2h).\n");
        sb.append("# Rutas con Q_ASIGNADA>0 (ordenadas por llegada).\n\n");
        sb.append("K_TRAMOS,DEP_UTC_1,ORI_1,DST_1,ARR_UTC_1"
                + ",DEP_UTC_2,ORI_2,DST_2,ARR_UTC_2"
                + ",DEP_UTC_3,ORI_3,DST_3,ARR_UTC_3"
                + ",DEP_UTC_4,ORI_4,DST_4,ARR_UTC_4"
                + ",ARR_UTC_FINAL,Q_ASIGNADA\n");

        usados.sort(Comparator.comparing(Route::arrival));
        for (Route r : usados) {
            sb.append(r.legs().size());
            for (int i = 0; i < 4; i++) {
                if (i < r.legs().size()) {
                    VueloProgramadoId v = r.legs().get(i);
                    sb.append(',').append(ts(v.getSalidaUtc()))
                      .append(',').append(v.getOrigen())
                      .append(',').append(v.getDestino())
                      .append(',').append(ts(v.getLlegadaUtc()));
                } else {
                    sb.append(",,,,");
                }
            }
            sb.append(',').append(ts(r.arrival()))
              .append(',').append(r.assigned())
              .append('\n');
        }

        long total1 = usados.stream().filter(r -> r.legs().size()==1).mapToLong(Route::assigned).sum();
        long total2 = usados.stream().filter(r -> r.legs().size()==2).mapToLong(Route::assigned).sum();
        long total3 = usados.stream().filter(r -> r.legs().size()==3).mapToLong(Route::assigned).sum();
        long total4 = usados.stream().filter(r -> r.legs().size()==4).mapToLong(Route::assigned).sum();
        long total = total1 + total2 + total3 + total4;

        sb.append("\n# TOTAL_ENVIADO_K1: ").append(total1).append('\n');
        sb.append("# TOTAL_ENVIADO_K2: ").append(total2).append('\n');
        sb.append("# TOTAL_ENVIADO_K3: ").append(total3).append('\n');
        sb.append("# TOTAL_ENVIADO_K4: ").append(total4).append('\n');
        sb.append("# TOTAL_ENVIADO: ").append(total).append('\n');
        return sb.toString();
    }

    // =========================================================
    // DTO de ruta
    // =========================================================
    public static record Route(List<VueloProgramadoId> legs, int assigned) {
        Instant arrival() { return legs.get(legs.size() - 1).getLlegadaUtc(); }
        Route withAssigned(int q) { return new Route(legs, q); }
    }

    private static record Result(long total, List<Route> usados, List<Route> candidatosOrdenados) {}

    private static String ts(Instant t) {
        return (t == null ? "-" :
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm'Z'")
                        .withZone(ZoneOffset.UTC).format(t));
    }

}
