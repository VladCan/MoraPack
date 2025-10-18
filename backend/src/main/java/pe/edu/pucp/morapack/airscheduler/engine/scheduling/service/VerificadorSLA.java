package pe.edu.pucp.morapack.airscheduler.engine.scheduling.service;

import java.time.Duration;

import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.SolucionProgramacion;

public final class VerificadorSLA {
    private VerificadorSLA(){}
    public static void assertBasicos(SolucionProgramacion sol, Duration ventana46h) {
        if (!sol.respetaCapacidadesVuelos())
            throw new IllegalStateException("Capacidad de vuelos violada.");

        /*if(!sol.respetaSLAConPickupTodos(ventana46h))
            throw new IllegalStateException("SLA con pickup violado.");
        if (!sol.respetaSLA48hTodos())
            throw new IllegalStateException("SLA 48h violado.");*/
    }
}
