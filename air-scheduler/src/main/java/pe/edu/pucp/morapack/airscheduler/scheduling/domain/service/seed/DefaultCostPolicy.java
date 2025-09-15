package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed;

import java.time.Duration;

import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosEdge;

public final class DefaultCostPolicy implements CostPolicy {
    // Simple: preferir menos WAIT. FLIGHT y SUPPLY sin costo.
    private final long waitCostPerMinute;

    public DefaultCostPolicy(long waitCostPerMinute) { this.waitCostPerMinute = waitCostPerMinute; }

    @Override
    public long costOf(VuelosEdge e, Duration waitDur) {
        return switch (e.getType()) {
            case WAIT   -> Math.max(0, waitDur == null ? 0 : waitDur.toMinutes()) * waitCostPerMinute;
            case FLIGHT -> 0L;
            case SUPPLY -> 0L;
        };
    }
}