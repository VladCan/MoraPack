package pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model;

import java.time.Instant;

/** Carga ya en vuelo que aterriza en un instante específico del horizonte. */
public record ArriboExogeno(Instant arriboUtc, int cantidad) {}
