package pe.edu.pucp.morapack.airscheduler.test;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.VueloProgramadoId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Exporta un LP (formato .lp) para resolver fuera (CBC/GLPK/Gurobi):
 *  max sum x_r
 *  s.a.
 *      sum_{r contiene f} x_r <= cap_f            (por vuelo)
 *      sum_{r usa [a,b) en AP} x_r <= cap_ap      (por cada intervalo de escala y +2h destino)
 *  x_r >= 0 (puedes pedir integridad fuera si quieres)
 */
public class LPExporter {

    public static void exportLP(List<?> rutas,
                                Map<VueloProgramadoId, Integer> capVuelo,
                                AeropuertosMap apMap,
                                Path outLp,
                                Duration pickupFinal) throws IOException {

        // Re-caster de tipo a la Route del runner
        @SuppressWarnings("unchecked")
        List<Rutas_1a4_48h_Improved.Route> routes =
                (List<Rutas_1a4_48h_Improved.Route>) rutas;

        StringBuilder sb = new StringBuilder(512_000);
        sb.append("\\ LP generado desde enumeración de rutas 1..4 tramos\n");
        sb.append("Maximize\n obj: ");
        for (int i = 0; i < routes.size(); i++) {
            if (i > 0) sb.append(" + ");
            sb.append("x").append(i);
        }
        sb.append("\nSubject To\n");

        // Restricciones por vuelo
        Map<VueloProgramadoId, List<Integer>> routesByFlight = new HashMap<>();
        for (int i = 0; i < routes.size(); i++) {
            for (VueloProgramadoId f : routes.get(i).legs()) {
                routesByFlight.computeIfAbsent(f, k -> new ArrayList<>()).add(i);
            }
        }
        int con = 1;
        for (Map.Entry<VueloProgramadoId, List<Integer>> e : routesByFlight.entrySet()) {
            VueloProgramadoId f = e.getKey();
            int cap = Math.max(0, capVuelo.getOrDefault(f, 0));
            sb.append(" c").append(con++).append(": ");
            for (int j = 0; j < e.getValue().size(); j++) {
                if (j > 0) sb.append(" + ");
                sb.append("x").append(e.getValue().get(j));
            }
            sb.append(" <= ").append(cap).append("\n");
        }

        // Restricciones por bodega (escala y +2h destino) — discretizamos por cada ruta-intervalo
        // (Eso da una relajación prudente; suficiente para certificar sobre el set enumerado)
        Map<String, List<IntervalUse>> whIntervals = new HashMap<>();
        for (int i = 0; i < routes.size(); i++) {
            List<VueloProgramadoId> legs = routes.get(i).legs();
            for (int k = 0; k < legs.size() - 1; k++) {
                var a = legs.get(k);
                var b = legs.get(k + 1);
                whIntervals.computeIfAbsent(a.getDestino(), k2 -> new ArrayList<>())
                        .add(new IntervalUse(i, a.getLlegadaUtc(), b.getSalidaUtc()));
            }
            var last = legs.get(legs.size() - 1);
            whIntervals.computeIfAbsent(last.getDestino(), k2 -> new ArrayList<>())
                    .add(new IntervalUse(i, last.getLlegadaUtc(), last.getLlegadaUtc().plus(pickupFinal)));
        }

        for (Map.Entry<String, List<IntervalUse>> e : whIntervals.entrySet()) {
            String ap = e.getKey();
            int cap = Math.max(0, apMap.getCapBodega(ap));
            for (int j = 0; j < e.getValue().size(); j++) {
                IntervalUse iu = e.getValue().get(j);
                if (iu.fin.isAfter(iu.ini)) {
                    sb.append(" c").append(con++).append(": x").append(iu.routeIdx)
                      .append(" <= ").append(cap).append("\n"); // cota por-intervalo
                }
            }
        }

        sb.append("Bounds\n");
        for (int i = 0; i < routes.size(); i++) {
            sb.append(" x").append(i).append(" >= 0\n");
        }
        sb.append("End\n");

        Files.writeString(outLp, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static final class IntervalUse {
        final int routeIdx;
        final Instant ini, fin;
        IntervalUse(int routeIdx, Instant ini, Instant fin) {
            this.routeIdx = routeIdx; this.ini = ini; this.fin = fin;
        }
    }
}
