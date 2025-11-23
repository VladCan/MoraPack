package pe.edu.pucp.morapack.airscheduler.test;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.ArchivoUtils;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosMap;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.teg.TEGEventBuilder;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.teg.helpers.TEGParametros;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Vuelo;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.OcupacionPorAeropuerto;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.SolucionProgramacion;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.VueloProgramadoId;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.service.IndexVuelos;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.service.VueloFicha;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.ssp.SSPGeneradorSeed;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Busca rutas de 1, 2, 3 o 4 tramos desde SEDES hasta SEQM en [INICIO, INICIO+48h),
 * y asigna en CONJUNTO la máxima cantidad sin violar:
 *  - Capacidad de cada vuelo.
 *  - Capacidad de bodegas en cada escala (ventana [llegada_i, salida_{i+1})).
 *  - Capacidad de bodega en destino (ventana [llegada_final, llegada_final + 2h)).
 *
 * También valida cada tramo contra el catálogo original (por ORI/DST y hora UTC).
 *
 * Salida: out/seqm_rutas_1a4_48h.csv (ordenadas por llegada final).
 * Consola: muestra totales enviados por longitud de ruta (1..4) y TOTAL.
 */
public class Rutas_1a4_48h {
    //de esta prueba descubrimos que la solución es subóptima, no por mucho solo 1000 productos
    //y además que no pudimos llegar a usar al 100% el aereopuerto lo máximo que pudimos usar en este
    //ejemplo es el 60%
    private static final String DESTINO = "SEQM";
    private static final Set<String> SEDES = new HashSet<>(Arrays.asList("SPIM", "EBCI", "UBBB"));

    private static final Instant INICIO = Instant.parse("2025-10-26T06:21:00Z");
    private static final Instant FIN = INICIO.plus(Duration.ofHours(48));
    private static final Duration PICKUP_FINAL = Duration.ofHours(2);
    private static final int K_MAX = 4; // máximo tramos

    public static void main(String[] args) {
        try {
            new Rutas_1a4_48h().run();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void run() throws Exception {
        // ===== Catálogos base =====
        AeropuertosMap aeropuertosMap = new AeropuertosMap();
        try (Scanner sc = ArchivoUtils.getScannerFromFilePath(
                "aereopuertos.txt")) {
            if (sc == null) throw new IllegalStateException("No se encontró archivo de husos.");
            aeropuertosMap.leerDatos(sc);
        }

        VuelosMap vuelosMaestro = new VuelosMap(aeropuertosMap);
        try (Scanner sc = ArchivoUtils.getScannerFromFilePath(
                "vuelos.txt")) {
            if (sc == null) throw new IllegalStateException("No se encontró catálogo de vuelos.");
            vuelosMaestro.leerDatos(sc);
        }

        // ===== TEG con ventana [INICIO, FIN) =====
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

        // ===== Enumeración de candidatos (1..4 tramos) =====
        List<Route> candidatos = new ArrayList<>();
        for (String origen : SEDES) {
            enumerateRoutes(origen, DESTINO, INICIO, FIN, 1, new ArrayList<>(), new HashSet<>(List.of(origen)),
                    index, capVuelo, vuelosMaestro.getVuelosPorOrigen(), candidatos);
        }

        // Ordenar candidatos por llegada final (earliest first)
        candidatos.sort(Comparator
                .comparing((Route r) -> r.arrival())
                .thenComparing(r -> r.legs().size())
                .thenComparing(r -> r.legs().get(0).getSalidaUtc()));

        // ===== Planificación conjunta =====
        OcupacionPorAeropuerto occPlan = new OcupacionPorAeropuerto(aeropuertosMap);
        Map<VueloProgramadoId, Integer> residualVuelo = new HashMap<>(capVuelo);

        long total1 = 0, total2 = 0, total3 = 0, total4 = 0;

        List<Route> usados = new ArrayList<>();
        for (Route r : candidatos) {
            int q = asignable(r, residualVuelo, occPlan, aeropuertosMap);
            if (q <= 0) continue;

            // Reservar bodegas (escala + destino +2h)
            reservarBodegas(r, q, occPlan);

            // Consumir capacidad de vuelos
            for (VueloProgramadoId f : r.legs()) {
                residualVuelo.merge(f, -q, Integer::sum);
            }

            // Acumular por longitud
            switch (r.legs().size()) {
                case 1 -> total1 += q;
                case 2 -> total2 += q;
                case 3 -> total3 += q;
                case 4 -> total4 += q;
            }

            usados.add(r.withAssigned(q));
        }

        long total = total1 + total2 + total3 + total4;

        // ===== Reporte =====
        Path outDir = Paths.get("out");
        Files.createDirectories(outDir);
        Path out = outDir.resolve("seqm_rutas_1a4_48h.csv");

        StringBuilder sb = new StringBuilder(256_000);
        sb.append("# Ventana: ").append(ts(INICIO)).append(" .. ").append(ts(FIN)).append('\n');
        sb.append("# Destino: ").append(DESTINO).append('\n');
        sb.append("# Sedes: ").append(String.join(",", SEDES)).append('\n');
        sb.append("# Reglas: sin sobrecargar vuelos, ni bodegas de escalas, ni bodega destino (+2h).\n");
        sb.append("# Solo se listan rutas REALES (validadas contra catálogo) con Q_ASIGNADA>0.\n\n");
        sb.append("K_TRAMOS,DEP_UTC_1,ORI_1,DST_1,ARR_UTC_1"
                + ",DEP_UTC_2,ORI_2,DST_2,ARR_UTC_2"
                + ",DEP_UTC_3,ORI_3,DST_3,ARR_UTC_3"
                + ",DEP_UTC_4,ORI_4,DST_4,ARR_UTC_4"
                + ",ARR_UTC_FINAL,Q_ASIGNADA\n");

        usados.sort(Comparator.comparing(Route::arrival)); // asegurar orden por llegada final
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

        sb.append("\n# TOTAL_ENVIADO_K1: ").append(total1).append('\n');
        sb.append("# TOTAL_ENVIADO_K2: ").append(total2).append('\n');
        sb.append("# TOTAL_ENVIADO_K3: ").append(total3).append('\n');
        sb.append("# TOTAL_ENVIADO_K4: ").append(total4).append('\n');
        sb.append("# TOTAL_ENVIADO: ").append(total).append('\n');

        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        // ===== Consola =====
        System.out.println("✅ Archivo generado: " + out.toAbsolutePath());
        System.out.println("• TOTAL_ENVIADO_K1: " + total1);
        System.out.println("• TOTAL_ENVIADO_K2: " + total2);
        System.out.println("• TOTAL_ENVIADO_K3: " + total3);
        System.out.println("• TOTAL_ENVIADO_K4: " + total4);
        System.out.println("• TOTAL_ENVIADO: " + total);
    }

    // =========================================================
    // Enumeración DFS de rutas (1..K_MAX tramos), filtrando:
    //  - ventana tiempo
    //  - conexión temporal arr<=dep_siguiente
    //  - vuelo real en catálogo (hora UTC coincide)
    // =========================================================
    private void enumerateRoutes(String current,
                                 String destino,
                                 Instant tMin,
                                 Instant tMax,
                                 int k,
                                 List<VueloProgramadoId> prefix,
                                 Set<String> visitados,
                                 IndexVuelos index,
                                 Map<VueloProgramadoId, Integer> capVuelo,
                                 Map<String, List<Vuelo>> catalogoPorOrigen,
                                 List<Route> out) {

        if (k > K_MAX) return;

        List<VueloFicha> salidas = index.porOrigen(current);
        if (salidas == null || salidas.isEmpty()) return;

        for (VueloFicha vf : salidas) {
            VueloProgramadoId id = vf.id();
            Instant dep = id.getSalidaUtc();
            Instant arr = id.getLlegadaUtc();
            if (dep == null || arr == null) continue;
            if (dep.isBefore(tMin)) continue;               // no podemos tomar vuelos previos
            if (!arr.isBefore(tMax)) continue;              // llegada fuera de ventana

            // Validación catálogo original para este tramo
            if (!existeEnCatalogo(id.getOrigen(), id.getDestino(), dep, arr, catalogoPorOrigen)) continue;

            // Capacidad de vuelo > 0
            if (capVuelo.getOrDefault(id, 0) <= 0) continue;

            // Evitar ciclos por aeropuerto (heurístico)
            if (visitados.contains(id.getDestino())) continue;

            // Extender ruta
            prefix.add(id);
            visitados.add(id.getDestino());

            // Si llega al destino, agregamos como candidato
            if (DESTINO.equals(id.getDestino())) {
                out.add(new Route(new ArrayList<>(prefix), 0));
            }

            // Si aún podemos agregar más tramos, continuar
            if (k < K_MAX && !DESTINO.equals(id.getDestino())) {
                enumerateRoutes(id.getDestino(), destino, arr, tMax, k + 1, prefix, visitados,
                        index, capVuelo, catalogoPorOrigen, out);
            }

            // backtrack
            prefix.remove(prefix.size() - 1);
            visitados.remove(id.getDestino());
        }
    }

    // =========================================================
    // Asignación factible para una ruta concreta r
    // - vuelos: residual
    // - bodegas: escalas [arr_i, dep_{i+1}), destino +2h
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

    // =========================================================
    // Validación contra catálogo original (por hora UTC - time-of-day)
    // =========================================================
    private boolean existeEnCatalogo(String origen, String destino,
                                     Instant depUtc, Instant arrUtc,
                                     Map<String, List<Vuelo>> catalogoPorOrigen) {
        List<Vuelo> cand = catalogoPorOrigen.get(origen);
        if (cand == null || cand.isEmpty()) return false;

        LocalTime depTOD = depUtc.atZone(ZoneOffset.UTC).toLocalTime();
        LocalTime arrTOD = arrUtc.atZone(ZoneOffset.UTC).toLocalTime();

        for (Vuelo v : cand) {
            if (!destino.equals(v.getDestino())) continue;
            if (depTOD.equals(v.getHoraGMTOrigen()) && arrTOD.equals(v.getHoraGMTDestino())) {
                return true;
            }
        }
        return false;
    }

    // Capacidad disponible *real* sobre occ (estado actual), acotada por cap declarada del aeropuerto
    private int maxReservableCapped(OcupacionPorAeropuerto occ, AeropuertosMap apMap, String ap, Instant ini, Instant fin) {
        if (ap == null || ini == null || fin == null || !fin.isAfter(ini)) return 0;
        int capAp = Math.max(0, apMap.getCapBodega(ap));
        if (capAp == 0) return 0;
        int mr = occ.maxReservable(ap, ini, fin);
        if (mr < 0) mr = 0;
        if (mr > capAp) mr = capAp;
        return mr;
    }

    // =========================================================
    // DTO de ruta
    // =========================================================
    private record Route(List<VueloProgramadoId> legs, int assigned) {
        Instant arrival() { return legs.get(legs.size() - 1).getLlegadaUtc(); }
        Route withAssigned(int q) { return new Route(legs, q); }
    }

    // =========================================================
    // Util
    // =========================================================
    private static String ts(Instant t) {
        return (t == null ? "-" :
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm'Z'")
                        .withZone(ZoneOffset.UTC).format(t));
    }
}
