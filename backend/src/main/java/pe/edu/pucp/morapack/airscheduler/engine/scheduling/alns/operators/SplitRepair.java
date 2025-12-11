package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.service.IndexVuelos;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.service.VueloFicha;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class SplitRepair implements RepairOperator {

    private final List<String> sedes;
    private final IndexVuelos indexVuelos;

    private static final Duration PICKUP_FINAL = Duration.ofHours(2);
    private static final int MAX_NODES_IN_PATH = 4;
    private static final int MAX_SPLITS_PER_ORDER = 10;
    private static final int MAX_CAPACIDAD_SEGURA = 100000;
    
    // RF5
    private static final Set<String> HUBS = Set.of("SPIM", "EBCI", "UBBB");
    // RF1
    private static final long MAX_SLA_HOURS = 46;

    public SplitRepair(List<String> sedes, VuelosTEG teg) {
        this.sedes = sedes;
        this.indexVuelos = new IndexVuelos(teg);
    }

    @Override
    public void repair(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {
        List<PlanPedido> planes = new ArrayList<>(s.getPlanPorPedido().values());
        Collections.shuffle(planes, new Random());

        for (PlanPedido plan : planes) {
            
            if (plan.getDemanda() <= 0) continue; 
            if (plan.getDemanda() > MAX_CAPACIDAD_SEGURA) continue; 
            
            if (plan.estaCompleto()) continue;

            Map<VueloProgramadoId, Integer> prevByFlight = contribucionPorVuelo(plan);
            
            // Genera una lista de rutas propuestas (Respetando RF4 - Vuelos)
            List<RutaAsignada> nuevas = buildPackingMax(plan, s, prevByFlight);
            
            if (nuevas.isEmpty()) continue;
            nuevas = combinarRutasIguales(nuevas);
            nuevas.removeIf(r -> r.getCantidad() <= 0);

            if (nuevas.isEmpty()) continue;

            // RF3: VERIFICACIÓN ESTRICTA
            // Si el split propuesto no cabe en los almacenes, lo descartamos.
            // No podemos permitir una solución inválida.
            if (verificarCapacidadAlmacenes(journal, plan, nuevas)) {
                
                aplicarDeltasCargaPorVuelo(s, prevByFlight, contribucionPorVuelo(nuevas));
                reservarBodegasDeRutas(journal, plan.getCreadoUtc(), plan.getAeropuertoDestino(), nuevas);

                List<RutaAsignada> listaFinal = new ArrayList<>(nuevas);

                PlanPedido nuevoPlan = PlanPedido.builder()
                        .idPedido(plan.getIdPedido())
                        .aeropuertoDestino(plan.getAeropuertoDestino())
                        .creadoUtc(plan.getCreadoUtc())
                        .demanda(plan.getDemanda())
                        .rutas(listaFinal)
                        .build();
                s.getPlanPorPedido().put(nuevoPlan.getIdPedido(), nuevoPlan);
            }
        }
    }

    private boolean verificarCapacidadAlmacenes(ALNS.Journal journal, PlanPedido plan, List<RutaAsignada> rutas) {
        OcupacionPorAeropuerto occ = journal.getOcc(); 

        for (RutaAsignada r : rutas) {
            int q = r.getCantidad();
            List<TramoAsignado> tramos = r.getTramos();
            if (tramos == null || tramos.isEmpty()) continue;

            for (int i = 0; i < tramos.size(); i++) {
                TramoAsignado t = tramos.get(i); 
                VueloProgramadoId v = t.getVuelo(); 

                // 1. Origen
                if (!HUBS.contains(v.getOrigen())) {
                    Instant iniOri = (i == 0) ? plan.getCreadoUtc() : tramos.get(i - 1).getVuelo().getLlegadaUtc();
                    if (iniOri != null && v.getSalidaUtc() != null && v.getSalidaUtc().isAfter(iniOri)) {
                        int maxCap = occ.maxReservable(v.getOrigen(), iniOri, v.getSalidaUtc());
                        if (q > maxCap) return false;
                    }
                }

                // 2. Destino
                if (!HUBS.contains(v.getDestino())) {
                    Instant finDst = (i + 1 < tramos.size()) 
                        ? tramos.get(i + 1).getVuelo().getSalidaUtc() 
                        : v.getLlegadaUtc().plus(PICKUP_FINAL); // RF2
                    
                    if (v.getLlegadaUtc() != null && finDst != null && finDst.isAfter(v.getLlegadaUtc())) {
                        int maxCap = occ.maxReservable(v.getDestino(), v.getLlegadaUtc(), finDst);
                        if (q > maxCap) return false;
                    }
                }
            }
        }
        return true;
    }

    // ... (buildPackingMax, buscarMejorRutaTimeAttack, dijkstraTimeBased con checks RF1 y RF5) ...
    // Asegúrate de copiar el resto de métodos (son largos, pero usan la misma lógica que RegretRepair para RF1/RF5)
    // El Dijkstra dentro de SplitRepair DEBE tener también el chequeo de HUBS y SLA que puse en RegretRepair.
    // Te lo incluyo aquí completo para evitar errores:

    private List<RutaAsignada> buildPackingMax(PlanPedido plan, SolucionProgramacion s, Map<VueloProgramadoId, Integer> prevByFlight) {
        final Instant ref = plan.getCreadoUtc();
        Map<VueloProgramadoId, Integer> addedNow = new HashMap<>();
        LinkedHashMap<List<VueloProgramadoId>, Integer> qtyByPath = new LinkedHashMap<>();
        int restante = plan.getDemanda(); 
        int intentos = 0;
        while (restante > 0) {
            if (intentos >= MAX_SPLITS_PER_ORDER) break;
            intentos++;
            List<VueloProgramadoId> pathIds = buscarMejorRutaTimeAttack(plan.getAeropuertoDestino(), s, prevByFlight, addedNow, ref);
            if (pathIds == null || pathIds.isEmpty()) break;
            int cuello = Integer.MAX_VALUE;
            for (VueloProgramadoId id : pathIds) {
                cuello = Math.min(cuello, residualAjustado(id, s, prevByFlight, addedNow));
            }
            if (cuello <= 0) break;
            int lote = Math.min(restante, cuello);
            if (lote <= 0) break; 
            qtyByPath.merge(pathIds, lote, Integer::sum);
            for (VueloProgramadoId id : pathIds) addedNow.merge(id, lote, Integer::sum);
            restante -= lote;
        }
        List<RutaAsignada> rutas = new ArrayList<>();
        for (var e : qtyByPath.entrySet()) {
            int q = e.getValue();
            if (q <= 0) continue;
            List<TramoAsignado> tramos = e.getKey().stream().map(id -> new TramoAsignado(id, q, id.getLlegadaUtc())).collect(Collectors.toList());
            rutas.add(new RutaAsignada(q, tramos));
        }
        return rutas;
    }

    private record State(String id, Instant llegada) implements Comparable<State> {
        @Override public int compareTo(State o) { return this.llegada.compareTo(o.llegada); }
    }

    private List<VueloProgramadoId> buscarMejorRutaTimeAttack(String destino, SolucionProgramacion s, Map<VueloProgramadoId, Integer> prevByFlight, Map<VueloProgramadoId, Integer> addedNow, Instant refCreacion) {
        List<List<VueloProgramadoId>> candidatos = new ArrayList<>();
        for (String sede : sedes) {
            if (sede.equals(destino)) continue;
            List<VueloProgramadoId> path = dijkstraTimeBased(sede, destino, s, prevByFlight, addedNow, refCreacion);
            if (path != null && !path.isEmpty()) candidatos.add(path);
        }
        if (candidatos.isEmpty()) return null;
        candidatos.sort((p1, p2) -> {
            Instant t1 = p1.get(p1.size() - 1).getLlegadaUtc();
            Instant t2 = p2.get(p2.size() - 1).getLlegadaUtc();
            return t1.compareTo(t2);
        });
        return candidatos.get(0);
    }

    private List<VueloProgramadoId> dijkstraTimeBased(String origen, String destino, SolucionProgramacion s, Map<VueloProgramadoId, Integer> prevByFlight, Map<VueloProgramadoId, Integer> addedNow, Instant refCreacion) {
        if (origen.equals(destino)) return null;
        PriorityQueue<State> pq = new PriorityQueue<>();
        Map<String, Instant> bestArrival = new HashMap<>();
        Map<String, VueloProgramadoId> prevVuelo = new HashMap<>(); 
        Map<String, String> prevNodo = new HashMap<>();
        pq.add(new State(origen, refCreacion));
        bestArrival.put(origen, refCreacion);
        Map<String, Integer> depth = new HashMap<>();
        depth.put(origen, 0);

        while (!pq.isEmpty()) {
            State current = pq.poll();
            String u = current.id();
            int d = depth.getOrDefault(u, 0);
            if (current.llegada.isAfter(bestArrival.getOrDefault(u, Instant.MAX))) continue;
            
            // RF1: SLA Check
            long horasTotal = Duration.between(refCreacion, current.llegada).toHours();
            if (horasTotal > MAX_SLA_HOURS) continue;

            if (u.equals(destino)) break; 
            if (d >= MAX_NODES_IN_PATH) continue;

            // RF5: Hub Check
            if (HUBS.contains(u) && !u.equals(origen)) continue;

            List<VueloFicha> salidas = indexVuelos.porOrigen(u);
            if (salidas == null) continue;
            for (VueloFicha vf : salidas) {
                VueloProgramadoId id = vf.id();
                Instant salidaUtc = id.getSalidaUtc();
                Instant llegadaUtc = id.getLlegadaUtc();
                String nextAp = id.getDestino();

                // RF5 Check en el siguiente nodo también
                if (HUBS.contains(nextAp) && !nextAp.equals(destino)) continue;

                if (salidaUtc.isBefore(current.llegada.plusSeconds(3600))) continue; 
                if (residualAjustado(id, s, prevByFlight, addedNow) <= 0) continue;

                if (llegadaUtc.isBefore(bestArrival.getOrDefault(nextAp, Instant.MAX))) {
                    bestArrival.put(nextAp, llegadaUtc);
                    depth.put(nextAp, d + 1);
                    prevVuelo.put(nextAp, id);
                    prevNodo.put(nextAp, u);
                    pq.add(new State(nextAp, llegadaUtc));
                }
            }
        }
        if (!prevVuelo.containsKey(destino)) return null;
        List<VueloProgramadoId> ruta = new ArrayList<>();
        String curr = destino;
        while (curr != null && !curr.equals(origen)) {
            VueloProgramadoId v = prevVuelo.get(curr);
            if (v == null) break;
            ruta.add(v);
            curr = prevNodo.get(curr);
        }
        if (ruta.isEmpty()) return null;
        Collections.reverse(ruta);
        return ruta;
    }

    private int residualAjustado(VueloProgramadoId id, SolucionProgramacion s, Map<VueloProgramadoId, Integer> prevByFlight, Map<VueloProgramadoId, Integer> addedNow) {
        int cap = s.getCargaPorVuelo().capacidad(id);
        int asg = s.getCargaPorVuelo().asignado(id);
        int prev = prevByFlight.getOrDefault(id, 0);
        int added = addedNow.getOrDefault(id, 0);
        int ocupadoVirtual = (asg - prev) + added;
        return Math.max(0, cap - ocupadoVirtual);
    }
    private Map<VueloProgramadoId, Integer> contribucionPorVuelo(PlanPedido plan) { return contribucionPorVuelo(plan.getRutas()); }
    private Map<VueloProgramadoId, Integer> contribucionPorVuelo(List<RutaAsignada> rutas) {
        Map<VueloProgramadoId, Integer> acc = new HashMap<>();
        if (rutas == null) return acc;
        for (RutaAsignada r : rutas) {
            if (r.getCantidad() <= 0) continue; 
            if (r.getTramos() != null) {
                for (TramoAsignado t : r.getTramos()) { acc.merge(t.getVuelo(), r.getCantidad(), Integer::sum); }
            }
        }
        return acc;
    }
    private List<RutaAsignada> combinarRutasIguales(List<RutaAsignada> rutas) {
        LinkedHashMap<List<VueloProgramadoId>, Integer> acc = new LinkedHashMap<>();
        for (RutaAsignada r : rutas) {
            if (r.getCantidad() <= 0) continue;
            List<VueloProgramadoId> key = (r.getTramos() == null ? List.of() : r.getTramos().stream().map(TramoAsignado::getVuelo).toList());
            if (key.isEmpty()) continue;
            acc.merge(key, r.getCantidad(), Integer::sum);
        }
        List<RutaAsignada> res = new ArrayList<>();
        for (var e : acc.entrySet()) {
            int q = e.getValue();
            if (q <= 0) continue;
            List<TramoAsignado> tramos = e.getKey().stream().map(id -> new TramoAsignado(id, q, id.getLlegadaUtc())).collect(Collectors.toList());
            res.add(new RutaAsignada(q, tramos));
        }
        return res;
    }
    private void aplicarDeltasCargaPorVuelo(SolucionProgramacion s, Map<VueloProgramadoId, Integer> prev, Map<VueloProgramadoId, Integer> nuevo) {
        Map<VueloProgramadoId, Integer> map = s.getCargaPorVuelo().getAsignado();
        Map<VueloProgramadoId, Integer> delta = new HashMap<>(nuevo);
        prev.forEach((k, v) -> delta.merge(k, -v, Integer::sum));
        delta.forEach((k, v) -> { if (v != 0) { map.merge(k, v, Integer::sum); if (map.get(k) < 0) map.put(k, 0); } });
    }
    private void reservarBodegasDeRutas(ALNS.Journal journal, Instant cr, String dst, List<RutaAsignada> rutas) {
        if (journal == null || rutas == null) return;
        for (RutaAsignada r : rutas) {
            List<TramoAsignado> tr = r.getTramos();
            int q = r.getCantidad();
            if (q <= 0) continue;
            for (int i = 0; i < tr.size(); i++) {
                TramoAsignado t = tr.get(i); 
                VueloProgramadoId v = t.getVuelo(); 
                if (!HUBS.contains(v.getOrigen())) {
                    Instant ini = (i == 0) ? cr : tr.get(i - 1).getVuelo().getLlegadaUtc();
                    if (ini != null && v.getSalidaUtc() != null && v.getSalidaUtc().isAfter(ini)) {
                        journal.reservar(v.getOrigen(), ini, v.getSalidaUtc(), q);
                    }
                }
                if (!HUBS.contains(v.getDestino())) {
                    Instant fin = (i + 1 < tr.size()) ? tr.get(i + 1).getVuelo().getSalidaUtc() : v.getLlegadaUtc().plus(PICKUP_FINAL);
                    if (v.getLlegadaUtc() != null && fin != null && fin.isAfter(v.getLlegadaUtc())) {
                        journal.reservar(v.getDestino(), v.getLlegadaUtc(), fin, q);
                    }
                }
            }
        }
    }
}