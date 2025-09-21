package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns;

import java.time.Duration;
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
    private final int maxIter = 50;                 // iteraciones máximas
    private final double tasaCambio = 0.3;          // probabilidad de aceptar peores soluciones

    public SolucionProgramacion ejecutar(SolucionProgramacion solucionInicial) {

        // Copias profundas desde el inicio
        SolucionProgramacion mejorSolucion = new SolucionProgramacion(solucionInicial);
        SolucionProgramacion solucionActual = new SolucionProgramacion(solucionInicial);

        for (int iter = 0; iter < maxIter; iter++) {

            // Seleccionar operadores aleatorios
            DestructionOperator destrOp = destructions.get(rnd.nextInt(destructions.size()));
            RepairOperator repairOp = repairs.get(rnd.nextInt(repairs.size()));

            // Copia profunda de la solución actual
            SolucionProgramacion nuevaSol = new SolucionProgramacion(solucionActual);

            // Aplicar destrucción
            destrOp.destroy(nuevaSol);

            // Aplicar reparación
            repairOp.repair(nuevaSol);

            // Evaluar costos
            double costoNueva = getCostoTotal(nuevaSol);
            double costoActual = getCostoTotal(solucionActual);
            double costoMejor = getCostoTotal(mejorSolucion);

            // Actualizar mejor solución
            if (costoNueva < costoMejor) {
                mejorSolucion = new SolucionProgramacion(nuevaSol); // copia profunda
                //System.out.println("Cambio de mejor solución en iteración " + iter);
            }

            // Aceptar nueva solución (según criterio)
            if (costoNueva <= costoActual || rnd.nextDouble() < tasaCambio) {
                solucionActual = new SolucionProgramacion(nuevaSol); // copia profunda
            }
        }

        return mejorSolucion;
    }

    private double getCostoTotal(SolucionProgramacion sol) {
        double costo = 0;

        // Penalización por pedidos incompletos (10 por cada uno)
        costo += sol.pedidosIncompletos().size() * 10;

        // Penalización por incumplimiento de capacidad
        if (!sol.respetaCapacidadesVuelos()) {
            costo += 50;
        }

        // Penalización por incumplimiento SLA 48h (cada pedido fuera suma 20)
        long fueraSLA48 = sol.getPlanPorPedido().values().stream()
                .filter(p -> !p.respetaSLA(Duration.ofHours(48)))
                .count();
        costo += fueraSLA48 * 20;

        // Penalización por incumplimiento SLA con pickup de 2h (cada pedido fuera suma 15)
        long fueraPickup = sol.getPlanPorPedido().values().stream()
                .filter(p -> !p.respetaSLAConPickup(Duration.ofHours(46))) // SLA48 - 2h
                .count();
        costo += fueraPickup * 15;

        return costo;
    }

}
