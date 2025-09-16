package pe.edu.pucp.morapack.airscheduler.scheduling.domain.model;

import lombok.Value;

import java.time.Instant;

/** Asignación de una cantidad del pedido a un vuelo (directo) y su llegada. */
@Value
public class TramoAsignado {
    VueloProgramadoId vuelo;
    int cantidad;
    Instant llegadaUtc;
}