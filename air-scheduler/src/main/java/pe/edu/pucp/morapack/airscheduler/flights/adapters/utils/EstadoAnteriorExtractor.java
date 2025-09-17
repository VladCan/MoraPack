package pe.edu.pucp.morapack.airscheduler.flights.adapters.utils;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.ArriboExogeno;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.PlanPedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.SolucionProgramacion;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.TramoAsignado;
// coloca OcupacionAlmacen donde la hayas creado (p.ej. flights.domain.model)
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.OcupacionAlmacen;

public final class EstadoAnteriorExtractor {
    private EstadoAnteriorExtractor() {}

    /**
     * Vuelos ya despegados y aún no llegados a presenteUTC.
     * Se agregan como "arribos exógenos" para congelarlos (no replanificables).
     */
    public static Map<String, List<ArriboExogeno>> construirArribosEnVuelo(
            SolucionProgramacion anterior, Instant presenteUTC) {

        if (anterior == null) return Map.of();

        // destino -> (llegada -> cantidad total)  (agrupamos por si hay múltiples tramos iguales)
        Map<String, Map<Instant, Integer>> agg = new HashMap<>();

        for (PlanPedido p : anterior.getPlanPorPedido().values()) {
            if (p.getTramos() == null) continue;
            for (TramoAsignado t : p.getTramos()) {
                Instant sal = t.getVuelo().getSalidaUtc();
                Instant arr = t.getLlegadaUtc();
                int q = t.getCantidad();
                if (q <= 0 || sal == null || arr == null) continue;

                // "en vuelo": salió antes de presente y aún no llega
                if (sal.isBefore(presenteUTC) && !arr.isBefore(presenteUTC)) {
                    agg.computeIfAbsent(p.getDestinoIcao(), k -> new HashMap<>())
                       .merge(arr, q, Integer::sum);
                }
            }
        }

        if (agg.isEmpty()) return Map.of();

        Map<String, List<ArriboExogeno>> result = new HashMap<>();
        for (var e : agg.entrySet()) {
            String destino = e.getKey();
            List<ArriboExogeno> lista = e.getValue().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(x -> new ArriboExogeno(x.getKey(), x.getValue()))
                    .toList();
            result.put(destino, lista);
        }
        return result;
    }

    /**
     * Reservas de bodega activas al inicio (pickup 2h): llegadas anteriores cuyo
     * intervalo [llegada, llegada+WAIT] aún cruza presenteUTC.
     * Esto reducirá capacidad de los WAIT al construir el TEG.
     */
    public static List<OcupacionAlmacen> reservasDesdeSolucionAnterior(
            SolucionProgramacion anterior, Instant presenteUTC, Duration waitDur) {

        if (anterior == null) return List.of();
        if (waitDur == null) waitDur = Duration.ofHours(2);

        List<OcupacionAlmacen> res = new ArrayList<>();
        for (PlanPedido p : anterior.getPlanPorPedido().values()) {
            if (p.getTramos() == null) continue;
            for (TramoAsignado t : p.getTramos()) {
                Instant arr = t.getLlegadaUtc();
                int q = t.getCantidad();
                if (q <= 0 || arr == null) continue;

                Instant hasta = arr.plus(waitDur);
                // activa si: arr <= presente < arr+WAIT
                if (!arr.isAfter(presenteUTC) && hasta.isAfter(presenteUTC)) {
                    // bloquea desde max(arr, presente) para precisión
                    Instant desde = arr.isAfter(presenteUTC) ? arr : presenteUTC;
                    res.add(new OcupacionAlmacen(p.getDestinoIcao(), desde, hasta, q));
                }
            }
        }
        return res;
    }
}