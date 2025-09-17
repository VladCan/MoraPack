package pe.edu.pucp.morapack.airscheduler.flights.domain.model;

import java.time.Instant;

public record OcupacionAlmacen(String aeropuerto, Instant desde, Instant hasta, int cantidad) {}
