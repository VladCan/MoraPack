package pe.edu.pucp.morapack.airscheduler.engine.flights.model;

import java.time.Instant;

public record OcupacionAlmacen(String aeropuerto, Instant desde, Instant hasta, int cantidad) {}
