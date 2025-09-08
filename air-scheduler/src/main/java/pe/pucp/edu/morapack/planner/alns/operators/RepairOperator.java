package pe.pucp.edu.morapack.planner.alns.operators;

import pe.pucp.edu.morapack.planner.AeropuertosMap;
import pe.pucp.edu.morapack.planner.Pedido;
import pe.pucp.edu.morapack.planner.Vuelo;
import pe.pucp.edu.morapack.planner.alns.model.Solution;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public interface RepairOperator {
    void repair(Solution s);
}