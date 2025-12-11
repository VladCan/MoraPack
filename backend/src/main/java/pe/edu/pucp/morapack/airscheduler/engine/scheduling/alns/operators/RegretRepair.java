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
    // RF1: SLA Máximo (46 horas)
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
            // RF4 (Parcial): Si el pedido es gigante, no cabe en ningún avión simple, skip.
            if (plan.getDemanda() > MAX_CAPACIDAD_AVION) continue;

            if (esInvalido(plan)) {
                
                RutaAsignada mejorRutaGlobal = null;
                double mejorTiempoGlobal = Double.MAX_VALUE;

                Collections.shuffle(sedesCandidatas, rnd);

                for (String sedeOrigen : sedesCandidatas) {
                    // El Dijkstra ya filtra por RF1, RF4 y RF5
                    RutaAsignada ruta = DijkstraTimeBased(sedeOrigen, plan, presenteUTC, cargaPorVuelo);
                    
                    if (ruta != null) {
                        double duracion = calcularDuracion(plan, ruta);
                        double ruido = 0.90 + (0.2 * rnd.nextDouble());
                        double duracionConRuido = duracion * ruido;

                        if (duracionConRuido < mejorTiempoGlobal) {
                            mejorTiempoGlobal = duracionConRuido;
                            mejorRutaGlobal = ruta;
                        }
                    }
                }

                if (mejorRutaGlobal != null && mejorRutaGlobal.getCantidad() > 0) {
                    
                    // RF3: Verificación estricta de capacidad en almacenes ANTES de escribir
                    boolean esFactible = verificarCapacidadAlmacenes(journal, plan, mejorRutaGlobal);

                    if (esFactible) {
                        // Si es factible, procedemos a escribir (Commit)
                        ejecutarReservas(journal, s, plan, mejorRutaGlobal);

                        List<RutaAsignada> rutaLista = new ArrayList<>();
                        rutaLista.add(mejorRutaGlobal);

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

        // El tiempo de inicio es el mayor entre (Creación del Pedido) y (Ahora Simulado)
        Instant startTime = plan.getCreadoUtc().isAfter(presenteUTC) ? plan.getCreadoUtc() : presenteUTC;

        pq.add(new Node(origen, null, null, 0, startTime));
        bestArrival.put(origen, startTime);

        Node mejorNodoDestino = null;

        while (!pq.isEmpty()) {
            Node current = pq.poll();

            // Poda si llegamos tarde
            if (current.llegada.isAfter(bestArrival.getOrDefault(current.ap, Instant.MAX))) continue;
            
            // RF1 Check: Si supera 46h desde la creación del pedido, descartamos esta rama
            long horasTranscurridas = Duration.between(plan.getCreadoUtc(), current.llegada).toHours();
            if (horasTranscurridas > MAX_SLA_HOURS) continue;

            if (current.ap.equals(destino)) {
                mejorNodoDestino = current;
                break; // Encontramos el destino (Time-based Dijkstra greedy)
            }

            if (current.hops >= MAX_HOPS) continue;

            // RF5 Check: Si estamos en un HUB y NO es el origen, es una conexión ilegal.
            if (HUBS.contains(current.ap) && !current.ap.equals(origen)) {
                continue;
            }

            List<VueloFicha> salidas = indexVuelos.porOrigen(current.ap);
            if (salidas == null) continue;

            for (VueloFicha vf : salidas) {
                VueloProgramadoId id = vf.id();
                Instant salidaUtc = id.getSalidaUtc();
                Instant llegadaUtc = id.getLlegadaUtc();

                // Tiempo mínimo de conexión (ej. 1h) o validación de causalidad
                if (salidaUtc.isBefore(current.llegada.plusSeconds(3600))) continue; 

                // RF4 Check: ¿Cabe en el avión?
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

    /**
     * RF3: Verifica si hay espacio en los almacenes para TODA la ruta.
     * NO realiza modificaciones, solo consulta (Check-Then-Act).
     */
    private boolean verificarCapacidadAlmacenes(ALNS.Journal journal, PlanPedido plan, RutaAsignada ruta) {
        int q = ruta.getCantidad();
        List<TramoAsignado> tramos = ruta.getTramos();
        OcupacionPorAeropuerto occ = journal.getOcc(); // Accedemos al objeto real para consultar

        for (int i = 0; i < tramos.size(); i++) {
            TramoAsignado t = tramos.get(i);
            VueloProgramadoId v = t.getVuelo();

            // 1. Verificar Origen (si no es HUB)
            if (!HUBS.contains(v.getOrigen())) {
                Instant iniOri = (i == 0) ? plan.getCreadoUtc() : tramos.get(i - 1).getVuelo().getLlegadaUtc();
                
                // Si hay intervalo de espera en origen
                if (iniOri != null && v.getSalidaUtc().isAfter(iniOri)) {
                    int disponible = occ.maxReservable(v.getOrigen(), iniOri, v.getSalidaUtc());
                    if (disponible < q) return false; // RF3 Violado
                }
            }

            // 2. Verificar Destino (si no es HUB)
            if (!HUBS.contains(v.getDestino())) {
                Instant finDst = (i + 1 < tramos.size()) 
                    ? tramos.get(i + 1).getVuelo().getSalidaUtc() // Conexión
                    : v.getLlegadaUtc().plus(Duration.ofHours(2)); // Destino final (RF2)
                
                if (finDst.isAfter(v.getLlegadaUtc())) {
                    int disponible = occ.maxReservable(v.getDestino(), v.getLlegadaUtc(), finDst);
                    if (disponible < q) return false; // RF3 Violado
                }
            }
        }
        return true;
    }

    /**
     * Ejecuta las reservas. Se asume que verificarCapacidadAlmacenes ya dio luz verde.
     */
    private void ejecutarReservas(ALNS.Journal journal, SolucionProgramacion s, PlanPedido plan, RutaAsignada ruta) {
        int q = ruta.getCantidad();
        List<TramoAsignado> tramos = ruta.getTramos();

        for (int i = 0; i < tramos.size(); i++) {
            TramoAsignado t = tramos.get(i);
            VueloProgramadoId v = t.getVuelo();

            // Reservar Origen
            if (!HUBS.contains(v.getOrigen())) {
                Instant iniOri = (i == 0) ? plan.getCreadoUtc() : tramos.get(i - 1).getVuelo().getLlegadaUtc();
                if (iniOri != null && v.getSalidaUtc().isAfter(iniOri)) {
                    journal.reservar(v.getOrigen(), iniOri, v.getSalidaUtc(), q);
                }
            }

            // Reservar Destino
            if (!HUBS.contains(v.getDestino())) {
                Instant finDst = (i + 1 < tramos.size()) 
                    ? tramos.get(i + 1).getVuelo().getSalidaUtc() 
                    : v.getLlegadaUtc().plus(Duration.ofHours(2));
                
                if (finDst.isAfter(v.getLlegadaUtc())) {
                    journal.reservar(v.getDestino(), v.getLlegadaUtc(), finDst, q);
                }
            }

            // Asignar al avión (RF4 ya validado en Dijkstra, pero se aplica aquí)
            s.getCargaPorVuelo().asignar(v, q);
        }
    }

    private double calcularDuracion(PlanPedido p, RutaAsignada r) {
        Instant llegada = r.ultimaLlegada();
        if (llegada == null || p.getCreadoUtc() == null) return Double.MAX_VALUE;
        return Duration.between(p.getCreadoUtc(), llegada).toMinutes();
    }
}