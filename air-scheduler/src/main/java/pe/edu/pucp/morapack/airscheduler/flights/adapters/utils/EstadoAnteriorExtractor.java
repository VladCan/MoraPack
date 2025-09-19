// air-scheduler/src/main/java/pe/edu/pucp/morapack/airscheduler/flights/adapters/utils/EstadoAnteriorExtractor.java
package pe.edu.pucp.morapack.airscheduler.flights.adapters.utils;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import pe.edu.pucp.morapack.airscheduler.flights.domain.model.ArriboExogeno;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.OcupacionAlmacen;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.PlanPedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.RutaAsignada;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.SolucionProgramacion;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.TramoAsignado;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.VueloProgramadoId;

public final class EstadoAnteriorExtractor {
    private EstadoAnteriorExtractor() {}

    /**
     * Vuelos ya despegados y aún no llegados a 'presenteUTC'.
     * Se devuelven como “arribos exógenos” por aeropuerto destino del tramo,
     * agrupados por instante de llegada.
     */
    public static Map<String, List<ArriboExogeno>> construirArribosEnVuelo(
            SolucionProgramacion anterior, Instant presenteUTC) {

        if (anterior == null || presenteUTC == null) return Map.of();

        // apDestino -> (llegada -> cantidad)
        Map<String, Map<Instant, Integer>> agg = new HashMap<>();

        for (PlanPedido p : anterior.getPlanPorPedido().values()) {
            List<RutaAsignada> rutas = p.getRutas();
            if (rutas == null || rutas.isEmpty()) continue;

            for (RutaAsignada r : rutas) {
                int qRuta = r.getCantidad();
                if (qRuta <= 0) continue;

                List<TramoAsignado> legs = r.getTramos();
                if (legs == null || legs.isEmpty()) continue;

                for (TramoAsignado t : legs) {
                    VueloProgramadoId v = t.getVuelo();
                    if (v == null) continue;
                    Instant sal = v.getSalidaUtc();
                    Instant arr = v.getLlegadaUtc();
                    if (sal == null || arr == null) continue;

                    // “en vuelo”: salió antes del presente y aún no llega
                    if (sal.isBefore(presenteUTC) && !arr.isBefore(presenteUTC)) {
                        String apDestinoTramo = v.getDestino();
                        agg.computeIfAbsent(apDestinoTramo, k -> new HashMap<>())
                           .merge(arr, qRuta, Integer::sum);
                    }
                }
            }
        }

        if (agg.isEmpty()) return Map.of();

        Map<String, List<ArriboExogeno>> result = new HashMap<>();
        for (var e : agg.entrySet()) {
            String ap = e.getKey();
            List<ArriboExogeno> lista = e.getValue().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(x -> new ArriboExogeno(x.getKey(), x.getValue()))
                    .toList();
            result.put(ap, lista);
        }
        return result;
    }

    /**
     * Reservas de bodega activas por “pickup 2h” en el destino FINAL de cada ruta.
     * Se toma el último tramo de la ruta (su llegada) y se bloquea [llegada, llegada+waitDur].
     * Si el presente está dentro de ese intervalo, se registra una ocupación desde max(presente, llegada).
     */
    public static List<OcupacionAlmacen> reservasDesdeSolucionAnterior(
            SolucionProgramacion anterior, Instant presenteUTC, Duration waitDur) {

        if (anterior == null || presenteUTC == null) return List.of();
        if (waitDur == null) waitDur = Duration.ofHours(2);

        List<OcupacionAlmacen> res = new ArrayList<>();

        for (PlanPedido p : anterior.getPlanPorPedido().values()) {
            List<RutaAsignada> rutas = p.getRutas();
            if (rutas == null || rutas.isEmpty()) continue;

            for (RutaAsignada r : rutas) {
                int q = r.getCantidad();
                if (q <= 0) continue;

                List<TramoAsignado> legs = r.getTramos();
                if (legs == null || legs.isEmpty()) continue;

                // último tramo de la ruta (destino final de la ruta)
                TramoAsignado last = legs.get(legs.size() - 1);
                VueloProgramadoId v = last.getVuelo();
                if (v == null) continue;
                Instant arr = v.getLlegadaUtc();
                if (arr == null) continue;

                Instant hasta = arr.plus(waitDur);
                // activa si llegada ≤ presente < llegada+wait
                if (!arr.isAfter(presenteUTC) && hasta.isAfter(presenteUTC)) {
                    Instant desde = arr.isAfter(presenteUTC) ? arr : presenteUTC;
                    String apDestinoFinal = v.getDestino(); // normalmente coincide con p.getDestinoIcao()
                    res.add(new OcupacionAlmacen(apDestinoFinal, desde, hasta, q));
                }
            }
        }
        return res;
    }
}
