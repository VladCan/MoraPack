// air-scheduler/src/main/java/pe/edu/pucp/morapack/airscheduler/flights/adapters/utils/EstadoAnteriorExtractor.java
package pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.ArriboExogeno;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.OcupacionAlmacen;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.PlanPedido;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.RutaAsignada;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.SolucionProgramacion;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.TramoAsignado;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.VueloProgramadoId;

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

                // CORRECCIÓN: Iteramos por TODOS los tramos, no solo el último
                for (int i = 0; i < legs.size(); i++) {
                    TramoAsignado currentLeg = legs.get(i);
                    VueloProgramadoId vueloLlegada = currentLeg.getVuelo();
                    
                    if (vueloLlegada == null || vueloLlegada.getLlegadaUtc() == null) continue;

                    Instant momentoLlegada = vueloLlegada.getLlegadaUtc();
                    Instant momentoLiberacion;

                    // CASO A: Es una ESCALA (no es el último tramo)
                    if (i < legs.size() - 1) {
                        TramoAsignado nextLeg = legs.get(i + 1);
                        if (nextLeg.getVuelo() == null) continue; 
                        
                        // La carga ocupa espacio hasta que sale el siguiente vuelo
                        momentoLiberacion = nextLeg.getVuelo().getSalidaUtc();
                    } 
                    // CASO B: Es el DESTINO FINAL
                    else {
                        // La carga ocupa espacio hasta que el cliente recoge (waitDur)
                        momentoLiberacion = momentoLlegada.plus(waitDur);
                    }

                    if (momentoLiberacion == null) continue;

                    // LÓGICA DE ESTADO: ¿Está la carga en el suelo AHORA?
                    // Sí, si ya llegó (llegada <= ahora) Y todavía no se libera (liberacion > ahora)
                    if (!momentoLlegada.isAfter(presenteUTC) && momentoLiberacion.isAfter(presenteUTC)) {
                        
                        // La reserva empieza AHORA (porque lo de atrás ya pasó) hasta la liberación planificada
                        Instant inicioReserva = presenteUTC;
                        String aeropuertoAlmacen = vueloLlegada.getDestino();

                        res.add(new OcupacionAlmacen(aeropuertoAlmacen, inicioReserva, momentoLiberacion, q));
                    }
                }
            }
        }
        return res;
    }
}
