package pe.edu.pucp.morapack.airscheduler.test;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.VueloProgramadoId;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

/** Utilidades de diagnóstico para entender cuellos. */
public class RouteTelemetry {

    public static void printSummary(List<Rutas_1a4_48h_Improved.Route> usados,
                                    AeropuertosMap apMap,
                                    Duration pickupFinal) {
        if (usados == null || usados.isEmpty()) return;

        // Ocupación por aeropuerto (derivada de rutas usadas)
        Map<String, TreeMap<Instant, Integer>> deltas = new HashMap<>();
        for (Rutas_1a4_48h_Improved.Route r : usados) {
            int q = Math.max(0, r.assigned());
            List<VueloProgramadoId> legs = r.legs();
            for (int i = 0; i < legs.size()-1; i++) {
                VueloProgramadoId a = legs.get(i);
                VueloProgramadoId b = legs.get(i+1);
                addDelta(deltas, a.getDestino(), a.getLlegadaUtc(), q);
                addDelta(deltas, a.getDestino(), b.getSalidaUtc(), -q);
            }
            VueloProgramadoId last = legs.get(legs.size()-1);
            addDelta(deltas, last.getDestino(), last.getLlegadaUtc(), q);
            addDelta(deltas, last.getDestino(), last.getLlegadaUtc().plus(pickupFinal), -q);
        }

        // Top aeropuertos más “tensionados” (pico vs capacidad)
        List<String> report = new ArrayList<>();
        for (Map.Entry<String, TreeMap<Instant,Integer>> e : deltas.entrySet()) {
            String ap = e.getKey();
            int cap = Math.max(0, apMap.getCapBodega(ap));
            int occ = 0, pico = 0;
            for (int d : e.getValue().values()) {
                occ += d;
                pico = Math.max(pico, occ);
            }
            if (cap > 0) {
                double uso = 100.0 * pico / cap;
                report.add(String.format("%s  pico=%d  cap=%d  uso=%.1f%%", ap, pico, cap, uso));
            }
        }

        if (!report.isEmpty()) {
            System.out.println("— Aeropuertos con mayor pico de ocupación (derivado de rutas asignadas) —");
            report.stream().sorted().forEach(System.out::println);
        }
    }

    private static void addDelta(Map<String, TreeMap<Instant,Integer>> map, String ap, Instant t, int delta) {
        if (ap == null || t == null) return;
        map.computeIfAbsent(ap, k -> new TreeMap<>()).merge(t, delta, Integer::sum);
    }

    public static String ts(Instant t) {
        return (t == null ? "-" :
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm'Z'")
                        .withZone(ZoneOffset.UTC).format(t));
    }
}
