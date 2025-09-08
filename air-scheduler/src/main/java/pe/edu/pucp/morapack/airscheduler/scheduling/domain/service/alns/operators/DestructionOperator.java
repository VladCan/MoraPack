package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators;

import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.Solution;

public interface DestructionOperator {
    void destroy(Solution s);
}
