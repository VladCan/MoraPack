package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators;


import java.util.*;

import pe.edu.pucp.morapack.airscheduler.orders.domain.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.Solution;

public class RandomRemoval implements DestructionOperator {
    private final int porcentaje;
    private final Random rnd = new Random();

    public RandomRemoval(int porcentaje) {
        this.porcentaje = porcentaje;
    }

    @Override
    public void destroy(Solution s) {
        List<Pedido> pedidos = new ArrayList<>(s.getAsignaciones().keySet());
        int n = pedidos.size() * porcentaje / 100;
        for (int i = 0; i < n; i++) {
            Pedido p = pedidos.get(rnd.nextInt(pedidos.size()));
            s.getAsignaciones().get(p).clear(); // eliminar asignación
        }
    }
}
