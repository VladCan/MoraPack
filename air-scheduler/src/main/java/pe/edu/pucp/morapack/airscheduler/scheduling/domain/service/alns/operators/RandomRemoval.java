package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators;


import java.util.*;

import pe.edu.pucp.morapack.airscheduler.orders.domain.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.Solucion;
import pe.edu.pucp.morapack.airscheduler.orders.domain.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.PlanPedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.SolucionProgramacion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class RandomRemoval implements DestructionOperator {
    private final int porcentaje;
    private final Random rnd = new Random();

    public RandomRemoval(int porcentaje) {
        this.porcentaje = porcentaje;
    }

    @Override
    public void destroy(SolucionProgramacion s) {
        Map<Integer, PlanPedido> planes = s.getPlanPorPedido();
        List<Integer> pedidos = new ArrayList<>(planes.keySet());
        int n = pedidos.size() * porcentaje / 100;

        for (int i = 0; i < n; i++) {
            int id = pedidos.get(rnd.nextInt(pedidos.size()));
            PlanPedido plan = planes.get(id);
            if (plan != null && plan.getTramos() != null) {
                plan.getTramosMutable().clear(); // elimina los tramos asignados
            }
        }
    }
}