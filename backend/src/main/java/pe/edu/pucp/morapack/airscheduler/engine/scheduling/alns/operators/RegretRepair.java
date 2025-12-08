package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.service.IndexVuelos;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.service.VueloFicha;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class RegretRepair implements RepairOperator {

    private final IndexVuelos indexVuelos;
    private final List<String> sedesCandidatas; 
    private final Random rnd = new Random();

    private static final int MAX_HOPS = 3; 
    private static final int MAX_CAPACIDAD_AVION = 6000;
    
    public RegretRepair(int k, List<String> sedes, VuelosTEG teg) {
        this.sedesCandidatas = new ArrayList<>(sedes);
        this.indexVuelos = new IndexVuelos(teg);
    }

    @Override
    public void repair(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {
        List<PlanPedido> planos = new ArrayList<>(s.getPlanPorPedido().values());
        CargaPorVuelo cargaPorVuelo = s.getCargaPorVuelo();
        Collections.shuffle(planos, rnd);

        for (PlanPedido plan : planos) {
            
            if (plan.getDemanda() <= 0) continue;
            // No rutear si excede capacidad física de un avión (debería ir a split)
            if (plan.getDemanda() > MAX_CAPACIDAD_AVION) continue;

            if (esInvalido(plan)) {
                
                RutaAsignada mejorRutaGlobal = null;
                double mejorTiempoGlobal = Double.MAX_VALUE;

                Collections.shuffle(sedesCandidatas, rnd);

                for (String sedeOrigen : sedesCandidatas) {
                    RutaAsignada ruta = DijkstraTimeBased(sedeOrigen, plan, presenteUTC, cargaPorVuelo);
                    
                    if (ruta != null) {
                        double duracion = calcularDuracion(plan, ruta);
                        // Factor de ruido para diversificación
                        double ruido = 0.90 + (0.2 * rnd.nextDouble());
                        double duracionConRuido = duracion * ruido;

                        if (duracionConRuido < mejorTiempoGlobal) {
                            mejorTiempoGlobal = duracionConRuido;
                            mejorRutaGlobal = ruta;
                        }
                    }
                }

                if (mejorRutaGlobal != null && mejorRutaGlobal.getCantidad() > 0) {
                    
                    // CORRECCIÓN: Usar lista mutable, nunca SingletonList
                    List<RutaAsignada> rutaLista = new ArrayList<>();
                    rutaLista.add(mejorRutaGlobal);
                    
                    boolean reservaExitosa = reservarRecursos(journal, s, plan, mejorRutaGlobal);

                    if (reservaExitosa) {
                        PlanPedido nuevoPlan = PlanPedido.builder()
                                .idPedido(plan.getIdPedido())
                                .aeropuertoDestino(plan.getAeropuertoDestino())
                                .creadoUtc(plan.getCreadoUtc())
                                .demanda(plan.getDemanda())
                                .rutas(rutaLista) // <--- Lista mutable
                                .build();

                        s.getPlanPorPedido().put(nuevoPlan.getIdPedido(), nuevoPlan);
                    }
                }
            }
        }
    }

    private boolean esInvalido(PlanPedido p) {
        if (p.getRutas() == null || p.getRutas().isEmpty()) return true;
        // Si tiene rutas, verificamos que no sean fantasmas (sin tramos)
        return p.getRutas().stream().anyMatch(r -> r.getTramos() == null || r.getTramos().isEmpty());
    }

    private RutaAsignada DijkstraTimeBased(String origen, PlanPedido plan, Instant presenteUTC, CargaPorVuelo cargaPorVuelo) {
        String destino = plan.getAeropuertoDestino();
        int demanda = plan.getDemanda();
        if (origen.equals(destino)) return null;

        record Node(String ap, VueloProgramadoId ultimoVuelo, Node padre, int hops, Instant llegada) implements Comparable<Node> {
            @Override public int compareTo(Node o) { return this.llegada.compareTo(o.llegada); }
        }

        PriorityQueue<Node> pq = new PriorityQueue<>();
        Map<String, Instant> bestArrival = new HashMap<>();

        pq.add(new Node(origen, null, null, 0, presenteUTC));
        bestArrival.put(origen, presenteUTC);

        Node mejorNodoDestino = null;

        while (!pq.isEmpty()) {
            Node current = pq.poll();

            if (current.llegada.isAfter(bestArrival.getOrDefault(current.ap, Instant.MAX))) continue;
            
            if (current.ap.equals(destino)) {
                mejorNodoDestino = current;
                break; 
            }

            if (current.hops >= MAX_HOPS) continue;

            List<VueloFicha> salidas = indexVuelos.porOrigen(current.ap);
            if (salidas == null) continue;

            for (VueloFicha vf : salidas) {
                VueloProgramadoId id = vf.id();
                Instant salidaUtc = id.getSalidaUtc();
                Instant llegadaUtc = id.getLlegadaUtc();

                if (salidaUtc.isBefore(current.llegada.plusSeconds(60))) continue;

                // Validación de capacidad de vuelo
                if (cargaPorVuelo.residual(id) < demanda) continue; 

                if (llegadaUtc.isBefore(bestArrival.getOrDefault(id.getDestino(), Instant.MAX))) {
                    bestArrival.put(id.getDestino(), llegadaUtc);
                    pq.add(new Node(id.getDestino(), id, current, current.hops + 1, llegadaUtc));
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
        
        if (tramos.isEmpty()) return null;

        Collections.reverse(tramos);
        return new RutaAsignada(demanda, tramos);
    }

    private boolean reservarRecursos(ALNS.Journal journal, SolucionProgramacion s, PlanPedido plan, RutaAsignada ruta) {
        int q = ruta.getCantidad();
        if (q <= 0) return false; 

        List<TramoAsignado> tramos = ruta.getTramos();
        if (tramos == null || tramos.isEmpty()) return false;

        // Pre-verificación de Vuelos
        for (TramoAsignado t : tramos) {
             if (s.getCargaPorVuelo().residual(t.getVuelo()) < q) return false;
        }

        // Escritura
        for (int i = 0; i < tramos.size(); i++) {
            TramoAsignado t = tramos.get(i);
            VueloProgramadoId v = t.getVuelo();

            Instant iniOri = (i == 0) ? plan.getCreadoUtc() : tramos.get(i - 1).getVuelo().getLlegadaUtc();
            if (iniOri != null && v.getSalidaUtc() != null && v.getSalidaUtc().isAfter(iniOri)) {
                journal.reservar(v.getOrigen(), iniOri, v.getSalidaUtc(), q);
                
                // MEJORA: Si al reservar saturamos el almacén, esto es una mala señal.
                // En un sistema ideal aquí retornaríamos false si excede capacidad crítica,
                // pero por ahora dejamos que el ALNS penalice.
            }

            Instant finDst = (i + 1 < tramos.size()) 
                ? tramos.get(i + 1).getVuelo().getSalidaUtc() 
                : v.getLlegadaUtc().plus(Duration.ofHours(2));
            
            if (v.getLlegadaUtc() != null && finDst != null && finDst.isAfter(v.getLlegadaUtc())) {
                journal.reservar(v.getDestino(), v.getLlegadaUtc(), finDst, q);
            }

            s.getCargaPorVuelo().asignar(v, q);
        }
        return true;
    }

    private double calcularDuracion(PlanPedido p, RutaAsignada r) {
        Instant llegada = r.ultimaLlegada();
        if (llegada == null || p.getCreadoUtc() == null) return Double.MAX_VALUE;
        return Duration.between(p.getCreadoUtc(), llegada).toMinutes();
    }
}