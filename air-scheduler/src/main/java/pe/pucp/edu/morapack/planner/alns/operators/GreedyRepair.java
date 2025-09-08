package pe.pucp.edu.morapack.planner.alns.operators;

import pe.pucp.edu.morapack.planner.*;
import pe.pucp.edu.morapack.planner.alns.model.Solution;
import pe.pucp.edu.morapack.planner.Vuelo;
import pe.pucp.edu.morapack.planner.Pedido;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;


public class GreedyRepair implements RepairOperator {
    @Override
    public void repair(Solution s) {
        for (Map.Entry<Pedido, List<Vuelo>> e : s.getAsignaciones().entrySet()) {
            if (e.getValue().isEmpty()) {
                Pedido p = e.getKey();
                // Simple heurística: primer vuelo disponible desde sedes
                for (Vuelo v : s.vuelosPorOrigen.get("SPIM")) {
                    if (v.getDestino().equals(p.getDestino()) && v.getCapacidad() >= p.getCantidad()) {
                        e.getValue().add(v);
                        break;
                    }
                }
            }
        }
    }
}