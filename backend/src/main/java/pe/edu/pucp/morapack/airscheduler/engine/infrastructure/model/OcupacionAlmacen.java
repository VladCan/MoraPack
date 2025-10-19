package pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model;

import java.time.Instant;

public record OcupacionAlmacen(String aeropuerto, Instant desde, Instant hasta, int cantidad) {}
