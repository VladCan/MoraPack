package pe.edu.pucp.morapack.airscheduler.flights.adapters.memory;

import java.time.Instant;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.LiveTEGState.FlightKey;

/** Entrega cristalizada al aterrizar (se suma a inventario). */
public final class DeliveryRecord {
    public final int batchNo, orderId, cantidadAceptada;
    public final String destino;
    public final Instant arrUtc;
    public final FlightKey flight;

    public DeliveryRecord(int batchNo, int orderId, String destino, Instant arrUtc,
            int cantidadAceptada, FlightKey flight) {
        this.batchNo = batchNo;
        this.orderId = orderId;
        this.destino = destino;
        this.arrUtc = arrUtc;
        this.cantidadAceptada = cantidadAceptada;
        this.flight = flight;
    }
}