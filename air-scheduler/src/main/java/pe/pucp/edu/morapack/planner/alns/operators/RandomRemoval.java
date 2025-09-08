package pe.pucp.edu.morapack.planner.alns.operators;

import pe.pucp.edu.morapack.planner.alns.model.Solution;
import pe.pucp.edu.morapack.planner.Pedido;
import java.util.*;

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
