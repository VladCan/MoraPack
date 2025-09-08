package pe.pucp.edu.morapack.planner.alns.operators;

import pe.pucp.edu.morapack.planner.Pedido;
import pe.pucp.edu.morapack.planner.Vuelo;
import pe.pucp.edu.morapack.planner.alns.model.Solution;

import java.util.*;

public class WorstRemoval implements DestructionOperator {
    private final int porcentaje;

    public WorstRemoval(int porcentaje) {
        this.porcentaje = porcentaje;
    }

    @Override
    public void destroy(Solution s) {
        Map<Pedido, Double> costos = new HashMap<>();
        for (Map.Entry<Pedido, List<Vuelo>> e : s.getAsignaciones().entrySet()) {
            double costo = 0;
            for (Vuelo v : e.getValue()) costo += v.getCosto();
            costos.put(e.getKey(), costo);
        }

        // Ordenar por costo descendente
        List<Pedido> pedidos = new ArrayList<>(costos.keySet());
        pedidos.sort((p1, p2) -> Double.compare(costos.get(p2), costos.get(p1)));

        int n = pedidos.size() * porcentaje / 100;
        for (int i = 0; i < n && i < pedidos.size(); i++) {
            s.getAsignaciones().get(pedidos.get(i)).clear();
        }
    }
}

