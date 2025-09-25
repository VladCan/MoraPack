package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.Vuelo;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RegretRepair paralelizado:
 * - Procesa planes en paralelo.
 * - Procesa rutas dentro de cada plan en paralelo.
 */
public class RegretRepair implements RepairOperator {

    private final int k;
    private final VuelosTEG teg;
    private final List<String> sedes;

    public RegretRepair(int k, List<String> sedes, VuelosTEG teg) {
        this.k = k;
        this.sedes = sedes;
        this.teg = teg;
    }
    @Override
    public void repair(SolucionProgramacion s) {
        List<PlanPedido> planos = new ArrayList<>(s.getPlanPorPedido().values());

        // Paralelizamos el procesamiento de planes
        List<PlanPedido> nuevosPlanos = planos.parallelStream()
                .map(plan -> {
                    if (plan.getRutas() == null || plan.getRutas().isEmpty()) {
                        return plan; // sin cambios
                    }

                    // Procesamos rutas en paralelo
                    List<RutaAsignada> nuevasRutas = plan.getRutas().parallelStream()
                            .map(rutaActual -> repararRuta(plan, rutaActual))
                            .collect(Collectors.toList());

                    // Construimos nuevo plan con rutas actualizadas
                    return PlanPedido.builder()
                            .idPedido(plan.getIdPedido())
                            .aeropuertoDestino(plan.getAeropuertoDestino())
                            .creadoUtc(plan.getCreadoUtc())
                            .demanda(plan.getDemanda())
                            .rutas(nuevasRutas)
                            .build();
                })
                .toList();

        // Reemplazamos todos los planes de golpe
        for (PlanPedido nuevoPlan : nuevosPlanos) {
            s.getPlanPorPedido().put(nuevoPlan.getIdPedido(), nuevoPlan);
        }
    }
    //Repara una ruta de un plan (puede ejecutarse en paralelo).
    private RutaAsignada repararRuta(PlanPedido plan, RutaAsignada rutaActual) {
        List<List<Vuelo>> candidatos = new ArrayList<>();
        for (String sede : sedes) {
            List<Vuelo> camino = dijkstraRuta(sede, plan.getAeropuertoDestino(), rutaActual.getCantidad());
            if (camino != null && !camino.isEmpty()) candidatos.add(camino);
        }

        if (candidatos.isEmpty()) {
            // No encontramos alternativa → dejamos la ruta igual
            return rutaActual;
        }

        // Ordenamos por costo
        candidatos.sort(Comparator.comparingDouble(this::costoRuta));

        // Elegimos la mejor (regret se puede usar después)
        List<Vuelo> elegida = candidatos.get(0);

        List<TramoAsignado> tramos = elegida.stream()
                .map(v -> vueloToTramoAsignado(v, rutaActual.getCantidad(), plan.getCreadoUtc()))
                .collect(Collectors.toList());

        return new RutaAsignada(rutaActual.getCantidad(), tramos);
    }
    //Dijkstra sobre el grafo de vuelos.
    private List<Vuelo> dijkstraRuta(String origen, String destino, int demanda) {
        Map<String, Double> dist = new HashMap<>();
        Map<String, Vuelo> previo = new HashMap<>();
        PriorityQueue<String> pq = new PriorityQueue<>(Comparator.comparingDouble(n -> dist.getOrDefault(n, Double.POSITIVE_INFINITY)));

        for (String nodo : teg.getVuelosPorOrigen().keySet()) {
            dist.put(nodo, Double.POSITIVE_INFINITY);
        }
        dist.put(origen, 0.0);
        pq.add(origen);

        while (!pq.isEmpty()) {
            String actual = pq.poll();
            if (Double.isInfinite(dist.getOrDefault(actual, Double.POSITIVE_INFINITY))) continue;
            if (actual.equals(destino)) break;

            List<Vuelo> salidas = teg.getVuelosPorOrigen().get(actual);
            if (salidas == null) continue;

            for (Vuelo v : salidas) {
                if (v.getCapacidad() < demanda) continue;

                double peso = v.getCosto() + v.getHoraGMTDestino().toSecondOfDay() * 0.001;
                double nuevoDist = dist.getOrDefault(actual, Double.POSITIVE_INFINITY) + peso;

                if (nuevoDist < dist.getOrDefault(v.getDestino(), Double.POSITIVE_INFINITY)) {
                    dist.put(v.getDestino(), nuevoDist);
                    previo.put(v.getDestino(), v);
                    pq.add(v.getDestino());
                }
            }
        }

        if (!previo.containsKey(destino)) return null;

        List<Vuelo> ruta = new ArrayList<>();
        String nodo = destino;
        while (previo.containsKey(nodo)) {
            Vuelo v = previo.get(nodo);
            ruta.add(v);
            nodo = v.getOrigen();
        }
        Collections.reverse(ruta);
        return ruta;
    }

    private double costoRuta(List<Vuelo> ruta) {
        return ruta.stream()
                .mapToDouble(v -> v.getCosto() + v.getHoraGMTDestino().toSecondOfDay() * 0.001)
                .sum();
    }

    private TramoAsignado vueloToTramoAsignado(Vuelo v, int cantidad, Instant referencia) {
        Instant salidaUtc = v.getHoraGMTOrigen()
                .atDate(referencia.atZone(ZoneOffset.UTC).toLocalDate())
                .toInstant(ZoneOffset.UTC);
        Instant llegadaUtc = v.getHoraGMTDestino()
                .atDate(referencia.atZone(ZoneOffset.UTC).toLocalDate())
                .toInstant(ZoneOffset.UTC);

        VueloProgramadoId id = new VueloProgramadoId(
                v.getOrigen(),
                v.getDestino(),
                salidaUtc,
                llegadaUtc
        );

        return new TramoAsignado(id, cantidad, llegadaUtc);
    }
}
