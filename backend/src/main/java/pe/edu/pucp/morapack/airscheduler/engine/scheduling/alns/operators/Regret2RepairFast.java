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

public class Regret2RepairFast implements RepairOperator {

    private final IndexVuelos indexVuelos;
    private final List<String> sedesCandidatas;
    private final Random rnd = new Random();

    private static final int MAX_HOPS = 3;
    private static final Set<String> HUBS = Set.of("SPIM", "EBCI", "UBBB");
    private static final long MAX_SLA_HOURS = 46;
    private static final double PENALTY_NO_ROUTE = 99999.0;
    
    // Configuración de Poda
    private static final int MAX_SEARCH_NODES = 600; // Detiene la búsqueda si explora demasiados nodos
    private static final long MAX_CONNECTION_HOURS = 12; // Nadie espera más de 12h en una escala

    public Regret2RepairFast(int k, List<String> sedes, VuelosTEG teg) {
        this.sedesCandidatas = new ArrayList<>(sedes);
        this.indexVuelos = new IndexVuelos(teg);
    }

    @Override
    public void repair(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {
        // 1. Identificar pedidos pendientes
        List<PlanPedido> unassigned = s.getPlanPorPedido().values().stream()
                .filter(p -> p.getDemanda() > 0 && esInvalido(p))
                .collect(Collectors.toList());

        if (unassigned.isEmpty()) return;

        CargaPorVuelo cargaPorVuelo = s.getCargaPorVuelo();
        List<CandidateEntry> ranking = new ArrayList<>(unassigned.size());

        // 2. FASE DE CÁLCULO MASIVO (Snapshot inicial)
        for (PlanPedido plan : unassigned) {
            // Buscamos la mejor ruta ignorando pequeñas variaciones de carga momentáneas
            RutaAsignada ruta1 = buscarMejorRutaDesdeCualquierHub(plan, presenteUTC, cargaPorVuelo, null);
            if (ruta1 == null) continue;

            double coste1 = calcularDuracion(plan, ruta1);

            // Calculamos la segunda mejor para el Regret
            VueloProgramadoId vueloABanear = ruta1.getTramos().get(0).getVuelo();
            RutaAsignada ruta2 = buscarMejorRutaDesdeCualquierHub(plan, presenteUTC, cargaPorVuelo, vueloABanear);

            double coste2 = (ruta2 == null) ? (coste1 + PENALTY_NO_ROUTE) : calcularDuracion(plan, ruta2);
            double regret = coste2 - coste1;
            double noise = 0.9 + (0.2 * rnd.nextDouble());

            ranking.add(new CandidateEntry(plan, ruta1, regret * noise));
        }

        // 3. ORDENAR POR ARREPENTIMIENTO
        ranking.sort((a, b) -> Double.compare(b.regretScore, a.regretScore));

        // 4. FASE DE INSERCIÓN CON RESCATE (FALLBACK)
        for (CandidateEntry entry : ranking) {
            PlanPedido plan = entry.plan;
            RutaAsignada rutaIntento = entry.bestRoute;

            // INTENTO A: Usar la ruta pre-calculada (Muy rápido)
            if (verificarCapacidadReal(s, journal, plan, rutaIntento)) {
                ejecutarReservas(journal, s, plan, rutaIntento);
                actualizarSolucion(s, plan, rutaIntento);
            } 
            else {
                // 🚨 INTENTO B (FALLBACK): La ruta pre-calculada falló (alguien ocupó el espacio).
                // Recalculamos una ruta fresca con la carga actualizada.
                RutaAsignada rutaFresca = buscarMejorRutaDesdeCualquierHub(plan, presenteUTC, s.getCargaPorVuelo(), null);
                
                if (rutaFresca != null && verificarCapacidadReal(s, journal, plan, rutaFresca)) {
                    ejecutarReservas(journal, s, plan, rutaFresca);
                    actualizarSolucion(s, plan, rutaFresca);
                }
            }
        }
    }

    private void actualizarSolucion(SolucionProgramacion s, PlanPedido plan, RutaAsignada ruta) {
        List<RutaAsignada> rutaLista = new ArrayList<>();
        rutaLista.add(ruta);
        
        PlanPedido nuevoPlan = PlanPedido.builder()
                .idPedido(plan.getIdPedido())
                .aeropuertoDestino(plan.getAeropuertoDestino())
                .creadoUtc(plan.getCreadoUtc())
                .demanda(plan.getDemanda())
                .rutas(rutaLista)
                .build();

        s.getPlanPorPedido().put(nuevoPlan.getIdPedido(), nuevoPlan);
    }

    private record CandidateEntry(PlanPedido plan, RutaAsignada bestRoute, double regretScore) {}

    private boolean verificarCapacidadReal(SolucionProgramacion s, ALNS.Journal journal, PlanPedido plan, RutaAsignada ruta) {
        for (TramoAsignado t : ruta.getTramos()) {
             if (s.getCargaPorVuelo().residual(t.getVuelo()) < ruta.getCantidad()) {
                 return false;
             }
        }
        return verificarCapacidadAlmacenes(journal, plan, ruta);
    }

    // --- DIJKSTRA OPTIMIZADO CON PODA ---

    private RutaAsignada buscarMejorRutaDesdeCualquierHub(PlanPedido plan, Instant presenteUTC, 
                                                          CargaPorVuelo carga, VueloProgramadoId vueloProhibido) {
        RutaAsignada mejorRuta = null;
        double mejorTiempo = Double.MAX_VALUE;

        for (String origen : sedesCandidatas) {
            RutaAsignada candidata = DijkstraTimeBased(origen, plan, presenteUTC, carga, vueloProhibido);
            if (candidata != null) {
                double tiempo = calcularDuracion(plan, candidata);
                if (tiempo < mejorTiempo) {
                    mejorTiempo = tiempo;
                    mejorRuta = candidata;
                }
            }
        }
        return mejorRuta;
    }

    private RutaAsignada DijkstraTimeBased(String origen, PlanPedido plan, Instant presenteUTC, 
                                           CargaPorVuelo cargaPorVuelo, VueloProgramadoId vueloProhibido) {
        String destino = plan.getAeropuertoDestino();
        int demanda = plan.getDemanda();
        if (origen.equals(destino)) return null;

        PriorityQueue<Node> pq = new PriorityQueue<>();
        Map<String, Instant> bestArrival = new HashMap<>();

        Instant startTime = plan.getCreadoUtc().isAfter(presenteUTC) ? plan.getCreadoUtc() : presenteUTC;
        pq.add(new Node(origen, null, null, 0, startTime));
        bestArrival.put(origen, startTime);

        Node mejorNodoDestino = null;
        int nodesExplored = 0; // Contador para evitar bucles infinitos o búsquedas muy largas

        while (!pq.isEmpty()) {
            Node current = pq.poll();
            nodesExplored++;

            // ⚡ PODA 1: Límite de exploración (Critical para performance)
            if (nodesExplored > MAX_SEARCH_NODES) break;

            // ⚡ PODA 2: Si ya llegamos a este nodo antes con mejor tiempo, descartar
            if (current.llegada.isAfter(bestArrival.getOrDefault(current.ap, Instant.MAX))) continue;

            // ⚡ PODA 3: Si ya encontramos un camino al destino y este nodo actual
            // ya llegó más tarde que ese camino final, no tiene sentido seguir por aquí.
            if (mejorNodoDestino != null && current.llegada.isAfter(mejorNodoDestino.llegada)) continue;

            // ⚡ ÉXITO TEMPRANO: En logística masiva, el primer camino válido encontrado por Dijkstra
            // suele ser el óptimo en tiempo. Cortamos aquí para ahorrar milisegundos.
            if (current.ap.equals(destino)) {
                mejorNodoDestino = current;
                break; 
            }

            if (current.hops >= MAX_HOPS) continue;

            List<VueloFicha> salidas = indexVuelos.porOrigen(current.ap);
            if (salidas == null) continue;

            for (VueloFicha vf : salidas) {
                VueloProgramadoId id = vf.id();
                
                if (vueloProhibido != null && id.equals(vueloProhibido)) continue;
                
                // ⚡ PODA 4: Capacidad (Pre-check rápido)
                if (cargaPorVuelo.residual(id) < demanda) continue;

                Instant salidaUtc = id.getSalidaUtc();
                
                // ⚡ PODA 5: Tiempo mínimo de conexión (1h)
                if (salidaUtc.isBefore(current.llegada.plusSeconds(3600))) continue; 
                
                // ⚡ PODA 6: Tiempo MÁXIMO de conexión (Evita esperas eternas de >12h)
                if (salidaUtc.isAfter(current.llegada.plusSeconds(3600 * MAX_CONNECTION_HOURS))) continue;

                // ⚡ PODA 7: SLA Global
                if (Duration.between(plan.getCreadoUtc(), id.getLlegadaUtc()).toHours() > MAX_SLA_HOURS) continue;

                String nextAp = id.getDestino();
                if (HUBS.contains(nextAp) && !nextAp.equals(destino)) continue;

                Instant llegadaUtc = id.getLlegadaUtc();
                if (llegadaUtc.isBefore(bestArrival.getOrDefault(nextAp, Instant.MAX))) {
                    bestArrival.put(nextAp, llegadaUtc);
                    pq.add(new Node(nextAp, id, current, current.hops + 1, llegadaUtc));
                }
            }
        }

        if (mejorNodoDestino == null) return null;

        List<TramoAsignado> tramos = new ArrayList<>();
        Node iter = mejorNodoDestino;
        while (iter.padre != null) {
            tramos.add(new TramoAsignado(iter.ultimoVuelo, demanda, iter.ultimoVuelo.getLlegadaUtc()));
            iter = iter.padre;
        }
        Collections.reverse(tramos);
        return new RutaAsignada(demanda, tramos);
    }

    // --- UTILITARIOS ---

    private boolean verificarCapacidadAlmacenes(ALNS.Journal journal, PlanPedido plan, RutaAsignada ruta) {
        int q = ruta.getCantidad();
        List<TramoAsignado> tramos = ruta.getTramos();
        OcupacionPorAeropuerto occ = journal.getOcc();

        for (int i = 0; i < tramos.size(); i++) {
            TramoAsignado t = tramos.get(i);
            VueloProgramadoId v = t.getVuelo();

            if (!HUBS.contains(v.getOrigen())) {
                Instant iniOri = (i == 0) ? plan.getCreadoUtc() : tramos.get(i - 1).getVuelo().getLlegadaUtc();
                if (iniOri != null && v.getSalidaUtc().isAfter(iniOri)) {
                    if (q > occ.maxReservable(v.getOrigen(), iniOri, v.getSalidaUtc())) return false;
                }
            }
            if (!HUBS.contains(v.getDestino())) {
                Instant finDst = (i + 1 < tramos.size()) ? tramos.get(i + 1).getVuelo().getSalidaUtc() : v.getLlegadaUtc().plus(Duration.ofHours(2));
                if (finDst.isAfter(v.getLlegadaUtc())) {
                    if (q > occ.maxReservable(v.getDestino(), v.getLlegadaUtc(), finDst)) return false;
                }
            }
        }
        return true;
    }

    private void ejecutarReservas(ALNS.Journal journal, SolucionProgramacion s, PlanPedido plan, RutaAsignada ruta) {
        int q = ruta.getCantidad();
        List<TramoAsignado> tramos = ruta.getTramos();

        for (int i = 0; i < tramos.size(); i++) {
            TramoAsignado t = tramos.get(i);
            VueloProgramadoId v = t.getVuelo();

            if (!HUBS.contains(v.getOrigen())) {
                Instant iniOri = (i == 0) ? plan.getCreadoUtc() : tramos.get(i - 1).getVuelo().getLlegadaUtc();
                if (iniOri != null && v.getSalidaUtc().isAfter(iniOri)) {
                    journal.reservar(v.getOrigen(), iniOri, v.getSalidaUtc(), q);
                }
            }

            if (!HUBS.contains(v.getDestino())) {
                Instant finDst = (i + 1 < tramos.size()) ? tramos.get(i + 1).getVuelo().getSalidaUtc() : v.getLlegadaUtc().plus(Duration.ofHours(2));
                if (finDst.isAfter(v.getLlegadaUtc())) {
                    journal.reservar(v.getDestino(), v.getLlegadaUtc(), finDst, q);
                }
            }
            s.getCargaPorVuelo().asignar(v, q);
        }
    }

    private boolean esInvalido(PlanPedido p) {
        if (p.getRutas() == null || p.getRutas().isEmpty()) return true;
        return p.getRutas().stream().anyMatch(r -> r.getTramos() == null || r.getTramos().isEmpty());
    }

    private double calcularDuracion(PlanPedido p, RutaAsignada r) {
        Instant llegada = r.ultimaLlegada();
        if (llegada == null || p.getCreadoUtc() == null) return Double.MAX_VALUE;
        return (double) Duration.between(p.getCreadoUtc(), llegada).toMinutes();
    }

    record Node(String ap, VueloProgramadoId ultimoVuelo, Node padre, int hops, Instant llegada) implements Comparable<Node> {
        @Override public int compareTo(Node o) { return this.llegada.compareTo(o.llegada); }
    }
}