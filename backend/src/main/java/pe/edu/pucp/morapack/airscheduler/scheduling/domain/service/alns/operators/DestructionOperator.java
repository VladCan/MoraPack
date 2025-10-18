package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators;

import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.SolucionProgramacion;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.ALNS;

import java.time.Instant;

public interface DestructionOperator {
    void destroy(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC);
}
