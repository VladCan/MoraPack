package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Vuelo;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * SplitRepair (packing máximo coherente):
 * - Asigna el mayor lote posible por camino (mínimo residual de sus tramos).
 * - Combina rutas idénticas.
 * - Actualiza cargaPorVuelo en la solución NUEVA (no mediante journal).
 * - Usa journal RESERVAR/LIBERAR solo para bodega (esperas en origen/destino).
 */
public class SplitRepair implements RepairOperator {

    private final VuelosTEG teg;
    private final List<String> sedes;

    private static final Duration PICKUP_FINAL = Duration.ofHours(2); // espera en destino final

    public SplitRepair(List<String> sedes, VuelosTEG teg) {
        this.sedes = sedes;
        this.teg = teg;
    }

    @Override
    public void repair(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {
        // Trabajamos sobre 's' (que en ALNS es 'nuevaSol').
        List<PlanPedido> planes = new ArrayList<>(s.getPlanPorPedido().values());

        for (PlanPedido plan : planes) {
            if (plan.getDemanda() <= 0) continue;

            // Aporte PREVIO del plan por vuelo (para residual ajustado y delta final):
            Map<VueloProgramadoId, Integer> prevByFlight = contribucionPorVuelo(plan);

            // Construir rutas nuevas con packing máximo:
            List<RutaAsignada> nuevas = buildPackingMax(plan, s, prevByFlight);

            // Coalesce por seguridad
            nuevas = combinarRutasIguales(nuevas);

            // DELTAS en el mapa de asignados de la solución NUEVA:
            aplicarDeltasCargaPorVuelo(s, prevByFlight, contribucionPorVuelo(nuevas));

            // Reservas de BODEGA (journal) para esperas origen/destino:
            reservarBodegasDeRutas(journal, plan.getCreadoUtc(), plan.getAeropuertoDestino(), nuevas);

            if (plan.getIdPedido() == 31){
                int a = 0;
            }

            // Reemplazar plan en la solución:
            PlanPedido nuevoPlan = PlanPedido.builder()
                    .idPedido(plan.getIdPedido())
                    .aeropuertoDestino(plan.getAeropuertoDestino())
                    .creadoUtc(plan.getCreadoUtc())
                    .demanda(plan.getDemanda())
                    .rutas(nuevas)
                    .build();
            s.getPlanPorPedido().put(nuevoPlan.getIdPedido(), nuevoPlan);
        }
    }

    // =========================
    // Packing máximo por pedido
    // =========================

    private List<RutaAsignada> buildPackingMax(PlanPedido plan,
                                               SolucionProgramacion s,
                                               Map<VueloProgramadoId, Integer> prevByFlight) {
        final Instant ref = plan.getCreadoUtc();

        // Lo agregado en esta pasada por vuelo (para no sobreasignar localmente):
        Map<VueloProgramadoId, Integer> addedNow = new HashMap<>();

        // Acumulador: camino (lista inmutable de VueloProgramadoId) -> cantidad total
        LinkedHashMap<List<VueloProgramadoId>, Integer> qtyByPath = new LinkedHashMap<>();

        int restante = plan.getDemanda();
        while (restante > 0) {
            List<Vuelo> rutaVuelos = buscarMejorRutaResidual(plan.getAeropuertoDestino(), s, prevByFlight, addedNow, ref);
            if (rutaVuelos == null || rutaVuelos.isEmpty()) break;

            List<VueloProgramadoId> pathIds = rutaVuelos.stream().map(v -> toId(v, ref)).toList();

            int cuello = Integer.MAX_VALUE;
            for (VueloProgramadoId id : pathIds) {
                cuello = Math.min(cuello, residualAjustado(id, s, prevByFlight, addedNow));
            }
            if (cuello <= 0) break;

            int lote = Math.min(restante, cuello);

            qtyByPath.merge(pathIds, lote, Integer::sum);
            for (VueloProgramadoId id : pathIds) addedNow.merge(id, lote, Integer::sum);

            restante -= lote;
        }

        // Materializar rutas:
        List<RutaAsignada> rutas = new ArrayList<>(qtyByPath.size());
        for (var e : qtyByPath.entrySet()) {
            int q = e.getValue();
            List<TramoAsignado> tramos = e.getKey().stream()
                    .map(id -> new TramoAsignado(id, q, id.getLlegadaUtc()))
                    .collect(Collectors.toList());
            rutas.add(new RutaAsignada(q, tramos));
        }
        return rutas;
    }

    // =========================
    // Dijkstra con residual>0
    // =========================

    private List<Vuelo> buscarMejorRutaResidual(String destino,
                                                SolucionProgramacion s,
                                                Map<VueloProgramadoId, Integer> prevByFlight,
                                                Map<VueloProgramadoId, Integer> addedNow,
                                                Instant ref) {
        List<List<Vuelo>> cand = new ArrayList<>();
        for (String sede : sedes) {
            List<Vuelo> path = dijkstraRutaResidual(sede, destino, s, prevByFlight, addedNow, ref);
            if (path != null && !path.isEmpty()) cand.add(path);
        }
        if (cand.isEmpty()) return null;
        cand.sort(Comparator.comparingDouble(this::costoRuta));
        return cand.get(0);
    }

    private List<Vuelo> dijkstraRutaResidual(String origen,
                                             String destino,
                                             SolucionProgramacion s,
                                             Map<VueloProgramadoId, Integer> prevByFlight,
                                             Map<VueloProgramadoId, Integer> addedNow,
                                             Instant ref) {
        Map<String, Double> dist = new HashMap<>();
        Map<String, Vuelo> prev = new HashMap<>();
        PriorityQueue<String> pq = new PriorityQueue<>(Comparator.comparingDouble(n -> dist.getOrDefault(n, Double.POSITIVE_INFINITY)));

        for (String nodo : teg.getVuelosPorOrigen().keySet()) dist.put(nodo, Double.POSITIVE_INFINITY);
        dist.put(origen, 0.0);
        pq.add(origen);

        while (!pq.isEmpty()) {
            String u = pq.poll();
            if (Double.isInfinite(dist.getOrDefault(u, Double.POSITIVE_INFINITY))) continue;
            if (u.equals(destino)) break;

            List<Vuelo> salidas = teg.getVuelosPorOrigen().get(u);
            if (salidas == null) continue;

            for (Vuelo v : salidas) {
                VueloProgramadoId id = toId(v, ref);
                if (residualAjustado(id, s, prevByFlight, addedNow) <= 0) continue;

                double peso = v.getCosto() + v.getHoraGMTDestino().toSecondOfDay() * 0.001;
                double nd = dist.getOrDefault(u, Double.POSITIVE_INFINITY) + peso;
                if (nd < dist.getOrDefault(v.getDestino(), Double.POSITIVE_INFINITY)) {
                    dist.put(v.getDestino(), nd);
                    prev.put(v.getDestino(), v);
                    pq.add(v.getDestino());
                }
            }
        }

        if (!prev.containsKey(destino)) return null;

        List<Vuelo> ruta = new ArrayList<>();
        String w = destino;
        while (prev.containsKey(w)) {
            Vuelo v = prev.get(w);
            ruta.add(v);
            w = v.getOrigen();
        }
        Collections.reverse(ruta);
        return ruta;
    }

    // =========================
    // Bodega (reservas con Journal)
    // =========================

    private void reservarBodegasDeRutas(ALNS.Journal journal,
                                        Instant creadoUtc,
                                        String destinoPedido,
                                        List<RutaAsignada> rutas) {
        if (journal == null || rutas == null) return;

        for (RutaAsignada r : rutas) {
            List<TramoAsignado> tr = r.getTramos();
            if (tr == null || tr.isEmpty()) continue;

            for (int i = 0; i < tr.size(); i++) {
                TramoAsignado t = tr.get(i);
                VueloProgramadoId v = t.getVuelo();
                int q = r.getCantidad();

                // Espera en ORIGEN: [creado o llegada_prev, salida)
                Instant esperaIniOri = (i == 0)
                        ? creadoUtc
                        : tr.get(i - 1).getVuelo().getLlegadaUtc();
                Instant esperaFinOri = v.getSalidaUtc();
                if (esperaIniOri != null && esperaFinOri != null && !esperaFinOri.isBefore(esperaIniOri)) {
                    journal.reservarConNombre(v.getOrigen(), esperaIniOri, esperaFinOri, q, "SplitRepair");
                }

                // Espera en DESTINO:
                // - Si hay siguiente: [llegada, salida_siguiente)
                // - Si es final:      [llegada, llegada + PICKUP_FINAL)
                Instant esperaIniDst = v.getLlegadaUtc();
                Instant esperaFinDst;
                if (i + 1 < tr.size()) {
                    esperaFinDst = tr.get(i + 1).getVuelo().getSalidaUtc();
                } else {
                    esperaFinDst = (esperaIniDst == null) ? null : esperaIniDst.plus(PICKUP_FINAL);
                }
                if (esperaIniDst != null && esperaFinDst != null && !esperaFinDst.isBefore(esperaIniDst)) {
                    journal.reservarConNombre(v.getOrigen(), esperaIniOri, esperaFinOri, q, "SplitRepair");
                }
            }
        }
    }

    // =========================
    // Helpers de carga/vuelos
    // =========================

    /** Residual ajustado = cap − (asignado_global − previo_del_plan + agregado_en_esta_pasada). */
    private int residualAjustado(VueloProgramadoId id,
                                 SolucionProgramacion s,
                                 Map<VueloProgramadoId, Integer> prevByFlight,
                                 Map<VueloProgramadoId, Integer> addedNow) {
        int cap   = s.getCargaPorVuelo().capacidad(id);         // capacidad del vuelo
        int asg   = s.getCargaPorVuelo().asignado(id);          // asignado global actual en NUEVA solución
        int prev  = prevByFlight.getOrDefault(id, 0);           // lo que este plan ya aportaba
        int added = addedNow.getOrDefault(id, 0);               // lo agregado durante esta pasada
        int usadoVirtual = asg - prev + added;
        return Math.max(0, cap - usadoVirtual);
    }

    private VueloProgramadoId toId(Vuelo v, Instant referencia) {
        Instant salidaUtc = v.getHoraGMTOrigen()
                .atDate(referencia.atZone(ZoneOffset.UTC).toLocalDate())
                .toInstant(ZoneOffset.UTC);
        Instant llegadaUtc = v.getHoraGMTDestino()
                .atDate(referencia.atZone(ZoneOffset.UTC).toLocalDate())
                .toInstant(ZoneOffset.UTC);
        return new VueloProgramadoId(v.getOrigen(), v.getDestino(), salidaUtc, llegadaUtc);
    }

    private double costoRuta(List<Vuelo> ruta) {
        return ruta.stream()
                .mapToDouble(v -> v.getCosto() + v.getHoraGMTDestino().toSecondOfDay() * 0.001)
                .sum();
    }

    /** Suma por vuelo del conjunto de rutas dado. */
    private Map<VueloProgramadoId, Integer> contribucionPorVuelo(List<RutaAsignada> rutas) {
        Map<VueloProgramadoId, Integer> acc = new HashMap<>();
        if (rutas == null) return acc;
        for (RutaAsignada r : rutas) {
            int q = r.getCantidad();
            if (r.getTramos() == null) continue;
            for (TramoAsignado t : r.getTramos()) {
                if (t.getVuelo() != null) acc.merge(t.getVuelo(), q, Integer::sum);
            }
        }
        return acc;
    }

    /** Suma por vuelo del plan actual. */
    private Map<VueloProgramadoId, Integer> contribucionPorVuelo(PlanPedido plan) {
        return contribucionPorVuelo(plan.getRutas());
    }

    /** Aplica delta de asignación de vuelos directamente en la solución NUEVA. */
    private void aplicarDeltasCargaPorVuelo(SolucionProgramacion s,
                                            Map<VueloProgramadoId, Integer> prevByFlight,
                                            Map<VueloProgramadoId, Integer> nuevoByFlight) {
        Map<VueloProgramadoId, Integer> asignadoMap = s.getCargaPorVuelo().getAsignado(); // <- mutable
        // delta = nuevo - previo
        Map<VueloProgramadoId, Integer> delta = new HashMap<>();
        for (var e : nuevoByFlight.entrySet()) delta.merge(e.getKey(), e.getValue(), Integer::sum);
        for (var e : prevByFlight.entrySet())  delta.merge(e.getKey(), -e.getValue(), Integer::sum);

        for (var e : delta.entrySet()) {
            VueloProgramadoId id = e.getKey();
            int d = e.getValue();
            if (d == 0) continue;
            asignadoMap.merge(id, d, Integer::sum);
            // sanitizar negativos por si acaso
            if (asignadoMap.get(id) != null && asignadoMap.get(id) < 0) asignadoMap.put(id, 0);
        }
    }

    // =========================
    // Coalesce de rutas
    // =========================

    private List<RutaAsignada> combinarRutasIguales(List<RutaAsignada> rutas) {
        LinkedHashMap<List<VueloProgramadoId>, Integer> acc = new LinkedHashMap<>();

        for (RutaAsignada r : rutas) {
            List<VueloProgramadoId> key = (r.getTramos() == null ? List.<VueloProgramadoId>of()
                    : r.getTramos().stream().map(TramoAsignado::getVuelo).toList());
            acc.merge(key, r.getCantidad(), Integer::sum);
        }

        List<RutaAsignada> result = new ArrayList<>(acc.size());
        for (var e : acc.entrySet()) {
            int q = e.getValue();
            List<TramoAsignado> tramos = e.getKey().stream()
                    .map(id -> new TramoAsignado(id, q, id.getLlegadaUtc()))
                    .collect(Collectors.toList());
            result.add(new RutaAsignada(q, tramos));
        }
        return result;
    }
}
