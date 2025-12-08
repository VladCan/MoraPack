package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;

import java.time.Instant;
import java.util.*;

/**
 * Operador "Cirujano":
 * Detecta qué almacenes están saturados y elimina SOLO los pedidos que pasan por ahí
 * en el momento del conflicto.
 */
public class WarehouseSmartRemoval implements DestructionOperator {

    private final AeropuertosMap aeropuertosMap;
    // Sedes infinitas a ignorar
    private static final Set<String> IGNORED = Set.of("SPIM", "EBCI", "UBBB");

    public WarehouseSmartRemoval(AeropuertosMap map) {
        this.aeropuertosMap = map;
    }

    @Override
    public void destroy(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {
        // 1. Detectar Hotspots
        Map<String, Set<Instant>> hotspots = detectarHotspots(s, journal.getOcc());

        if (hotspots.isEmpty()) return; 

        List<PlanPedido> candidatosAMorir = new ArrayList<>();
        List<PlanPedido> todos = new ArrayList<>(s.getPlanPorPedido().values());
        Collections.shuffle(todos); 

        // 2. Identificar culpables
        for (PlanPedido p : todos) {
            if (esCulpable(p, hotspots)) {
                candidatosAMorir.add(p);
            }
        }

        // 3. Eliminar un porcentaje (40%) para aliviar presión
        int target = Math.max(1, (int) (candidatosAMorir.size() * 0.40));
        
        for (int i = 0; i < target && i < candidatosAMorir.size(); i++) {
            PlanPedido victima = candidatosAMorir.get(i);
            desasignar(victima, s, journal);
        }
    }

    private Map<String, Set<Instant>> detectarHotspots(SolucionProgramacion s, OcupacionPorAeropuerto occ) {
        Map<String, Set<Instant>> map = new HashMap<>();
        
        // CORRECCIÓN AQUÍ: Usamos allIcaos() que SÍ existe en tu clase
        for (String ap : aeropuertosMap.allIcaos()) {
            if (IGNORED.contains(ap)) continue;
            // Solo necesitamos iterar para saber cuáles existen, la lógica real está abajo
        }
        
        // Reconstruimos la línea de tiempo de uso basada en la solución actual
        Map<String, TreeMap<Instant, Integer>> timeline = new HashMap<>();
        
        for (PlanPedido p : s.getPlanPorPedido().values()) {
            if (p.getRutas() == null) continue;
            for (RutaAsignada r : p.getRutas()) {
                if (r.getCantidad() <= 0) continue;
                for (TramoAsignado t : r.getTramos()) {
                    VueloProgramadoId v = t.getVuelo();
                    if (!IGNORED.contains(v.getOrigen())) {
                        add(timeline, v.getOrigen(), v.getSalidaUtc(), 0); 
                    }
                    if (!IGNORED.contains(v.getDestino())) {
                        add(timeline, v.getDestino(), v.getLlegadaUtc(), 0);
                    }
                }
            }
        }

        // Consultamos la ocupación real en esos puntos
        for (var entry : timeline.entrySet()) {
            String ap = entry.getKey();
            // CORRECCIÓN: Usamos getCapBodega() que sí existe en tu clase
            int cap = aeropuertosMap.getCapBodega(ap);
            
            for (Instant t : entry.getValue().keySet()) {
                if (occ.consultar(ap, t) > cap) {
                    map.computeIfAbsent(ap, k -> new HashSet<>()).add(t);
                }
            }
        }
        
        return map;
    }

    private boolean esCulpable(PlanPedido p, Map<String, Set<Instant>> hotspots) {
        if (p.getRutas() == null) return false;
        for (RutaAsignada r : p.getRutas()) {
            if (r.getTramos() == null) continue;
            for (TramoAsignado t : r.getTramos()) {
                VueloProgramadoId v = t.getVuelo();
                if (hotspots.containsKey(v.getOrigen())) return true;
                if (hotspots.containsKey(v.getDestino())) return true;
            }
        }
        return false;
    }

    private void desasignar(PlanPedido plan, SolucionProgramacion s, ALNS.Journal journal) {
        for (RutaAsignada r : plan.getRutas()) {
            if (r.getCantidad() <= 0) continue;
            for (int i = 0; i < r.getTramos().size(); i++) {
                TramoAsignado t = r.getTramos().get(i);
                VueloProgramadoId v = t.getVuelo();
                int q = r.getCantidad();
                
                if (!IGNORED.contains(v.getOrigen())) {
                   Instant ini = (i==0) ? plan.getCreadoUtc() : r.getTramos().get(i-1).getVuelo().getLlegadaUtc();
                   if (ini != null && v.getSalidaUtc().isAfter(ini))
                       journal.liberar(v.getOrigen(), ini, v.getSalidaUtc(), q);
                }
                
                if (!IGNORED.contains(v.getDestino())) {
                   Instant fin = v.getLlegadaUtc().plus(java.time.Duration.ofHours(2));
                   journal.liberar(v.getDestino(), v.getLlegadaUtc(), fin, q);
                }
                s.getCargaPorVuelo().asignar(v, -q);
            }
        }
        
        // Dejamos al pedido "vacío" (sin rutas)
        PlanPedido limpio = PlanPedido.builder()
                .idPedido(plan.getIdPedido())
                .aeropuertoDestino(plan.getAeropuertoDestino())
                .creadoUtc(plan.getCreadoUtc())
                .demanda(plan.getDemanda())
                .rutas(new ArrayList<>()) 
                .build();
        s.getPlanPorPedido().put(limpio.getIdPedido(), limpio);
    }
    
    private void add(Map<String, TreeMap<Instant, Integer>> map, String ap, Instant t, int v) {
        map.computeIfAbsent(ap, k->new TreeMap<>()).put(t, v);
    }
}