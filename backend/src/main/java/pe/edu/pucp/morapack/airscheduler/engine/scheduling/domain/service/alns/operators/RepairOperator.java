package pe.edu.pucp.morapack.airscheduler.engine.scheduling.domain.service.alns.operators;

import java.time.Instant;

import pe.edu.pucp.morapack.airscheduler.engine.scheduling.domain.model.SolucionProgramacion;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.domain.service.alns.ALNS;

public interface RepairOperator {
    void repair(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC);
}