package pe.edu.pucp.morapack.airscheduler.scheduling.adapters.io;

import java.io.*;
import java.nio.file.*;
import java.util.List;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.BookingRecord;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.DeliveryRecord;

/** Escribe historial de bookings y entregas en CSVs separados dentro de un directorio. */
public final class CsvHistoryWriter implements AutoCloseable {

    private final BufferedWriter bookings;
    private final BufferedWriter deliveries;

    public CsvHistoryWriter(Path dir) throws IOException {
        Files.createDirectories(dir);
        bookings   = Files.newBufferedWriter(dir.resolve("bookings.csv"),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        deliveries = Files.newBufferedWriter(dir.resolve("deliveries.csv"),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        // cabeceras si los archivos están vacíos
        if (Files.size(dir.resolve("bookings.csv")) == 0) {
            bookings.write("batchNo,idPedido,origen,destino,depUtc,arrUtc,cantidad\n");
            bookings.flush();
        }
        if (Files.size(dir.resolve("deliveries.csv")) == 0) {
            deliveries.write("batchNo,idPedido,aeropuerto,timeUtc,cantidad\n");
            deliveries.flush();
        }
    }

    public void appendBookings(List<BookingRecord> list) throws IOException {
        for (var b : list) {
            bookings.write(b.batchNo + "," + b.orderId + "," + b.origen + "," + b.destino + ","
                    + b.depUtc + "," + b.arrUtc + "," + b.cantidad + "\n");
        }
        bookings.flush();
    }

    public void appendDeliveries(List<DeliveryRecord> list) throws IOException {
        for (var d : list) {
            deliveries.write(d.batchNo + "," + d.orderId + "," + d.destino + ","
                    + d.arrUtc + "," + d.cantidadAceptada + "\n");
        }
        deliveries.flush();
    }

    @Override public void close() throws IOException {
        try (bookings; deliveries) {
            // try-with-resources cierra ambos
        }
    }
}