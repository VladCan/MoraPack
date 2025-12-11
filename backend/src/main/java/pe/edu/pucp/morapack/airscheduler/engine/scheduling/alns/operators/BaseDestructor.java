package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;
import java.util.List;

public abstract class BaseDestructor implements DestructionOperator {
    
    protected void liberarRuta(PlanPedido plan, RutaAsignada ruta, ALNS.Journal journal, SolucionProgramacion s) {
        // Delegamos a la transacción con multiplicador -1 (LIBERAR)
        // Pasamos una lista con la única ruta a borrar para reutilizar la lógica por lotes
        RouteTransaction.aplicarCambios(s, journal, plan, List.of(ruta), -1);
    }
}