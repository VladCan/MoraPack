package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed.model;

import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosEdge;

public record PathArc(VuelosEdge edge, int flowPushed) {
}