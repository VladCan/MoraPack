package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service;

import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.SolucionProgramacion;

import java.time.Duration;

public final class VerificadorSLA {
    private VerificadorSLA(){}

    public static void assertBasicos(SolucionProgramacion sol, Duration ventana2h) {
        if (!sol.respetaCapacidadesVuelos())
            throw new IllegalStateException("Capacidad de vuelos violada.");
        if (!sol.respetaVentana2hTodos(ventana2h))
            throw new IllegalStateException("Ventana de 2 horas violada.");
        if (!sol.respetaSLA48hTodos())
            throw new IllegalStateException("SLA 48h violado.");
    }
}
