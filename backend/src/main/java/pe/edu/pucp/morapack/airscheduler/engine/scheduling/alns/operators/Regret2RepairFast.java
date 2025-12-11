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
 * HYBRID SPLIT REPAIR - OPTIMIZADO PARA ALMACENAMIENTO (RF3, RF6)
 * Prioriza conexiones cortas para liberar espacio en aeropuertos.
 */
public class Regret2RepairFast implements RepairOperator {

    private final IndexVuelos indexVuelos;
    private final List<String> sedesCandidatas;
    
    private static final int MAX_HOPS = 3;
    private static final Set<String> HUBS = Set.of("SPIM", "EBCI", "UBBB"); // RF5: Sedes infinitas
    private static final long MAX_SLA_HOURS = 46; // RF1
    
    private static final long MIN_CONNECTION_HOURS = 1;
    private static final long MAX_CONNECTION_HOURS = 6; 
    
    private static final int MAX_DIJKSTRA_NODES = 1000;

    public Regret2RepairFast(List<String> sedes, VuelosTEG teg) {
        this.sedesCandidatas = new ArrayList<>(sedes);
        this.indexVuelos = new IndexVuelos(teg);
    }

    @Override
    public void repair(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {
        List<PlanPedido> unassigned = s.getPlanPorPedido().values().stream()
                .filter(p -> p.getDemanda() > 0 && esInvalido(p))
                .collect(Collectors.toList());

        if (unassigned.isEmpty()) return;

        unassigned.sort((a, b) -> Integer.compare(b.getDemanda(), a.getDemanda()));

        Map<VueloProgramadoId, Integer> localFlightLoad = new HashMap<>();

        for (PlanPedido plan : unassigned) {
            repararPedido(plan, s, journal, presenteUTC, localFlightLoad);
        }
    }

    private void repararPedido(PlanPedido plan, SolucionProgramacion s, ALNS.Journal journal, 
                               Instant presenteUTC, Map<VueloProgramadoId, Integer> localFlightLoad) {
        
        int demandaRestante = plan.getDemanda();
        List<RutaAsignada> nuevasRutas = new ArrayList<>();
        int intentos = 0;

        while (demandaRestante > 0 && intentos < 10) {
            intentos++;

            RutaCandidata mejorCandidata = null;
            double mejorCosto = Double.MAX_VALUE;

            for (String origen : sedesCandidatas) {
                RutaCandidata candidata = dijkstraSplit(origen, plan, presenteUTC, s.getCargaPorVuelo(), localFlightLoad, journal);
                
                if (candidata != null) {
                    if (candidata.costoPonderado < mejorCosto) {
                        mejorCosto = candidata.costoPonderado;
                        mejorCandidata = candidata;
                    }
                }
            }

            if (mejorCandidata == null) break; 

            int capacidadRuta = calcularCapacidadReal(mejorCandidata.tramosIds, s, journal, localFlightLoad);
            int aEnviar = Math.min(demandaRestante, capacidadRuta);
            
            if (aEnviar <= 0) break;

            List<TramoAsignado> tramosFinales = new ArrayList<>();
            for (VueloProgramadoId vid : mejorCandidata.tramosIds) {
                tramosFinales.add(new TramoAsignado(vid, aEnviar, vid.getLlegadaUtc()));
                localFlightLoad.merge(vid, aEnviar, Integer::sum);
            }
            
            ejecutarReservas(journal, plan, tramosFinales, aEnviar);
            
            for (VueloProgramadoId vid : mejorCandidata.tramosIds) {
                s.getCargaPorVuelo().asignar(vid, aEnviar);
            }

            nuevasRutas.add(new RutaAsignada(aEnviar, tramosFinales));
            demandaRestante -= aEnviar;
        }

        if (!nuevasRutas.isEmpty()) {
            actualizarSolucion(s, plan, nuevasRutas);
        }
    }

    private RutaCandidata dijkstraSplit(String origen, PlanPedido plan, Instant presenteUTC, 
                                        CargaPorVuelo cargaGlobal, Map<VueloProgramadoId, Integer> cargaLocal,
                                        ALNS.Journal journal) {
        String destino = plan.getAeropuertoDestino();
        if (origen.equals(destino)) return null;

        PriorityQueue<Node> pq = new PriorityQueue<>();
        Map<String, Double> bestCost = new HashMap<>();

        Instant startTime = plan.getCreadoUtc().isAfter(presenteUTC) ? plan.getCreadoUtc() : presenteUTC;
        
        pq.add(new Node(origen, null, null, 0, startTime, 0.0));
        bestCost.put(origen, 0.0);

        Node mejorNodo = null;
        int nodes = 0;

        while (!pq.isEmpty()) {
            Node current = pq.poll();
            nodes++;
            if (nodes > MAX_DIJKSTRA_NODES) break;

            if (current.costoAcumulado > bestCost.getOrDefault(current.ap, Double.MAX_VALUE)) continue;

            if (current.ap.equals(destino)) {
                mejorNodo = current;
                break; 
            }

            if (current.hops >= MAX_HOPS) continue;

            // --- CORRECCIÓN DE CONCURRENCIA ---
            List<VueloFicha> rawSalidas = indexVuelos.porOrigen(current.ap);
            if (rawSalidas == null || rawSalidas.isEmpty()) continue;
            
            // NO necesitamos ordenar aquí porque Dijkstra explora nodos, no lista de aristas ordenada.
            // Pero si necesitamos iterar sin miedo a modificaciones externas (aunque IndexVuelos suele ser read-only).
            // Para seguridad extrema, iteramos sobre la lista directa si es inmutable, o copia si hay riesgo.
            // Dado que IndexVuelos es estático, iteramos directamente PERO sin ordenar.
            
            for (VueloFicha vf : rawSalidas) {
                VueloProgramadoId id = vf.id();
                
                int ocupadoGlobal = cargaGlobal.asignado(id);
                int ocupadoLocal = cargaLocal.getOrDefault(id, 0);
                if ((cargaGlobal.capacidad(id) - (ocupadoGlobal + ocupadoLocal)) < 1) continue;

                Instant salidaUtc = id.getSalidaUtc();
                long waitSeconds = Duration.between(current.llegada, salidaUtc).getSeconds();
                
                if (waitSeconds < MIN_CONNECTION_HOURS * 3600) continue; 
                if (waitSeconds > MAX_CONNECTION_HOURS * 3600) continue; 
                
                if (Duration.between(plan.getCreadoUtc(), id.getLlegadaUtc()).toHours() > MAX_SLA_HOURS) continue;

                String nextAp = id.getDestino();
                if (HUBS.contains(nextAp) && !nextAp.equals(destino)) continue;

                double flightDuration = Duration.between(salidaUtc, id.getLlegadaUtc()).toMinutes();
                double waitPenalty = (waitSeconds / 60.0) * 2.5; 
                double nuevoCosto = current.costoAcumulado + flightDuration + waitPenalty;

                if (nuevoCosto < bestCost.getOrDefault(nextAp, Double.MAX_VALUE)) {
                    bestCost.put(nextAp, nuevoCosto);
                    pq.add(new Node(nextAp, id, current, current.hops + 1, id.getLlegadaUtc(), nuevoCosto));
                }
            }
        }

        if (mejorNodo == null) return null;

        List<VueloProgramadoId> ids = new ArrayList<>();
        Node iter = mejorNodo;
        while (iter.padre != null) {
            ids.add(iter.ultimoVuelo);
            iter = iter.padre;
        }
        Collections.reverse(ids);
        return new RutaCandidata(ids, mejorNodo.llegada, mejorNodo.costoAcumulado);
    }

    private int calcularCapacidadReal(List<VueloProgramadoId> rutaIds, SolucionProgramacion s, 
                                      ALNS.Journal journal, Map<VueloProgramadoId, Integer> localFlightLoad) {
        int minCap = Integer.MAX_VALUE;

        for (VueloProgramadoId vid : rutaIds) {
            int cap = s.getCargaPorVuelo().capacidad(vid);
            int ocupado = s.getCargaPorVuelo().asignado(vid) + localFlightLoad.getOrDefault(vid, 0);
            minCap = Math.min(minCap, cap - ocupado);
        }

        for (int i = 0; i < rutaIds.size(); i++) {
            VueloProgramadoId v = rutaIds.get(i);
            
            if (!HUBS.contains(v.getOrigen())) {
                Instant checkTime = v.getSalidaUtc().minusSeconds(60); 
                int capAlmacen = journal.getOcc().disponible(v.getOrigen(), checkTime);
                minCap = Math.min(minCap, capAlmacen);
            }
            
            if (i < rutaIds.size() - 1) { 
                String airportConexion = v.getDestino();
                if (!HUBS.contains(airportConexion)) {
                    VueloProgramadoId nextV = rutaIds.get(i+1);
                    int capAlmacen = journal.getOcc().maxReservable(airportConexion, v.getLlegadaUtc(), nextV.getSalidaUtc());
                    minCap = Math.min(minCap, capAlmacen);
                }
            } else {
                String destinoFinal = v.getDestino();
                if (!HUBS.contains(destinoFinal)) {
                    Instant finEstadia = v.getLlegadaUtc().plus(Duration.ofHours(2));
                    int capAlmacen = journal.getOcc().maxReservable(destinoFinal, v.getLlegadaUtc(), finEstadia);
                    minCap = Math.min(minCap, capAlmacen);
                }
            }
        }

        return Math.max(0, minCap);
    }

    private void ejecutarReservas(ALNS.Journal journal, PlanPedido plan, List<TramoAsignado> tramos, int q) {
        for (int i = 0; i < tramos.size(); i++) {
            TramoAsignado t = tramos.get(i);
            VueloProgramadoId v = t.getVuelo();

            if (!HUBS.contains(v.getOrigen())) {
                Instant ini = (i == 0) ? plan.getCreadoUtc() : tramos.get(i - 1).getVuelo().getLlegadaUtc();
                if (ini != null && v.getSalidaUtc().isAfter(ini)) {
                    journal.reservar(v.getOrigen(), ini, v.getSalidaUtc(), q);
                }
            }

            if (i == tramos.size() - 1 && !HUBS.contains(v.getDestino())) {
                Instant fin = v.getLlegadaUtc().plus(Duration.ofHours(2));
                journal.reservar(v.getDestino(), v.getLlegadaUtc(), fin, q);
            }
        }
    }

    private void actualizarSolucion(SolucionProgramacion s, PlanPedido p, List<RutaAsignada> nuevasRutas) {
        PlanPedido nuevo = PlanPedido.builder()
                .idPedido(p.getIdPedido())
                .aeropuertoDestino(p.getAeropuertoDestino())
                .creadoUtc(p.getCreadoUtc())
                .demanda(p.getDemanda())
                .rutas(nuevasRutas)
                .build();
        s.getPlanPorPedido().put(nuevo.getIdPedido(), nuevo);
    }

    private boolean esInvalido(PlanPedido p) {
        return p.getRutas() == null || p.getRutas().isEmpty();
    }

    private record RutaCandidata(List<VueloProgramadoId> tramosIds, Instant llegadaFinal, double costoPonderado) {}
    
    private record Node(String ap, VueloProgramadoId ultimoVuelo, Node padre, int hops, Instant llegada, double costoAcumulado) implements Comparable<Node> {
        @Override public int compareTo(Node o) { 
            return Double.compare(this.costoAcumulado, o.costoAcumulado); 
        }
    }
}