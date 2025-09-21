package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.Vuelo;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reparador que toma pedidos grandes y los parte en subrutas
 * para que se asignen a múltiples vuelos si es necesario.
 */
public class SplitRepair implements RepairOperator {

    private final VuelosTEG teg;
    private final List<String> sedes;
    private final int umbralGrande; // Demanda mínima para considerar "pedido grande"

    public SplitRepair(List<String> sedes, VuelosTEG teg, int umbralGrande) {
        this.sedes = sedes;
        this.teg = teg;
        this.umbralGrande = umbralGrande;
    }

    @Override
    public void repair(SolucionProgramacion s) {
        List<PlanPedido> planos = new ArrayList<>(s.getPlanPorPedido().values());

        for (PlanPedido plan : planos) {
            if (plan.getDemanda() <= 0) continue;

            List<RutaAsignada> nuevasRutas = new ArrayList<>();

            int demandaRestante = plan.getDemanda();
            while (demandaRestante > 0) {
                // lote máximo = min(demandaRestante, mejor capacidad de la red)
                int lote = calcularLote(demandaRestante);

                List<Vuelo> mejorRuta = buscarMejorRuta(plan.getAeropuertoDestino(), lote);

                if (mejorRuta == null || mejorRuta.isEmpty()) {
                    // Si no encontramos ruta, dejamos de partir
                    break;
                }

                // Convertir a tramos asignados
                List<TramoAsignado> tramos = mejorRuta.stream()
                        .map(v -> vueloToTramoAsignado(v, lote, plan.getCreadoUtc()))
                        .collect(Collectors.toList());

                nuevasRutas.add(new RutaAsignada(lote, tramos));
                demandaRestante -= lote;
            }

            // Si no generamos nada nuevo, conservar original
            if (nuevasRutas.isEmpty() && plan.getRutas() != null) {
                nuevasRutas.addAll(plan.getRutas());
            }

            PlanPedido nuevoPlan = PlanPedido.builder()
                    .idPedido(plan.getIdPedido())
                    .aeropuertoDestino(plan.getAeropuertoDestino())
                    .creadoUtc(plan.getCreadoUtc())
                    .demanda(plan.getDemanda())
                    .rutas(nuevasRutas)
                    .build();

            s.getPlanPorPedido().put(nuevoPlan.getIdPedido(), nuevoPlan);
        }
    }

    /** Calcula cuánto partir en cada lote */
    private int calcularLote(int demandaRestante) {
        // ejemplo: mínimo entre la demanda y un bloque fijo (ej. 100)
        return Math.min(demandaRestante, umbralGrande);
    }

    /** Busca mejor ruta disponible para cierta demanda */
    private List<Vuelo> buscarMejorRuta(String destino, int demanda) {
        List<List<Vuelo>> candidatos = new ArrayList<>();
        for (String sede : sedes) {
            List<Vuelo> camino = dijkstraRuta(sede, destino, demanda);
            if (camino != null && !camino.isEmpty()) candidatos.add(camino);
        }
        if (candidatos.isEmpty()) return null;

        candidatos.sort(Comparator.comparingDouble(this::costoRuta));
        return candidatos.get(0);
    }

    /** Dijkstra sobre el grafo de vuelos */
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
