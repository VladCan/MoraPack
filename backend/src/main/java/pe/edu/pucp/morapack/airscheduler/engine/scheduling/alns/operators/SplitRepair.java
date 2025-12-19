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

/**
 * SplitRepair Optimizado (Relaxed Constraints):
 * - Busca rutas aunque violen SLA, para evitar dejar pedidos sin asignar.
 */
public class SplitRepair implements RepairOperator {

    private final List<String> sedes;
    private final IndexVuelos indexVuelos;

    private static final Duration PICKUP_FINAL = Duration.ofHours(2);
    private static final int MAX_NODES_IN_PATH = 4;
    private static final int MAX_SPLITS_PER_ORDER = 10; 

    public SplitRepair(List<String> sedes, VuelosTEG teg) {
        this.sedes = sedes;
        this.indexVuelos = new IndexVuelos(teg);
    }

    @Override
    public void repair(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {
        List<PlanPedido> planes = new ArrayList<>(s.getPlanPorPedido().values());
        Collections.shuffle(planes, new Random());

        for (PlanPedido plan : planes) {
            if (plan.estaCompleto()) continue;

            Map<VueloProgramadoId, Integer> prevByFlight = contribucionPorVuelo(plan);
            
            List<RutaAsignada> nuevas = buildPackingMax(plan, s, prevByFlight);
            
            if (nuevas.isEmpty()) continue;

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

    private List<RutaAsignada> buildPackingMax(PlanPedido plan,
                                               SolucionProgramacion s,
                                               Map<VueloProgramadoId, Integer> prevByFlight) {
        final Instant ref = plan.getCreadoUtc();
        
        Map<VueloProgramadoId, Integer> addedNow = new HashMap<>();
        LinkedHashMap<List<VueloProgramadoId>, Integer> qtyByPath = new LinkedHashMap<>();

        int restante = plan.getDemanda(); 
        int intentos = 0;

        while (restante > 0) {
            if (intentos >= MAX_SPLITS_PER_ORDER) break;
            intentos++;

            // Búsqueda relajada (sin deadline estricto)
            List<VueloProgramadoId> pathIds = buscarMejorRutaTimeAttack(plan.getAeropuertoDestino(), s, prevByFlight, addedNow, ref);

            if (pathIds == null || pathIds.isEmpty()) break;

            int cuello = Integer.MAX_VALUE;
            for (VueloProgramadoId id : pathIds) {
                cuello = Math.min(cuello, residualAjustado(id, s, prevByFlight, addedNow));
            }

            if (cuello <=  0) break;

            int lote = Math.min(restante, cuello);
            qtyByPath.merge(pathIds, lote, Integer::sum);
            for (VueloProgramadoId id : pathIds) addedNow.merge(id, lote, Integer::sum);

            restante -= lote;
        }

        List<RutaAsignada> rutas = new ArrayList<>(qtyByPath.size());
        for (var e : qtyByPath.entrySet()) {
            int q = e.getValue();
            if (e.getKey() == null || e.getKey().isEmpty()) continue;

            List<TramoAsignado> tramos = e.getKey().stream()
                    .map(id -> new TramoAsignado(id, q, id.getLlegadaUtc()))
                    .collect(Collectors.toList());
            
            rutas.add(new RutaAsignada(q, tramos));
        }
        return rutas;
    }

    private record State(String id, Instant llegada) implements Comparable<State> {
        @Override public int compareTo(State o) { return this.llegada.compareTo(o.llegada); }
    }

    private List<VueloProgramadoId> buscarMejorRutaTimeAttack(String destino,
                                                SolucionProgramacion s,
                                                Map<VueloProgramadoId, Integer> prevByFlight,
                                                Map<VueloProgramadoId, Integer> addedNow,
                                                Instant refCreacion) {
        List<List<VueloProgramadoId>> candidatos = new ArrayList<>();
        
        for (String sede : sedes) {
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

    private List<VueloProgramadoId> dijkstraTimeBased(String origen, String destino, SolucionProgramacion s,
                                             Map<VueloProgramadoId, Integer> prevByFlight,
                                             Map<VueloProgramadoId, Integer> addedNow,
                                             Instant refCreacion) {

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
            
            if (u.equals(destino)) break; 

            if (d >= MAX_NODES_IN_PATH) continue;

            List<VueloFicha> salidas = indexVuelos.porOrigen(u);
            if (salidas == null) continue;

            for (VueloFicha vf : salidas) {
                VueloProgramadoId id = vf.id();
                Instant salidaUtc = id.getSalidaUtc();
                Instant llegadaUtc = id.getLlegadaUtc();

                if (salidaUtc.isBefore(current.llegada.plusSeconds(60))) continue;
                
                // 🛑 REMOVIDO: Filtro SLA estricto.
                
                if (residualAjustado(id, s, prevByFlight, addedNow) <= 0) continue;

                if (llegadaUtc.isBefore(bestArrival.getOrDefault(id.getDestino(), Instant.MAX))) {
                    bestArrival.put(id.getDestino(), llegadaUtc);
                    depth.put(id.getDestino(), d + 1);
                    prevVuelo.put(id.getDestino(), id);
                    prevNodo.put(id.getDestino(), u);
                    
                    pq.add(new State(id.getDestino(), llegadaUtc));
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

    // ... (Resto de métodos auxiliares: residualAjustado, contribucionPorVuelo, etc. se mantienen IGUALES)
    // Incluye los métodos auxiliares del archivo anterior aquí abajo para que compile.
    private int residualAjustado(VueloProgramadoId id, SolucionProgramacion s, Map<VueloProgramadoId, Integer> prevByFlight, Map<VueloProgramadoId, Integer> addedNow) {
        int cap = s.getCargaPorVuelo().capacidad(id); int asg = s.getCargaPorVuelo().asignado(id);
        int prev = prevByFlight.getOrDefault(id, 0); int added = addedNow.getOrDefault(id, 0);
        return Math.max(0, cap - (asg - prev + added));
    }
    private Map<VueloProgramadoId, Integer> contribucionPorVuelo(PlanPedido plan) { return contribucionPorVuelo(plan.getRutas()); }
    private Map<VueloProgramadoId, Integer> contribucionPorVuelo(List<RutaAsignada> rutas) {
        Map<VueloProgramadoId, Integer> acc = new HashMap<>();
        if (rutas == null) return acc;
        for (RutaAsignada r : rutas) {
            if (r.getTramos() != null) for (TramoAsignado t : r.getTramos()) acc.merge(t.getVuelo(), r.getCantidad(), Integer::sum);
        }
        return acc;
    }
    private List<RutaAsignada> combinarRutasIguales(List<RutaAsignada> rutas) {
        LinkedHashMap<List<VueloProgramadoId>, Integer> acc = new LinkedHashMap<>();
        for (RutaAsignada r : rutas) {
            List<VueloProgramadoId> key = (r.getTramos() == null ? List.of() : r.getTramos().stream().map(TramoAsignado::getVuelo).toList());
            acc.merge(key, r.getCantidad(), Integer::sum);
        }
        List<RutaAsignada> res = new ArrayList<>();
        for (var e : acc.entrySet()) {
            int q = e.getValue();
            List<TramoAsignado> tramos = e.getKey().stream().map(id -> new TramoAsignado(id, q, id.getLlegadaUtc())).collect(Collectors.toList());
            res.add(new RutaAsignada(q, tramos));
        }
        return res;
    }
    private void aplicarDeltasCargaPorVuelo(SolucionProgramacion s, Map<VueloProgramadoId, Integer> prev, Map<VueloProgramadoId, Integer> nuevo) {
        Map<VueloProgramadoId, Integer> map = s.getCargaPorVuelo().getAsignado();
        Map<VueloProgramadoId, Integer> delta = new HashMap<>(nuevo);
        prev.forEach((k, v) -> delta.merge(k, -v, Integer::sum));
        delta.forEach((k, v) -> { if (v != 0) map.merge(k, v, Integer::sum); });
    }
    private void reservarBodegasDeRutas(ALNS.Journal journal,
                                        Instant creadoUtc,
                                        String dst, // realmente no lo usas, pero lo dejo por firma
                                        List<RutaAsignada> rutas) {
        if (journal == null || rutas == null) return;

        for (RutaAsignada r : rutas) {
            int q = r.getCantidad();
            List<TramoAsignado> tr = r.getTramos();
            if (tr == null || tr.isEmpty()) continue;

            // Igual que en RegretRepair.reservarRecursos
            VueloProgramadoId primerVuelo = tr.get(0).getVuelo();
            String aeropuertoActual = primerVuelo.getOrigen();
            Instant tiempoActual = creadoUtc;

            for (TramoAsignado tramo : tr) {
                VueloProgramadoId v = tramo.getVuelo();
                Instant salida = v.getSalidaUtc();
                Instant llegada = v.getLlegadaUtc();

                // Reserva en el aeropuerto actual (solo si NO es sede)
                if (!sedes.contains(aeropuertoActual)) {
                    if (tiempoActual != null && salida != null && !salida.isBefore(tiempoActual)) {
                        journal.reservar(aeropuertoActual, tiempoActual, salida, q);
                    }
                }

                // Avanzamos al aeropuerto destino del vuelo
                aeropuertoActual = v.getDestino();
                tiempoActual = llegada;
            }

            // Destino final: llegada → llegada + PICKUP_FINAL (si no es sede)
            if (!sedes.contains(aeropuertoActual) && tiempoActual != null) {
                Instant finDestino = tiempoActual.plus(PICKUP_FINAL);
                journal.reservar(aeropuertoActual, tiempoActual, finDestino, q);
            }
        }
    }

}