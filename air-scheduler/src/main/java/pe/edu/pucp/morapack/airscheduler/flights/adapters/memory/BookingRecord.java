package pe.edu.pucp.morapack.airscheduler.flights.adapters.memory;

import java.time.Instant;

public final class BookingRecord {
    public final int batchNo;
    public final int idPedido;
    public final String origen;
    public final String destino;
    public final Instant depUtc;
    public final Instant arrUtc;
    public final int cantidad;

    public BookingRecord(int batchNo, int idPedido, String origen, String destino,
                         Instant depUtc, Instant arrUtc, int cantidad) {
        this.batchNo = batchNo; this.idPedido = idPedido;
        this.origen = origen; this.destino = destino;
        this.depUtc = depUtc; this.arrUtc = arrUtc; this.cantidad = cantidad;
    }
}
