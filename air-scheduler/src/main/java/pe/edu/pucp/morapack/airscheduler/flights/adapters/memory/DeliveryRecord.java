package pe.edu.pucp.morapack.airscheduler.flights.adapters.memory;

import java.time.Instant;

public final class DeliveryRecord {
    public final int batchNo;
    public final int idPedido;
    public final String aeropuerto;
    public final Instant timeUtc;
    public final int cantidad;

    public DeliveryRecord(int batchNo, int idPedido, String aeropuerto, Instant timeUtc, int cantidad) {
        this.batchNo = batchNo; this.idPedido = idPedido;
        this.aeropuerto = aeropuerto; this.timeUtc = timeUtc; this.cantidad = cantidad;
    }
}
