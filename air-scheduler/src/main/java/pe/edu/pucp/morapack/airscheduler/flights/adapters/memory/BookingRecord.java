package pe.edu.pucp.morapack.airscheduler.flights.adapters.memory;

import java.time.Instant;

/** Reserva materializada por la seed (va en vuelo hasta arrUtc). */
public final class BookingRecord {
    public final int batchNo, orderId, cantidad;
    public final String origen, destino;
    public final Instant depUtc, arrUtc;

    public BookingRecord(int batchNo, int orderId, String origen, String destino,
            Instant depUtc, Instant arrUtc, int cantidad) {
        this.batchNo = batchNo;
        this.orderId = orderId;
        this.origen = origen;
        this.destino = destino;
        this.depUtc = depUtc;
        this.arrUtc = arrUtc;
        this.cantidad = cantidad;
    }
}