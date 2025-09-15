package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed;

import java.time.Duration;

import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosEdge;

/**
 * Tweaks pedidos:
 * - Penaliza cada vuelo con un costo fijo (favorece directos si la espera es similar).
 * - Costo por minuto de espera.
 * - Esperar en el aeropuerto destino puede ser más barato (multiplicador < 1).
 */
public final class PreferredCostPolicy implements CostPolicy {
    private final long waitCostPerMinute;      // p.ej. 1
    private final long flightFixedCost;        // p.ej. 10
    private final double waitAtDestMultiplier; // p.ej. 0.2 (barato en destino)
    private final String destIcao;             // destino del pedido (opcional; null = sin descuento)

    public PreferredCostPolicy(long waitPerMin, long flightPenalty, double waitAtDestMult, String destIcao) {
        this.waitCostPerMinute = waitPerMin;
        this.flightFixedCost = flightPenalty;
        this.waitAtDestMultiplier = waitAtDestMult;
        this.destIcao = destIcao;
    }

    @Override
    public long costOf(VuelosEdge e, Duration waitDurationIfAny) {
        switch (e.getType()) {
            case SUPPLY:
                return 0L;
            case FLIGHT:
                return flightFixedCost;
            case WAIT:
                long mins = Math.max(0, waitDurationIfAny == null ? 0 : waitDurationIfAny.toMinutes());
                boolean isDest = (e.getTo() != null && destIcao != null && destIcao.equals(e.getTo().getIcao()));
                double mult = isDest ? waitAtDestMultiplier : 1.0;
                return Math.round(mins * waitCostPerMinute * mult);
            default:
                return 0L;
        }
    }
}