package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.PlanPedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.SolucionProgramacion;

import java.util.*;

public class RandomRemoval implements DestructionOperator {
    private final int porcentaje;
    private final Random rnd = new Random();

    public RandomRemoval(int porcentaje) {
        this.porcentaje = porcentaje;
    }

    @Override
    public void destroy(SolucionProgramacion s) {
        Map<Integer, PlanPedido> planes = s.asMap();  // << usar asMap()
        List<Integer> pedidos = new ArrayList<>(planes.keySet());

        if (pedidos.isEmpty()) return;

        int n = Math.max(1, pedidos.size() * porcentaje / 100); // al menos 1
        Collections.shuffle(pedidos, rnd); // evitar repetidos

        for (int i = 0; i < n; i++) {
            int id = pedidos.get(i);
            PlanPedido plan = planes.get(id);
            if (plan != null) {
                plan.limpiarTramos();
            }
        }
    }
}