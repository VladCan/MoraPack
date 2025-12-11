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
    
    // RF5: Sedes con stock infinito que NO pueden ser conexiones
    private static final Set<String> HUBS = Set.of("SPIM", "EBCI", "UBBB");
    // RF1: SLA Máximo
    private static final long MAX_SLA_HOURS = 46;
    
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
            // RF4 Check Macro: Si no cabe en ningún avión, ni intentar
            if (plan.getDemanda() > MAX_CAPACIDAD_AVION) continue;

            if (esInvalido(plan)) {
                
                RutaAsignada mejorRutaValida = null;
                double mejorTiempo = Double.MAX_VALUE;

                Collections.shuffle(sedesCandidatas, rnd);

                // ESTRATEGIA: Buscar en TODAS las sedes una ruta válida.
                // No nos rendimos si la primera está llena.
                for (String sedeOrigen : sedesCandidatas) {
                    
                    // 1. Obtener ruta candidata (Cumple RF1, RF4, RF5 por construcción en Dijkstra)
                    RutaAsignada ruta = DijkstraTimeBased(sedeOrigen, plan, presenteUTC, cargaPorVuelo);
                    
                    if (ruta != null) {
                        // 2. Verificar RF3 (Capacidad Almacenes) INMEDIATAMENTE
                        // Si no cabe en los almacenes, esta ruta NO es una opción.
                        if (verificarCapacidadAlmacenes(journal, plan, ruta)) {
                            
                            double duracion = calcularDuracion(plan, ruta);
                            double ruido = 0.90 + (0.2 * rnd.nextDouble()); // Aleatoriedad para diversidad
                            double duracionConRuido = duracion * ruido;

                            if (duracionConRuido < mejorTiempo) {
                                mejorTiempo = duracionConRuido;
                                mejorRutaValida = ruta;
                            }
                        }
                    }
                }

                // Si encontramos alguna ruta válida (cumple todas las RFs), la aplicamos.
                if (mejorRutaValida != null) {
                    
                    ejecutarReservas(journal, s, plan, mejorRutaValida);

                    List<RutaAsignada> rutaLista = new ArrayList<>();
                    rutaLista.add(mejorRutaValida);

                    PlanPedido nuevoPlan = PlanPedido.builder()
                            .idPedido(plan.getIdPedido())
                            .aeropuertoDestino(plan.getAeropuertoDestino())
                            .creadoUtc(plan.getCreadoUtc())
                            .demanda(plan.getDemanda())
                            .rutas(rutaLista)
                            .build();

                    s.getPlanPorPedido().put(nuevoPlan.getIdPedido(), nuevoPlan);
                }
            }
        }
    }

    private boolean esInvalido(PlanPedido p) {
        if (p.getRutas() == null || p.getRutas().isEmpty()) return true;
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

        Instant startTime = plan.getCreadoUtc().isAfter(presenteUTC) ? plan.getCreadoUtc() : presenteUTC;

        pq.add(new Node(origen, null, null, 0, startTime));
        bestArrival.put(origen, startTime);

        Node mejorNodoDestino = null;

        while (!pq.isEmpty()) {
            Node current = pq.poll();

            if (current.llegada.isAfter(bestArrival.getOrDefault(current.ap, Instant.MAX))) continue;
            
            // RF1: Control estricto de SLA (46h)
            long horasTranscurridas = Duration.between(plan.getCreadoUtc(), current.llegada).toHours();
            if (horasTranscurridas > MAX_SLA_HOURS) continue;

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
                String nextAp = id.getDestino();

                // RF5: Si el siguiente aeropuerto es un HUB y NO es el destino final,
                // no podemos usarlo (porque los hubs no hacen conexiones).
                if (HUBS.contains(nextAp) && !nextAp.equals(destino)) {
                    continue;
                }

                if (salidaUtc.isBefore(current.llegada.plusSeconds(3600))) continue; // Min 1h transbordo

                // RF4: Capacidad del avión (HARD CONSTRAINT)
                if (cargaPorVuelo.residual(id) < demanda) continue; 

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
        
        if (tramos.isEmpty()) return null;
        Collections.reverse(tramos);
        return new RutaAsignada(demanda, tramos);
    }

    /**
     * RF3: Verifica capacidad física de almacenes (Check-Then-Act).
     * Devuelve true SOLO si hay espacio garantizado.
     */
    private boolean verificarCapacidadAlmacenes(ALNS.Journal journal, PlanPedido plan, RutaAsignada ruta) {
        int q = ruta.getCantidad();
        List<TramoAsignado> tramos = ruta.getTramos();
        OcupacionPorAeropuerto occ = journal.getOcc(); 

        for (int i = 0; i < tramos.size(); i++) {
            TramoAsignado t = tramos.get(i);
            VueloProgramadoId v = t.getVuelo();

            // 1. Verificar Origen (Solo si no es HUB)
            if (!HUBS.contains(v.getOrigen())) {
                Instant iniOri = (i == 0) ? plan.getCreadoUtc() : tramos.get(i - 1).getVuelo().getLlegadaUtc();
                if (iniOri != null && v.getSalidaUtc() != null && v.getSalidaUtc().isAfter(iniOri)) {
                    int maxCap = occ.maxReservable(v.getOrigen(), iniOri, v.getSalidaUtc());
                    if (q > maxCap) return false; // RF3 Violado: No cabe
                }
            }

            // 2. Verificar Destino (Solo si no es HUB)
            if (!HUBS.contains(v.getDestino())) {
                // RF2: Destino final se queda 2 horas. Conexión hasta siguiente vuelo.
                Instant finDst = (i + 1 < tramos.size()) 
                    ? tramos.get(i + 1).getVuelo().getSalidaUtc() 
                    : v.getLlegadaUtc().plus(Duration.ofHours(2));
                
                if (finDst.isAfter(v.getLlegadaUtc())) {
                    int maxCap = occ.maxReservable(v.getDestino(), v.getLlegadaUtc(), finDst);
                    if (q > maxCap) return false; // RF3 Violado: No cabe
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
                Instant finDst = (i + 1 < tramos.size()) 
                    ? tramos.get(i + 1).getVuelo().getSalidaUtc() 
                    : v.getLlegadaUtc().plus(Duration.ofHours(2));
                
                if (finDst.isAfter(v.getLlegadaUtc())) {
                    journal.reservar(v.getDestino(), v.getLlegadaUtc(), finDst, q);
                }
            }
            s.getCargaPorVuelo().asignar(v, q);
        }
    }

    private double calcularDuracion(PlanPedido p, RutaAsignada r) {
        Instant llegada = r.ultimaLlegada();
        if (llegada == null || p.getCreadoUtc() == null) return Double.MAX_VALUE;
        return Duration.between(p.getCreadoUtc(), llegada).toMinutes();
    }
}