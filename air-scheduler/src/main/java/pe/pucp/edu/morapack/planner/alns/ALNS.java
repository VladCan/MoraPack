package pe.pucp.edu.morapack.planner.alns;
import pe.pucp.edu.morapack.planner.AeropuertosMap;
import pe.pucp.edu.morapack.planner.alns.operators.*;
import pe.pucp.edu.morapack.planner.alns.model.Solution;
import pe.pucp.edu.morapack.planner.Vuelo;
import pe.pucp.edu.morapack.planner.Pedido;
import pe.pucp.edu.morapack.planner.alns.operators.DestructionOperator;

import java.util.*;

public class ALNS {

    private final Map<String, List<Vuelo>> vuelosPorOrigen;
    private final AeropuertosMap aeropuertosMap;
    private final List<Pedido> pedidos;
    private final List<String> sedes;
    private final List<DestructionOperator> destructions;
    private final List<RepairOperator> repairs;
    private final int maxIter;

    private final Random rnd = new Random();

    public ALNS(Map<String, List<Vuelo>> vuelosPorOrigen, AeropuertosMap aeropuertosMap,
                List<Pedido> pedidos, List<String> sedes,
                List<DestructionOperator> destructions, List<RepairOperator> repairs,
                int maxIter) {
        this.vuelosPorOrigen = vuelosPorOrigen;
        this.aeropuertosMap = aeropuertosMap;
        this.pedidos = pedidos;
        this.sedes = sedes;
        this.destructions = destructions;
        this.repairs = repairs;
        this.maxIter = maxIter;
    }

    public Solution run() {
        // Solución inicial
        Solution current = new Solution(pedidos, vuelosPorOrigen, aeropuertosMap, sedes);
        current.init();

        Solution best = new Solution(current);

        for (int iter = 0; iter < maxIter; iter++) {

            // Seleccionar operadores aleatorios
            DestructionOperator destrOp = destructions.get(rnd.nextInt(destructions.size()));
            RepairOperator repairOp = repairs.get(rnd.nextInt(repairs.size()));

            // Copiar solución actual
            Solution newSol = new Solution(current);

            // Aplicar destrucción
            destrOp.destroy(newSol);

            // Aplicar reparación
            repairOp.repair(newSol);

            // Evaluar costo
            if (newSol.getCostoTotal() < best.getCostoTotal()) {
                best = new Solution(newSol);
            }

            // Criterio de aceptación simple (mejor o igual)
            if (newSol.getCostoTotal() <= current.getCostoTotal()) {
                current = new Solution(newSol);
            }
        }

        return best;
    }
}