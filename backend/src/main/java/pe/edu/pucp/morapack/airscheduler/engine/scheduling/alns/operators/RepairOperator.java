package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import java.time.Instant;

import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.SolucionProgramacion;

public interface RepairOperator {
    void repair(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC);
}