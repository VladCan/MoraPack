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
    
    // 🔥 CAMBIO CLAVE PARA RF6: Reducimos drásticamente la ventana de conexión
    // Antes 12h, ahora 4h. Esto obliga a liberar almacenes rápido.
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

        // Estrategia: Atender primero los pedidos más grandes para asegurar espacio
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

        while (demandaRestante > 0 && intentos < 10) { // Aumenté intentos ligeramente para split granular
            intentos++;

            RutaCandidata mejorCandidata = null;
            // Usamos un costo compuesto (tiempo + penalización por espera)
            double mejorCosto = Double.MAX_VALUE;

            for (String origen : sedesCandidatas) {
                // Buscamos ruta considerando restricciones de espacio (RF3, RF4)
                RutaCandidata candidata = dijkstraSplit(origen, plan, presenteUTC, s.getCargaPorVuelo(), localFlightLoad, journal);
                
                if (candidata != null) {
                    // El "Costo" ahora incluye penalización por usar almacén en conexiones
                    if (candidata.costoPonderado < mejorCosto) {
                        mejorCosto = candidata.costoPonderado;
                        mejorCandidata = candidata;
                    }
                }
            }

            if (mejorCandidata == null) break; 

            // Verificar capacidad real en todo el trayecto (RF3 y RF4)
            int capacidadRuta = calcularCapacidadReal(mejorCandidata.tramosIds, s, journal, localFlightLoad);
            int aEnviar = Math.min(demandaRestante, capacidadRuta);
            
            if (aEnviar <= 0) break;

            List<TramoAsignado> tramosFinales = new ArrayList<>();
            for (VueloProgramadoId vid : mejorCandidata.tramosIds) {
                tramosFinales.add(new TramoAsignado(vid, aEnviar, vid.getLlegadaUtc()));
                localFlightLoad.merge(vid, aEnviar, Integer::sum);
            }
            
            // RF2 y RF3: Reservar espacio en aeropuertos (Destino final y Conexiones)
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
                                        ALNS.Journal journal) { // Pasamos Journal para pre-chequeo rápido
        String destino = plan.getAeropuertoDestino();
        if (origen.equals(destino)) return null;

        PriorityQueue<Node> pq = new PriorityQueue<>();
        Map<String, Double> bestCost = new HashMap<>();

        Instant startTime = plan.getCreadoUtc().isAfter(presenteUTC) ? plan.getCreadoUtc() : presenteUTC;
        
        // Costo inicial 0
        pq.add(new Node(origen, null, null, 0, startTime, 0.0));
        bestCost.put(origen, 0.0);

        Node mejorNodo = null;
        int nodes = 0;

        while (!pq.isEmpty()) {
            Node current = pq.poll();
            nodes++;
            if (nodes > MAX_DIJKSTRA_NODES) break;

            // Poda por costo
            if (current.costoAcumulado > bestCost.getOrDefault(current.ap, Double.MAX_VALUE)) continue;

            if (current.ap.equals(destino)) {
                mejorNodo = current;
                break; 
            }

            if (current.hops >= MAX_HOPS) continue;

            List<VueloFicha> salidas = indexVuelos.porOrigen(current.ap);
            if (salidas == null) continue;

            for (VueloFicha vf : salidas) {
                VueloProgramadoId id = vf.id();
                
                // 1. RF4: Chequeo rápido de capacidad de vuelo
                int ocupadoGlobal = cargaGlobal.asignado(id);
                int ocupadoLocal = cargaLocal.getOrDefault(id, 0);
                if ((cargaGlobal.capacidad(id) - (ocupadoGlobal + ocupadoLocal)) < 1) continue;

                // 2. Tiempos y Conexiones (RF6)
                Instant salidaUtc = id.getSalidaUtc();
                
                // Tiempo de espera en este aeropuerto
                long waitSeconds = Duration.between(current.llegada, salidaUtc).getSeconds();
                
                // Reglas de conexión
                if (waitSeconds < MIN_CONNECTION_HOURS * 3600) continue; // Mínimo 1h
                if (waitSeconds > MAX_CONNECTION_HOURS * 3600) continue; // Máximo 6h (Estricto para ahorrar almacén)
                
                // RF1: SLA Global
                if (Duration.between(plan.getCreadoUtc(), id.getLlegadaUtc()).toHours() > MAX_SLA_HOURS) continue;

                // RF5: Hubs no pueden ser puntos intermedios (ya filtrado por tu lógica anterior, reforzado aquí)
                String nextAp = id.getDestino();
                if (HUBS.contains(nextAp) && !nextAp.equals(destino)) continue;

                // --- COSTO INTELIGENTE ---
                // Costo = Tiempo de vuelo + (Tiempo de espera * PENALIZACIÓN)
                // Penalizamos fuertemente dejar paquetes en tierra.
                double flightDuration = Duration.between(salidaUtc, id.getLlegadaUtc()).toMinutes();
                double waitPenalty = (waitSeconds / 60.0) * 2.5; // Cada minuto en tierra duele 2.5 veces más que en aire
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

        // 1. Capacidad de Aviones (RF4)
        for (VueloProgramadoId vid : rutaIds) {
            int cap = s.getCargaPorVuelo().capacidad(vid);
            int ocupado = s.getCargaPorVuelo().asignado(vid) + localFlightLoad.getOrDefault(vid, 0);
            minCap = Math.min(minCap, cap - ocupado);
        }

        // 2. Capacidad de Almacenes (RF3) - Verificación Integral
        for (int i = 0; i < rutaIds.size(); i++) {
            VueloProgramadoId v = rutaIds.get(i);
            
            // A. Aeropuerto de Origen (Espera inicial)
            // Si no es un Hub infinito (RF5), verificamos espacio
            if (!HUBS.contains(v.getOrigen())) {
                // Verificamos el espacio justo antes de salir.
                // Si la carga llega mucho antes, ocupará espacio.
                Instant checkTime = v.getSalidaUtc().minusSeconds(60); 
                int capAlmacen = journal.getOcc().disponible(v.getOrigen(), checkTime);
                minCap = Math.min(minCap, capAlmacen);
            }
            
            // B. Aeropuerto de Conexión (Tránsito)
            // Si llego a un aeropuerto que NO es destino final y NO es Hub, ocupo espacio mientras espero
            if (i < rutaIds.size() - 1) { 
                String airportConexion = v.getDestino();
                if (!HUBS.contains(airportConexion)) {
                    VueloProgramadoId nextV = rutaIds.get(i+1);
                    // Verificamos el "peor momento" en el intervalo de espera
                    // (Simplificación: chequeamos a la mitad de la espera o al llegar)
                    int capAlmacen = journal.getOcc().maxReservable(airportConexion, v.getLlegadaUtc(), nextV.getSalidaUtc());
                    minCap = Math.min(minCap, capAlmacen);
                }
            } else {
                // C. Destino Final (RF2)
                // Deben quedarse 2 horas. Verificamos si hay espacio para esa estadía.
                String destinoFinal = v.getDestino();
                if (!HUBS.contains(destinoFinal)) { // Solo si no es Hub
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

            // 1. Reserva en Origen (si no es Hub)
            if (!HUBS.contains(v.getOrigen())) {
                Instant ini = (i == 0) ? plan.getCreadoUtc() : tramos.get(i - 1).getVuelo().getLlegadaUtc();
                
                // Corrección: El pedido existe desde 'ini', pero quizás el vuelo sale mucho después.
                // Reservamos desde que el producto está disponible en el aeropuerto hasta que sale el vuelo.
                if (ini != null && v.getSalidaUtc().isAfter(ini)) {
                    journal.reservar(v.getOrigen(), ini, v.getSalidaUtc(), q);
                }
            }

            // 2. Reserva en Destino Final (RF2)
            // Si es el último tramo y no es Hub, reservamos 2 horas obligatorias.
            if (i == tramos.size() - 1 && !HUBS.contains(v.getDestino())) {
                Instant fin = v.getLlegadaUtc().plus(Duration.ofHours(2));
                journal.reservar(v.getDestino(), v.getLlegadaUtc(), fin, q);
            }
            
            // Nota: La reserva de "Conexión" está implícita en el punto 1 del siguiente tramo.
            // Si el tramo i llega a 'B' a las 10:00, y el tramo i+1 sale de 'B' a las 14:00,
            // el bucle i+1 ejecutará la reserva en 'B' de 10:00 a 14:00.
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

    // Helper classes
    private record RutaCandidata(List<VueloProgramadoId> tramosIds, Instant llegadaFinal, double costoPonderado) {}
    
    // Node ahora comparable por Costo Ponderado, no solo llegada
    private record Node(String ap, VueloProgramadoId ultimoVuelo, Node padre, int hops, Instant llegada, double costoAcumulado) implements Comparable<Node> {
        @Override public int compareTo(Node o) { 
            return Double.compare(this.costoAcumulado, o.costoAcumulado); 
        }
    }
}