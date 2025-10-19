package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Vuelo;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * SplitRepair (versión "packing máximo"):
 * - Si un plan NO tiene rutas, genera rutas nuevas metiendo el mayor lote posible por ruta (capacidad mínima residual a lo largo de la ruta).
 * - Si un plan YA tiene rutas, combina rutas idénticas (mismo itinerario) sumando cantidades.
 *
 * Evita crear múltiples rutas duplicadas para el mismo vuelo.
 */
public class SplitRepair implements RepairOperator {

    private final VuelosTEG teg;
    private final List<String> sedes;
    /** umbralGrande queda para compatibilidad, pero ya no "trocea" en lotes pequeños. */
    private final int umbralGrande;

    public SplitRepair(List<String> sedes, VuelosTEG teg, int umbralGrande) {
        this.sedes = sedes;
        this.teg = teg;
        this.umbralGrande = umbralGrande;
    }

    @Override
    public void repair(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {
        List<PlanPedido> planos = new ArrayList<>(s.getPlanPorPedido().values());

        for (PlanPedido plan : planos) {
            if (plan.getDemanda() <= 0) continue;

            List<RutaAsignada> nuevasRutas;
            if (plan.getRutas() == null || plan.getRutas().isEmpty()) {
                // Caso A: no hay rutas -> construir rutas con "packing máximo"
                nuevasRutas = generarRutasPackingGreedy(plan.getDemanda(), plan.getAeropuertoDestino(), plan.getCreadoUtc());
            } else {
                // Caso B: ya hay rutas -> solo combinar rutas idénticas (no las partimos más)
                nuevasRutas = combinarRutasIguales(plan.getRutas());
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
        //System.out.println("End of repairing");
    }

    /** Packing: asigna el mayor lote posible por ruta (min residual a lo largo de la ruta), coalesciendo rutas idénticas. */
    private List<RutaAsignada> generarRutasPackingGreedy(int demanda, String destino, Instant creadoUtc) {
        // capacidad "usada" local por vuelo (por instancia de Vuelo, no toca el estado global)
        IdentityHashMap<Vuelo, Integer> used = new IdentityHashMap<>();

        // Acumulador por ruta (lista de Vuelo) -> cantidad total
        LinkedHashMap<List<Vuelo>, Integer> qtyByPath = new LinkedHashMap<>();

        int restante = demanda;
        while (restante > 0) {
            List<Vuelo> ruta = buscarMejorRutaConResidual(destino, used);
            if (ruta == null || ruta.isEmpty()) break;

            int capRuta = capacidadResidualRuta(ruta, used);
            if (capRuta <= 0) break;

            int lote = Math.min(restante, capRuta);

            // Acumula por ruta (coalesce)
            qtyByPath.merge(ruta, lote, Integer::sum);

            // Descontar residual local
            for (Vuelo v : ruta) {
                int u = used.getOrDefault(v, 0);
                used.put(v, u + lote);
            }

            restante -= lote;
        }

        // Materializar rutas finales con la cantidad acumulada y los tramos (TramoAsignado) correspondientes
        List<RutaAsignada> rutas = new ArrayList<>(qtyByPath.size());
        for (Map.Entry<List<Vuelo>, Integer> e : qtyByPath.entrySet()) {
            int q = e.getValue();
            List<TramoAsignado> tramos = e.getKey().stream()
                    .map(v -> vueloToTramoAsignado(v, q, creadoUtc))
                    .collect(Collectors.toList());
            rutas.add(new RutaAsignada(q, tramos));
        }
        return rutas;
    }

    /** Combina rutas con el mismo itinerario (misma secuencia de VueloProgramadoId), sumando cantidades. */
    private List<RutaAsignada> combinarRutasIguales(List<RutaAsignada> rutas) {
        // clave = lista inmutable de VueloProgramadoId que define el itinerario
        LinkedHashMap<List<VueloProgramadoId>, Integer> acc = new LinkedHashMap<>();
        LinkedHashMap<List<VueloProgramadoId>, List<VueloProgramadoId>> itineraryRef = new LinkedHashMap<>();

        for (RutaAsignada r : rutas) {
            List<VueloProgramadoId> key = (r.getTramos() == null ? List.<VueloProgramadoId>of()
                    : r.getTramos().stream().map(TramoAsignado::getVuelo).toList());
            acc.merge(key, r.getCantidad(), Integer::sum);
            itineraryRef.putIfAbsent(key, key); // conserva la secuencia para reconstruir tramos
        }

        List<RutaAsignada> result = new ArrayList<>(acc.size());
        for (Map.Entry<List<VueloProgramadoId>, Integer> e : acc.entrySet()) {
            int q = e.getValue();
            List<TramoAsignado> tramos = e.getKey().stream()
                    .map(v -> new TramoAsignado(v, q, v.getLlegadaUtc()))
                    .collect(Collectors.toList());
            result.add(new RutaAsignada(q, tramos));
        }
        return result;
    }

    /** Busca una ruta con capacidad residual >0 en todos los tramos (respecto a 'used'). */
    private List<Vuelo> buscarMejorRutaConResidual(String destino, IdentityHashMap<Vuelo, Integer> used) {
        List<List<Vuelo>> candidatos = new ArrayList<>();
        for (String sede : sedes) {
            List<Vuelo> camino = dijkstraRutaResidual(sede, destino, used);
            if (camino != null && !camino.isEmpty()) candidatos.add(camino);
        }
        if (candidatos.isEmpty()) return null;
        candidatos.sort(Comparator.comparingDouble(this::costoRuta));
        return candidatos.get(0);
    }

    /** Dijkstra que solo usa aristas con residual>0 (capacidad - used). */
    private List<Vuelo> dijkstraRutaResidual(String origen, String destino, IdentityHashMap<Vuelo, Integer> used) {
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
                int residual = v.getCapacidad() - used.getOrDefault(v, 0);
                if (residual <= 0) continue; // sin cupo en este tramo

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

    /** Capacidad residual mínima a lo largo de la ruta. */
    private int capacidadResidualRuta(List<Vuelo> ruta, IdentityHashMap<Vuelo, Integer> used) {
        int min = Integer.MAX_VALUE;
        for (Vuelo v : ruta) {
            int residual = v.getCapacidad() - used.getOrDefault(v, 0);
            if (residual < min) min = residual;
        }
        return Math.max(0, min);
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
