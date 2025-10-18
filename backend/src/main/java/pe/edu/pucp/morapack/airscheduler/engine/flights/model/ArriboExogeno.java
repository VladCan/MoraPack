package pe.edu.pucp.morapack.airscheduler.engine.flights.model;

import java.time.Instant;

/** Carga ya en vuelo que aterriza en un instante específico del horizonte. */
public record ArriboExogeno(Instant arriboUtc, int cantidad) {}
