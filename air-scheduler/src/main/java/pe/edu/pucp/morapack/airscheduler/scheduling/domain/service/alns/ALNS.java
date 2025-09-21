package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns;


import java.time.Instant;
import java.util.*;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.orders.domain.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.SolucionProgramacion;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators.DestructionOperator;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators.RepairOperator;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ALNS {

    private final VuelosTEG teg;
    private final List<Pedido> pedidos;
    private final List<DestructionOperator> destructions;
    private final List<RepairOperator> repairs;
    private final Instant presenteUTC;
    private final Random rnd = new Random();        // RNG compartido para selección de operadores
    private final int maxIter = 50;                // iteraciones máximas
    private final double tasaCambio = 0.3;          // probabilidad de aceptar peores soluciones

    public SolucionProgramacion ejecutar(SolucionProgramacion solucionInicial) {

        SolucionProgramacion mejorSolucion = solucionInicial;
        SolucionProgramacion solucionActual = solucionInicial;

        for (int iter = 0; iter < maxIter; iter++) {

            // Seleccionar un operador aleatorio de destrucción y reparación
            DestructionOperator destrOp = destructions.get(rnd.nextInt(destructions.size()));
            RepairOperator repairOp = repairs.get(rnd.nextInt(repairs.size()));

            // Copiar la solución actual
            SolucionProgramacion nuevaSol = SolucionProgramacion.builder()
                    .planPorPedido(solucionActual.asMap())
                    .cargaPorVuelo(solucionActual.getCargaPorVuelo())
                    .build();

            // Aplicar destrucción
            destrOp.destroy(nuevaSol);

            // Aplicar reparación
            repairOp.repair(nuevaSol);

            // Evaluar costo
            double costoNueva = getCostoTotal(nuevaSol);
            double costoActual = getCostoTotal(solucionActual);
            double costoMejor = getCostoTotal(mejorSolucion);

            // Actualizar mejor solución
            if (costoNueva < costoMejor) {
                mejorSolucion = nuevaSol;
                System.out.println("Cambio");
            }

            // Criterio de aceptación simple (mejor o igual, o con tasa de cambio)
            if (costoNueva <= costoActual || rnd.nextDouble() < tasaCambio) {
                solucionActual = nuevaSol;
            }
        }

        return mejorSolucion;
    }

    private double getCostoTotal(SolucionProgramacion sol) {
        double costo = 0;

        // Pedidos incompletos
        costo += sol.pedidosIncompletos().size() * 10;

        // Penalización por incumplimiento de capacidad
        if (!sol.respetaCapacidadesVuelos()) costo += 50;

        // Penalización por incumplimiento de ventana 2h
        /*long fueraVentana = sol.getPlanPorPedido().values().stream()
                .filter(p -> !p.respetaVentana2h(java.time.Duration.ofHours(2)))
                .count();
        costo += fueraVentana * 5;*/

        return costo;
    }
}
