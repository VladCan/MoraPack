package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import lombok.RequiredArgsConstructor;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Destructor Inteligente para RF3 (Capacidad de Almacén).
 * 1. Identifica el aeropuerto con mayor saturación.
 * 2. Elimina pedidos que pasan por ese aeropuerto para liberar espacio.
 */
@RequiredArgsConstructor
public class WarehouseCrisisRemoval extends BaseDestructor {

    private final int numeroABorrar;
    private final AeropuertosMap aeropuertosMap;
    private final Random rnd = new Random();

    // RF5: Estos aeropuertos son Hubs/Sedes principales con capacidad "infinita"
    // No tiene sentido intentar arreglar su capacidad borrando pedidos.
    private static final Set<String> HUBS_IGNORAR = Set.of("SPIM", "EBCI", "UBBB");

    @Override
    public void destroy(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {
        
        // 1. ENCONTRAR EL AEROPUERTO CRÍTICO (CUELLO DE BOTELLA)
        String aeropuertoCritico = null;
        int maxExceso = 0;

        // Recorremos todos los aeropuertos registrados en el mapa estático
        for (String codigoAp : aeropuertosMap.keys()) {
            if (HUBS_IGNORAR.contains(codigoAp)) continue;

            int capacidadNominal = aeropuertosMap.getCapBodega(codigoAp);
            
            // Consultamos al Journal/Ocupación cuál es el pico máximo registrado en toda la simulación
            // Asumo que tu OcupacionPorAeropuerto tiene un método para esto. 
            // Si no, necesitarás implementarlo: devuelve el valor más alto del timeline de ese aeropuerto.
            int ocupacionPico = journal.getOcc().getMaxOcupacionGlobal(codigoAp);

            if (ocupacionPico > capacidadNominal) {
                int exceso = ocupacionPico - capacidadNominal;
                if (exceso > maxExceso) {
                    maxExceso = exceso;
                    aeropuertoCritico = codigoAp;
                }
            }
        }

        // Si no hay violaciones de capacidad, este operador no hace nada (o podría delegar a Random)
        if (aeropuertoCritico == null) {
            return; 
        }

        final String targetAp = aeropuertoCritico;

        // 2. IDENTIFICAR VÍCTIMAS (PEDIDOS QUE USAN EL AEROPUERTO CRÍTICO)
        List<PlanPedido> victimas = s.getPlanPorPedido().values().stream()
            .filter(p -> p.getRutas() != null && !p.getRutas().isEmpty())
            .filter(p -> pasaPorAeropuerto(p, targetAp))
            .collect(Collectors.toList());

        if (victimas.isEmpty()) return;

        // 3. BORRAR VÍCTIMAS
        // Mezclamos para no borrar siempre los mismos y caer en ciclos
        Collections.shuffle(victimas, rnd);
        
        int borrados = 0;
        for (PlanPedido plan : victimas) {
            if (borrados >= numeroABorrar) break;

            List<RutaAsignada> rutasToKeep = new ArrayList<>();
            boolean huboCambio = false;

            // Iteramos rutas para liberar recursos
            for (RutaAsignada ruta : plan.getRutas()) {
                
                // Blindaje: Si ya salió el primer vuelo, no se puede borrar
                if (ruta.getTramos() != null && !ruta.getTramos().isEmpty()) {
                    Instant salida = ruta.getTramos().get(0).getVuelo().getSalidaUtc();
                    if (!salida.isAfter(presenteUTC)) {
                        rutasToKeep.add(ruta);
                        continue;
                    }
                }

                // Liberar recursos (usando lógica de BaseDestructor)
                liberarRuta(plan, ruta, journal, s);
                huboCambio = true;
            }

            // Si liberamos algo, actualizamos el plan
            if (huboCambio) {
                PlanPedido nuevoPlan = PlanPedido.builder()
                        .idPedido(plan.getIdPedido())
                        .aeropuertoDestino(plan.getAeropuertoDestino())
                        .creadoUtc(plan.getCreadoUtc())
                        .demanda(plan.getDemanda())
                        .rutas(rutasToKeep) // Solo conservamos las que ya volaron
                        .build();

                s.getPlanPorPedido().put(nuevoPlan.getIdPedido(), nuevoPlan);
                borrados++;
            }
        }
    }

    /**
     * Verifica si alguna ruta del pedido toca el aeropuerto objetivo (Origen, Destino o Escala).
     */
    private boolean pasaPorAeropuerto(PlanPedido plan, String targetAp) {
        for (RutaAsignada r : plan.getRutas()) {
            if (r.getTramos() == null) continue;
            for (TramoAsignado t : r.getTramos()) {
                if (t.getVuelo().getOrigen().equals(targetAp) || 
                    t.getVuelo().getDestino().equals(targetAp)) {
                    return true;
                }
            }
        }
        return false;
    }
}