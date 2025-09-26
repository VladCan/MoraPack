package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators;

import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.SolucionProgramacion;

public interface DestructionOperator {
    void destroy(SolucionProgramacion s);
}
