package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.Vuelo;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RegretRepair mejorado con Dijkstra sobre el TEG:
 * - Encuentra la mejor ruta (multi-hop) según costo-tiempo.
 * - Usa k para aplicar criterio de regret.
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
        for (PlanPedido plan : s.getPlanPorPedido().values()) {
            if (plan.getTramosAplanados() != null && !plan.getTramosAplanados().isEmpty()) continue;

            List<List<Vuelo>> rutas = new ArrayList<>();
            for (String sede : sedes) {
                List<Vuelo> ruta = dijkstraRuta(sede, plan.getAeropuertoDestino(), plan.getDemanda());
                if (ruta != null && !ruta.isEmpty()) {
                    rutas.add(ruta);
                }
            }

            if( plan.getIdPedido()==456){
                System.out.println("456");
            }

            if (rutas.isEmpty()) continue;

            // Ordenamos rutas por costo total (costo + tiempo)
            //System.out.println("Antes de ordenar:");
            //rutas.forEach(r -> System.out.println(r + " -> " + costoRuta(r)));
            rutas.sort(Comparator.comparingDouble(this::costoRuta));
            //System.out.println("Después de ordenar:");
            //rutas.forEach(r -> System.out.println(r + " -> " + costoRuta(r)));

            // Selección con regret (aunque aquí usamos la mejor)
            List<Vuelo> elegida;
            if (rutas.size() <= k) {
                elegida = rutas.get(0);
            } else {
                double regret = costoRuta(rutas.get(k)) - costoRuta(rutas.get(0));
                elegida = rutas.get(0);
            }

            // Asignar la ruta al plan
            List<TramoAsignado> tramos = elegida.stream()
                    .map(v -> vueloToTramoAsignado(v, plan.getDemanda(), plan.getCreadoUtc()))
                    .collect(Collectors.toList());

            RutaAsignada nuevaRuta = new RutaAsignada(plan.getDemanda(),tramos);
            List<RutaAsignada> rutasActuales = new ArrayList<>(plan.getRutas());
            rutasActuales.add(nuevaRuta);
            PlanPedido nuevoPlan = PlanPedido.builder()
                    .idPedido(plan.getIdPedido())
                    .aeropuertoDestino(plan.getAeropuertoDestino())
                    .creadoUtc(plan.getCreadoUtc())
                    .demanda(plan.getDemanda())
                    .rutas(rutasActuales) // método que acepta lista completa (Lombok builder soporta)
                    .build();
            s.getPlanPorPedido().put(nuevoPlan.getIdPedido(), nuevoPlan);
        }
    }

    /**
     * Dijkstra sobre el grafo de vuelos.
     */
    private List<Vuelo> dijkstraRuta(String origen, String destino, int demanda) {
        Map<String, Double> dist = new HashMap<>();
        Map<String, Vuelo> previo = new HashMap<>();
        PriorityQueue<String> pq = new PriorityQueue<>(Comparator.comparingDouble(dist::get));

        for (String nodo : teg.getVuelosPorOrigen().keySet()) {
            dist.put(nodo, Double.POSITIVE_INFINITY);
        }
        dist.put(origen, 0.0);
        pq.add(origen);

        while (!pq.isEmpty()) {
            String actual = pq.poll();
            if (actual.equals(destino)) break;

            List<Vuelo> salidas = teg.getVuelosPorOrigen().get(actual);
            if (salidas == null) continue;

            for (Vuelo v : salidas) {
                if (v.getCapacidad() < demanda) continue;

                double peso = v.getCosto() + v.getHoraGMTDestino().toSecondOfDay() * 0.001;
                double nuevoDist = dist.get(actual) + peso;

                if (nuevoDist < dist.getOrDefault(v.getDestino(), Double.POSITIVE_INFINITY)) {
                    dist.put(v.getDestino(), nuevoDist);
                    previo.put(v.getDestino(), v);
                    pq.add(v.getDestino());
                }
            }
        }

        // reconstruir ruta
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
