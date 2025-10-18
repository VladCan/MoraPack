package pe.edu.pucp.morapack.airscheduler.engine.scheduling.domain.model;

import lombok.Value;

import java.time.Instant;

/** Asignación de una cantidad del pedido a un vuelo (directo) y su llegada. */
@Value
public class TramoAsignado {
    VueloProgramadoId vuelo;
    int cantidad;
    Instant llegadaUtc;

    public TramoAsignado(VueloProgramadoId vuelo, int cantidad, Instant llegadaUtc) {
        this.vuelo = vuelo;
        this.cantidad = cantidad;
        this.llegadaUtc = llegadaUtc;
    }

    public TramoAsignado(TramoAsignado otro) {
        this.vuelo = new VueloProgramadoId(otro.getVuelo()); // nueva instancia
        this.cantidad = otro.getCantidad();
        this.llegadaUtc = otro.getLlegadaUtc(); // Instant es inmutable, se puede copiar así
    }

    public VueloProgramadoId getVuelo() {
        return vuelo;
    }
}