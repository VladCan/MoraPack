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
 * HYBRID SPLIT REPAIR
 * Fusiona la robustez de la lógica antigua (división de carga y conciencia de estado)
 * con la velocidad de la lógica nueva (estructuras ligeras y poda).
 */
public class Regret2RepairFast implements RepairOperator {

    private final IndexVuelos indexVuelos;
    private final List<String> sedesCandidatas;
    
    // Configuración
    private static final int MAX_HOPS = 3;
    private static final Set<String> HUBS = Set.of("SPIM", "EBCI", "UBBB");
    private static final long MAX_SLA_HOURS = 46;
    private static final int MAX_DIJKSTRA_NODES = 800; // Poda de seguridad

    public Regret2RepairFast(List<String> sedes, VuelosTEG teg) {
        this.sedesCandidatas = new ArrayList<>(sedes);
        this.indexVuelos = new IndexVuelos(teg);
    }

    @Override
    public void repair(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {
        // 1. Obtener pedidos rotos u huerfanos
        List<PlanPedido> unassigned = s.getPlanPorPedido().values().stream()
                .filter(p -> p.getDemanda() > 0 && esInvalido(p))
                .collect(Collectors.toList());

        if (unassigned.isEmpty()) return;

        // 2. Ordenar por Urgencia (Deadline más cercano primero) o por Demanda (más grandes primero)
        // La estrategia "Largest First" suele funcionar bien con Splitting para llenar huecos grandes primero.
        unassigned.sort((a, b) -> {
            int cmp = a.getCreadoUtc().compareTo(b.getCreadoUtc()); // Más antiguos primero (SLA)
            if (cmp == 0) return Integer.compare(b.getDemanda(), a.getDemanda()); // Desempate por tamaño
            return cmp;
        });

        // 3. Cache Local de Carga (Para velocidad extrema)
        // Rastrea cuánto hemos ocupado de los vuelos EN ESTA iteración de reparación.
        Map<VueloProgramadoId, Integer> localFlightLoad = new HashMap<>();

        for (PlanPedido plan : unassigned) {
            repararPedido(plan, s, journal, presenteUTC, localFlightLoad);
        }
    }

    private void repararPedido(PlanPedido plan, SolucionProgramacion s, ALNS.Journal journal, 
                               Instant presenteUTC, Map<VueloProgramadoId, Integer> localFlightLoad) {
        
        int demandaRestante = plan.getDemanda();
        List<RutaAsignada> nuevasRutas = new ArrayList<>();
        
        // Evitamos bucles infinitos si no encontramos ruta
        int intentos = 0;
        int maxIntentos = 5; 

        // BUCLE "LÍQUIDO": Mientras falte carga, seguimos buscando rutas
        while (demandaRestante > 0 && intentos < maxIntentos) {
            intentos++;

            // A. Buscar la mejor ruta disponible considerando la carga local acumulada
            RutaCandidata mejorCandidata = null;
            double mejorTiempo = Double.MAX_VALUE;

            for (String origen : sedesCandidatas) {
                RutaCandidata candidata = dijkstraSplit(origen, plan, presenteUTC, s.getCargaPorVuelo(), localFlightLoad);
                if (candidata != null) {
                    double tiempo = Duration.between(plan.getCreadoUtc(), candidata.llegadaFinal).toMinutes();
                    if (tiempo < mejorTiempo) {
                        mejorTiempo = tiempo;
                        mejorCandidata = candidata;
                    }
                }
            }

            if (mejorCandidata == null) break; // No hay ruta física posible para el resto

            // B. Calcular cuánto cabe realmente en esa ruta (Cuello de botella)
            int capacidadRuta = calcularCapacidadReal(mejorCandidata.tramosIds, s, journal, localFlightLoad);
            
            // C. Determinar cuánto enviamos (lo que falta o lo que cabe)
            int aEnviar = Math.min(demandaRestante, capacidadRuta);
            
            if (aEnviar <= 0) break; // La ruta existe pero está llena (edge case)

            // D. Construir la ruta asignada y actualizar estado
            List<TramoAsignado> tramosFinales = new ArrayList<>();
            for (VueloProgramadoId vid : mejorCandidata.tramosIds) {
                tramosFinales.add(new TramoAsignado(vid, aEnviar, vid.getLlegadaUtc()));
                // Actualizamos mapa local inmediatamente para que el siguiente ciclo del while lo vea
                localFlightLoad.merge(vid, aEnviar, Integer::sum);
            }
            
            // Reservar en Journal (Almacenes)
            ejecutarReservas(journal, plan, tramosFinales, aEnviar);
            
            // Actualizar Carga Global (Vuelos)
            for (VueloProgramadoId vid : mejorCandidata.tramosIds) {
                s.getCargaPorVuelo().asignar(vid, aEnviar);
            }

            nuevasRutas.add(new RutaAsignada(aEnviar, tramosFinales));
            demandaRestante -= aEnviar;
        }

        // E. Guardar el plan actualizado (incluso si está incompleto, es mejor que nada)
        if (!nuevasRutas.isEmpty()) {
            actualizarSolucion(s, plan, nuevasRutas);
        }
    }

    // --- DIJKSTRA CONSCIENTE DEL ESTADO LOCAL ---
    
    private RutaCandidata dijkstraSplit(String origen, PlanPedido plan, Instant presenteUTC, 
                                        CargaPorVuelo cargaGlobal, Map<VueloProgramadoId, Integer> cargaLocal) {
        String destino = plan.getAeropuertoDestino();
        if (origen.equals(destino)) return null;

        PriorityQueue<Node> pq = new PriorityQueue<>();
        Map<String, Instant> bestArrival = new HashMap<>();

        Instant startTime = plan.getCreadoUtc().isAfter(presenteUTC) ? plan.getCreadoUtc() : presenteUTC;
        pq.add(new Node(origen, null, null, 0, startTime));
        bestArrival.put(origen, startTime);

        Node mejorNodo = null;
        int nodes = 0;

        while (!pq.isEmpty()) {
            Node current = pq.poll();
            nodes++;
            if (nodes > MAX_DIJKSTRA_NODES) break; // Poda

            if (current.llegada.isAfter(bestArrival.getOrDefault(current.ap, Instant.MAX))) continue;
            if (mejorNodo != null && current.llegada.isAfter(mejorNodo.llegada)) continue;

            if (current.ap.equals(destino)) {
                mejorNodo = current;
                break; // First valid path is usually good enough for greedy split
            }

            if (current.hops >= MAX_HOPS) continue;

            List<VueloFicha> salidas = indexVuelos.porOrigen(current.ap);
            if (salidas == null) continue;

            for (VueloFicha vf : salidas) {
                VueloProgramadoId id = vf.id();
                
                // 1. CHEQUEO DE CAPACIDAD (CONSCIENTE)
                // Capacidad Total - (Ocupado Global + Ocupado Local en este repair)
                int ocupadoGlobal = cargaGlobal.asignado(id);
                int ocupadoLocal = cargaLocal.getOrDefault(id, 0);
                int capacidadTotal = cargaGlobal.capacidad(id);
                
                // Si queda menos de 1 unidad, este vuelo es inútil
                if ((capacidadTotal - (ocupadoGlobal + ocupadoLocal)) < 1) continue;

                // 2. CHEQUEOS TEMPORALES
                Instant salidaUtc = id.getSalidaUtc();
                if (salidaUtc.isBefore(current.llegada.plusSeconds(3600))) continue; // Min 1h
                if (salidaUtc.isAfter(current.llegada.plusSeconds(3600 * 12))) continue; // Max 12h
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

        if (mejorNodo == null) return null;

        // Reconstruir solo IDs de vuelo para ser ligero
        List<VueloProgramadoId> ids = new ArrayList<>();
        Node iter = mejorNodo;
        while (iter.padre != null) {
            ids.add(iter.ultimoVuelo);
            iter = iter.padre;
        }
        Collections.reverse(ids);
        return new RutaCandidata(ids, mejorNodo.llegada);
    }

    // --- CÁLCULO DE CUELLO DE BOTELLA (Esencial para la lógica "Líquida") ---

    private int calcularCapacidadReal(List<VueloProgramadoId> rutaIds, SolucionProgramacion s, 
                                      ALNS.Journal journal, Map<VueloProgramadoId, Integer> localFlightLoad) {
        int minCap = Integer.MAX_VALUE;

        // 1. Vuelos
        for (VueloProgramadoId vid : rutaIds) {
            int cap = s.getCargaPorVuelo().capacidad(vid);
            int ocupado = s.getCargaPorVuelo().asignado(vid) + localFlightLoad.getOrDefault(vid, 0);
            minCap = Math.min(minCap, cap - ocupado);
        }

        // 2. Almacenes (Aproximación rápida consultando el Journal Global)
        // Nota: No usamos un mapa local para almacenes por complejidad, confiamos en el Journal.
        // Si el journal dice que está lleno, reducimos el batch.
        for (int i = 0; i < rutaIds.size(); i++) {
            VueloProgramadoId v = rutaIds.get(i);
            
            // Check Origen (Espera)
            if (!HUBS.contains(v.getOrigen())) {
                // Asumimos peor caso: mirar capacidad en el momento de salida
                // (Para ser exactos deberíamos mirar intervalo llegada_prev -> salida, pero esto es aproximación rápida)
                int capAlmacen = journal.getOcc().maxReservable(v.getOrigen(), v.getSalidaUtc().minusSeconds(60), v.getSalidaUtc());
                minCap = Math.min(minCap, capAlmacen);
            }
            
            // Check Destino (Si es conexión)
            if (i < rutaIds.size() - 1 && !HUBS.contains(v.getDestino())) {
                // Conexión: Llegada vuelo actual -> Salida vuelo siguiente
                VueloProgramadoId next = rutaIds.get(i+1);
                int capAlmacen = journal.getOcc().maxReservable(v.getDestino(), v.getLlegadaUtc(), next.getSalidaUtc());
                minCap = Math.min(minCap, capAlmacen);
            }
        }

        return Math.max(0, minCap);
    }

    // --- UTILS ---

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
            if (!HUBS.contains(v.getDestino())) {
                Instant fin = v.getLlegadaUtc().plus(Duration.ofHours(2));
                journal.reservar(v.getDestino(), v.getLlegadaUtc(), fin, q);
            }
        }
    }

    private void actualizarSolucion(SolucionProgramacion s, PlanPedido p, List<RutaAsignada> nuevasRutas) {
        // Opción B (Construcción manual si toBuilder no está disponible)
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
    private record RutaCandidata(List<VueloProgramadoId> tramosIds, Instant llegadaFinal) {}
    
    private record Node(String ap, VueloProgramadoId ultimoVuelo, Node padre, int hops, Instant llegada) implements Comparable<Node> {
        @Override public int compareTo(Node o) { return this.llegada.compareTo(o.llegada); }
    }
}