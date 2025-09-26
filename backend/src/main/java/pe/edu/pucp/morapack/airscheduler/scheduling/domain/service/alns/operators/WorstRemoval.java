package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators;

import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.PlanPedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.SolucionProgramacion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class WorstRemoval implements DestructionOperator {
    private final int porcentaje;
    private final Random rnd = new Random();

    @Override
    public void destroy(SolucionProgramacion s) {
        Map<Integer, PlanPedido> planes = s.getPlanPorPedido();
        if (planes.isEmpty()) return;

        // Heurística: ordenar planes por cantidad total de tramos 
        // (más "complejos" primero)
        List<PlanPedido> listaPlanes = new ArrayList<>(planes.values());
        listaPlanes.sort(Comparator.comparingInt(
                p -> -p.getTramosAplanados().size()
        ));

        int n = listaPlanes.size() * porcentaje / 100;

        for (int i = 0; i < n && i < listaPlanes.size(); i++) {
            PlanPedido plan = listaPlanes.get(i);
            // destruir = vaciar todos los tramos de sus rutas
            plan.limpiarTramos(); 
        }
    }
}
