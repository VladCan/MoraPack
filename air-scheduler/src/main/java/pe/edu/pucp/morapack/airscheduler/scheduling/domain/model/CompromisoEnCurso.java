package pe.edu.pucp.morapack.airscheduler.scheduling.domain.model;

import java.time.Instant;
import lombok.Value;

@Value
public class CompromisoEnCurso {
    long pedidoId;
    String origen;     // ICAO
    String destino;    // ICAO
    Instant salidaUtc;
    Instant llegadaUtc;
    int cantidad;
}