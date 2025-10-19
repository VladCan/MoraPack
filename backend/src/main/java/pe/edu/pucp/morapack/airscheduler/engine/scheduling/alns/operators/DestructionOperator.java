package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import java.time.Instant;

import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.SolucionProgramacion;

public interface DestructionOperator {
    void destroy(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC);
}
