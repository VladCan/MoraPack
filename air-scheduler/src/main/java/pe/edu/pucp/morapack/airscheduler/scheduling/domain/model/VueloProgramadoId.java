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

    public VueloProgramadoId(String origen, String destino, Instant salidaUtc, Instant llegadaUtc) {
        this.origen = origen;
        this.destino = destino;
        this.salidaUtc = salidaUtc;
        this.llegadaUtc = llegadaUtc;
    }

    public VueloProgramadoId(VueloProgramadoId otro) {
        this.origen = otro.getOrigen();
        this.destino = otro.getDestino();
        this.salidaUtc = otro.getSalidaUtc();
        this.llegadaUtc = otro.getLlegadaUtc();
    }
}