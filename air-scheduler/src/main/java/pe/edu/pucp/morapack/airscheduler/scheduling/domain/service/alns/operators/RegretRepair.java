package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators;



import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import pe.edu.pucp.morapack.airscheduler.flights.domain.model.Vuelo;
import pe.edu.pucp.morapack.airscheduler.orders.domain.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.Solution;

public class RegretRepair implements RepairOperator {
    private final int k; // número de alternativas a considerar

    public RegretRepair(int k) {
        this.k = k;
    }

    @Override
    public void repair(Solution s) {
        for (Map.Entry<Pedido, List<Vuelo>> e : s.getAsignaciones().entrySet()) {
            if (!e.getValue().isEmpty()) continue;

            Pedido p = e.getKey();
            List<Vuelo> candidatos = new ArrayList<>();

            // Generar todos los vuelos posibles desde sedes
            for (String sede : s.sedes) {
                List<Vuelo> vuelos = s.vuelosPorOrigen.get(sede);
                if (vuelos != null) {
                    for (Vuelo v : vuelos) {
                        if (v.getDestino().equals(p.getDestino()) &&
                                v.getCapacidad() >= p.getCantidad()) {
                            candidatos.add(v);
                        }
                    }
                }
            }

            if (candidatos.isEmpty()) continue;

            // Ordenar por costo (ascendente)
            candidatos.sort(Comparator.comparingDouble(Vuelo::getCosto));

            // Calcular "regret" como diferencia entre mejor y k-ésimo mejor
            Vuelo elegido;
            if (candidatos.size() <= k) {
                elegido = candidatos.get(0);
            } else {
                double regret = candidatos.get(k).getCosto() - candidatos.get(0).getCosto();
                elegido = candidatos.get(0); // seleccionamos el de menor costo
            }

            e.getValue().add(elegido);
        }
    }
}