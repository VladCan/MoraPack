package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import lombok.RequiredArgsConstructor;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;
import java.time.Instant;
import java.util.*;

@RequiredArgsConstructor
public class RandomRemoval extends BaseDestructor {
    private final int porcentaje;
    private final Random rnd = new Random();

    @Override
    public void destroy(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {
        List<Integer> ids = new ArrayList<>(s.getPlanPorPedido().keySet());
        if (ids.isEmpty()) return;

        int target = Math.max(1, ids.size() * porcentaje / 100);
        Collections.shuffle(ids, rnd);

        int borrados = 0;
        for (Integer id : ids) {
            if (borrados >= target) break;
            
            PlanPedido plan = s.getPlanPorPedido().get(id);
            if (plan.getRutas() == null || plan.getRutas().isEmpty()) continue;

            List<RutaAsignada> keep = new ArrayList<>();
            boolean cambio = false;

            for (RutaAsignada r : plan.getRutas()) {
                // Blindaje temporal: si ya voló, no se toca
                if (!r.getTramos().isEmpty() && !r.getTramos().get(0).getVuelo().getSalidaUtc().isAfter(presenteUTC)) {
                    keep.add(r);
                    continue;
                }
                
                liberarRuta(plan, r, journal, s); // Usa el método de BaseDestructor
                cambio = true;
            }

            if (cambio) {
                // Reconstruir plan vacío
                PlanPedido limpio = PlanPedido.builder()
                        .idPedido(plan.getIdPedido())
                        .aeropuertoDestino(plan.getAeropuertoDestino())
                        .creadoUtc(plan.getCreadoUtc())
                        .demanda(plan.getDemanda())
                        .rutas(keep)
                        .build();
                s.getPlanPorPedido().put(id, limpio);
                borrados++;
            }
        }
    }
}