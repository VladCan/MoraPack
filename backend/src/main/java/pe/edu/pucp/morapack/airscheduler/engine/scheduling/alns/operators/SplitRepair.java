package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Vuelo;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * SplitRepair (packing máximo coherente) mejorado para priorizar SLA y Residual.
 * El Dijkstra incluye filtro SLA y un stop de seguridad contra rutas cíclicas/excesivas.
 */
public class SplitRepair implements RepairOperator {

    private final VuelosTEG teg;
    private final List<String> sedes;

    // 🛑 SEGURIDAD Y CONSTANTES
    private static final Duration SLA_ARRIVAL_LIMIT = Duration.ofHours(46); 
    private static final Duration PICKUP_FINAL = Duration.ofHours(2); 
    private static final double COST_WEIGHT = 1.0; 
    private static final int MAX_NODES_IN_PATH = 500; 

    public SplitRepair(List<String> sedes, VuelosTEG teg) {
        this.sedes = sedes;
        this.teg = teg;
    }

    @Override
    public void repair(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {
        List<PlanPedido> planes = new ArrayList<>(s.getPlanPorPedido().values());

        for (PlanPedido plan : planes) {
            if (plan.getDemanda() <= 0) continue;

            Map<VueloProgramadoId, Integer> prevByFlight = contribucionPorVuelo(plan);
            List<RutaAsignada> nuevas = buildPackingMax(plan, s, prevByFlight);
            nuevas = combinarRutasIguales(nuevas);
            aplicarDeltasCargaPorVuelo(s, prevByFlight, contribucionPorVuelo(nuevas));
            reservarBodegasDeRutas(journal, plan.getCreadoUtc(), plan.getAeropuertoDestino(), nuevas);

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

    // 🛑 buildPackingMax
    private List<RutaAsignada> buildPackingMax(PlanPedido plan,
                                                 SolucionProgramacion s,
                                                 Map<VueloProgramadoId, Integer> prevByFlight) {
        final Instant ref = plan.getCreadoUtc();
        final Instant deadline = ref.plus(SLA_ARRIVAL_LIMIT);

        Map<VueloProgramadoId, Integer> addedNow = new HashMap<>();
        LinkedHashMap<List<VueloProgramadoId>, Integer> qtyByPath = new LinkedHashMap<>();

        int restante = plan.getDemanda();
        while (restante > 0) {
            List<Vuelo> rutaVuelos = buscarMejorRutaResidual(plan.getAeropuertoDestino(), s, prevByFlight, addedNow, ref, deadline);
            
            if (rutaVuelos == null || rutaVuelos.isEmpty()) break;

            // Uso de toList() requiere cast para resolver inferencia de tipo T
            List<VueloProgramadoId> pathIds = rutaVuelos.stream()
                    .map((Function<Vuelo, VueloProgramadoId>) v -> toId(v, ref))
                    .toList();

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
    // DIJKSTRA Y BÚSQUEDA DE RUTA
    // =========================

    private List<Vuelo> buscarMejorRutaResidual(String destino,
                                                 SolucionProgramacion s,
                                                 Map<VueloProgramadoId, Integer> prevByFlight,
                                                 Map<VueloProgramadoId, Integer> addedNow,
                                                 Instant refCreacion,
                                                 Instant deadline) {
        List<List<Vuelo>> cand = new ArrayList<>();
        for (String sede : sedes) {
            List<Vuelo> path = dijkstraRutaResidual(sede, destino, s, prevByFlight, addedNow, refCreacion, deadline);
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
                                             Instant refCreacion,
                                             Instant deadline) {
        
        // El estado de distancia sigue siendo Costo (Double), PERO usamos el tiempo de llegada absoluto
        // (arrivalTimes) para garantizar la factibilidad temporal.
        Map<String, Double> dist = new HashMap<>();
        Map<String, Instant> arrivalTimes = new HashMap<>(); 
        Map<String, Vuelo> prev = new HashMap<>();
        PriorityQueue<String> pq = new PriorityQueue<>(Comparator.comparingDouble(n -> dist.getOrDefault(n, Double.POSITIVE_INFINITY)));

        for (String nodo : teg.getVuelosPorOrigen().keySet()) dist.put(nodo, Double.POSITIVE_INFINITY);
        dist.put(origen, 0.0);
        arrivalTimes.put(origen, refCreacion);
        pq.add(origen);

        while (!pq.isEmpty()) {
            String u = pq.poll();
            if (Double.isInfinite(dist.getOrDefault(u, Double.POSITIVE_INFINITY))) continue;
            if (u.equals(destino)) break;

            List<Vuelo> salidas = teg.getVuelosPorOrigen().get(u);
            if (salidas == null) continue;

            // Tiempo de llegada al nodo 'u' (base para la salida del nuevo vuelo)
            Instant lastArrivalTime = arrivalTimes.get(u);
            
            for (Vuelo v : salidas) {
                VueloProgramadoId id = toId(v, refCreacion);
                
                // 1. FILTRO DE FACTIBILIDAD TEMPORAL (CRÍTICO)
                // Debe haber un tiempo mínimo de manejo/layover (60s)
                if (id.getSalidaUtc().isBefore(lastArrivalTime.plusSeconds(60))) continue; 

                // 2. Filtro de Residual y SLA
                if (residualAjustado(id, s, prevByFlight, addedNow) <= 0) continue;
                if (id.getLlegadaUtc().isAfter(deadline)) continue; // 🛑 Filtro SLA

                // 3. Cálculo de peso: Usamos el costo logístico del VueloProgramadoId
                double costoLogistico = id.getCosto(); 
                double peso = COST_WEIGHT * costoLogistico; 

                // 4. Actualizar distancia acumulada
                double nd = dist.getOrDefault(u, Double.POSITIVE_INFINITY) + peso;

                if (nd < dist.getOrDefault(v.getDestino(), Double.POSITIVE_INFINITY)) {
                    dist.put(v.getDestino(), nd);
                    // 🛑 ACTULIZACIÓN CRÍTICA: Almacenar también el tiempo de llegada absoluto
                    arrivalTimes.put(v.getDestino(), id.getLlegadaUtc()); 
                    
                    prev.put(v.getDestino(), v);
                    pq.add(v.getDestino());
                }
            }
        }

        if (!prev.containsKey(destino)) return null;

        List<Vuelo> ruta = new ArrayList<>();
        String w = destino;
        int safetyCounter = 0;
        
        // 🛑 RECONSTRUCCIÓN CON STOP DE SEGURIDAD (PREVIENE OOM)
        while (prev.containsKey(w)) {
            if (safetyCounter++ > MAX_NODES_IN_PATH) {
                System.err.println("ALERTA: Se alcanzó el límite de tramos en Dijkstra. Posible bucle o ruta excesivamente larga.");
                return null; 
            }
            Vuelo v = prev.get(w);
            ruta.add(v);
            w = v.getOrigen();
        }
        Collections.reverse(ruta);
        return ruta;
    }


    // =========================
    // MÉTODOS AUXILIARES
    // =========================

    private VueloProgramadoId toId(Vuelo v, Instant referencia) {
        Instant salidaUtc = v.getHoraGMTOrigen()
                .atDate(referencia.atZone(ZoneOffset.UTC).toLocalDate())
                .toInstant(ZoneOffset.UTC);
        Instant llegadaUtc = v.getHoraGMTDestino()
                .atDate(referencia.atZone(ZoneOffset.UTC).toLocalDate())
                .toInstant(ZoneOffset.UTC);
        return new VueloProgramadoId(v.getOrigen(), v.getDestino(), salidaUtc, llegadaUtc);
    }
    
    private int residualAjustado(VueloProgramadoId id,
                                 SolucionProgramacion s,
                                 Map<VueloProgramadoId, Integer> prevByFlight,
                                 Map<VueloProgramadoId, Integer> addedNow) {
        int cap = s.getCargaPorVuelo().capacidad(id); 
        int asg = s.getCargaPorVuelo().asignado(id); 
        int prev = prevByFlight.getOrDefault(id, 0); 
        int added = addedNow.getOrDefault(id, 0); 
        int usadoVirtual = asg - prev + added;
        return Math.max(0, cap - usadoVirtual);
    }
    
    private double costoRuta(List<Vuelo> ruta) {
        return ruta.stream()
                .mapToDouble(Vuelo::getCosto)
                .sum();
    }

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

    private Map<VueloProgramadoId, Integer> contribucionPorVuelo(PlanPedido plan) {
        return contribucionPorVuelo(plan.getRutas());
    }

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

    private void aplicarDeltasCargaPorVuelo(SolucionProgramacion s,
                                             Map<VueloProgramadoId, Integer> prevByFlight,
                                             Map<VueloProgramadoId, Integer> nuevoByFlight) {
        Map<VueloProgramadoId, Integer> asignadoMap = s.getCargaPorVuelo().getAsignado();
        Map<VueloProgramadoId, Integer> delta = new HashMap<>();
        for (var e : nuevoByFlight.entrySet()) delta.merge(e.getKey(), e.getValue(), Integer::sum);
        for (var e : prevByFlight.entrySet()) delta.merge(e.getKey(), -e.getValue(), Integer::sum);

        for (var e : delta.entrySet()) {
            VueloProgramadoId id = e.getKey();
            int d = e.getValue();
            if (d == 0) continue;
            asignadoMap.merge(id, d, Integer::sum);
            if (asignadoMap.get(id) != null && asignadoMap.get(id) < 0) asignadoMap.put(id, 0);
        }
    }

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
                    journal.reservar(v.getOrigen(), esperaIniOri, esperaFinOri, q);
                }

                // Espera en DESTINO: [llegada, llegada + PICKUP_FINAL)
                Instant esperaIniDst = v.getLlegadaUtc();
                Instant esperaFinDst;
                if (i + 1 < tr.size()) {
                    esperaFinDst = tr.get(i + 1).getVuelo().getSalidaUtc();
                } else {
                    esperaFinDst = (esperaIniDst == null) ? null : esperaIniDst.plus(PICKUP_FINAL);
                }
                if (esperaIniDst != null && esperaFinDst != null && !esperaFinDst.isBefore(esperaIniDst)) {
                    journal.reservar(v.getDestino(), esperaIniDst, esperaFinDst, q);
                }
            }
        }
    }
}