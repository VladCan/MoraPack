package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed;

import java.time.Duration;

import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosEdge;


/** Politica de costo por arco del TEG (espera, vuelo, supply). */
public interface CostPolicy {
    /**
     * Costo del arco.
     * @param e arco del TEG
     * @param waitDurationIfAny duración de espera si el arco es WAIT; null en FLIGHT/SUPPLY
     */
    long costOf(VuelosEdge e, Duration waitDurationIfAny);
}