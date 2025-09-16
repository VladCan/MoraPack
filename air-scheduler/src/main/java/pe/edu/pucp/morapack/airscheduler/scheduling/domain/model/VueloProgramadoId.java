package pe.edu.pucp.morapack.airscheduler.scheduling.domain.model;

import lombok.Value;

import java.time.Instant;

/** Identifica un vuelo programado específico (un arco FLIGHT del TEG). */
@Value
public class VueloProgramadoId {
    String origen;
    String destino;
    Instant salidaUtc;
    Instant llegadaUtc;
}