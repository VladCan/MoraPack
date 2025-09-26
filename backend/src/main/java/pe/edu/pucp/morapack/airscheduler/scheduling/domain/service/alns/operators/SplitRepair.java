package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.Vuelo;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * SplitRepair:
 * - Si un plan NO tiene rutas, genera rutas nuevas dividiendo la demanda.
 * - Si un plan tiene rutas grandes, las divide en subrutas de tamaño más pequeño.
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

            if (plan.getRutas() == null || plan.getRutas().isEmpty()) {
                // Caso 1: No hay rutas, construir nuevas dividiendo la demanda
                nuevasRutas.addAll(generarRutasDivididas(plan.getDemanda(), plan.getAeropuertoDestino(), plan.getCreadoUtc()));
            } else {
                // Caso 2: Hay rutas → revisar si alguna es demasiado grande
                for (RutaAsignada ruta : plan.getRutas()) {
                    if (ruta.getCantidad() > umbralGrande) {
                        // Dividimos la ruta en bloques más pequeños
                        nuevasRutas.addAll(generarRutasDivididas(ruta.getCantidad(), plan.getAeropuertoDestino(), plan.getCreadoUtc()));
                    } else {
                        nuevasRutas.add(ruta);
                    }
                }
            }

            // Construir plan actualizado
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

    /** Genera rutas dividiendo la demanda en lotes */
    private List<RutaAsignada> generarRutasDivididas(int demanda, String destino, Instant creadoUtc) {
        List<RutaAsignada> rutas = new ArrayList<>();
        int demandaRestante = demanda;

        while (demandaRestante > 0) {
            int lote = calcularLote(demandaRestante);

            List<Vuelo> mejorRuta = buscarMejorRuta(destino, lote);
            if (mejorRuta == null || mejorRuta.isEmpty()) {
                break; // si no encontramos ruta, detenemos el split
            }

            List<TramoAsignado> tramos = mejorRuta.stream()
                    .map(v -> vueloToTramoAsignado(v, lote, creadoUtc))
                    .collect(Collectors.toList());

            rutas.add(new RutaAsignada(lote, tramos));
            demandaRestante -= lote;
        }
        return rutas;
    }

    /** Calcula el tamaño del lote */
    private int calcularLote(int demandaRestante) {
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
