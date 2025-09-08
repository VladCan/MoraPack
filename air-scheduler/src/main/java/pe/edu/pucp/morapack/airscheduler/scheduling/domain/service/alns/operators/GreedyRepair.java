package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators;

import pe.edu.pucp.morapack.airscheduler.*;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.Vuelo;
import pe.edu.pucp.morapack.airscheduler.orders.domain.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.Solution;


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