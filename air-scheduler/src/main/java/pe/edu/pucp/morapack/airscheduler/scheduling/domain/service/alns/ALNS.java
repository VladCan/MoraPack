package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns;


import java.time.Instant;
import java.util.*;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.Vuelo;
import pe.edu.pucp.morapack.airscheduler.orders.domain.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.Solucion;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.SolucionProgramacion;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators.DestructionOperator;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators.RepairOperator;

public class ALNS {

    /*private final Map<String, List<Vuelo>> vuelosPorOrigen;
    private final AeropuertosMap aeropuertosMap;
    private final List<Pedido> pedidos;
    private final List<String> sedes;
    */
    private final List<DestructionOperator> destructions;
    private final List<RepairOperator> repairs;
    private final VuelosTEG teg;
    private final List<Pedido> pedidos;
    private final Random rnd = new Random();
    private  final Instant presenteUTC;
    //Parametros ALNS
    private final int maxIter = 100;
    private final double tasaCambio = 0.3;  // para probabilidades de aceptar peor solución

    public ALNS(VuelosTEG teg, List<Pedido> pedidos,
                List<DestructionOperator> destructions,
                List<RepairOperator> repairs, Instant presenteUTC) {
        this.teg = teg;
        this.pedidos = pedidos;
        this.destructions = destructions;
        this.repairs = repairs;
        this.presenteUTC = presenteUTC;
    }
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