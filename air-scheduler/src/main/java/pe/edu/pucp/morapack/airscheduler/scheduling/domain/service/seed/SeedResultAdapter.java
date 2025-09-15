package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed;

import java.util.ArrayList;
import java.util.List;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.BookingRecord;

/**
 * Convierte la seed a bookings aplicables en LiveTEGState.
 * Versión mínima: devuelve lista vacía para compilar y correr la estructura.
 * (Luego la completamos con la lectura de PathArc / OrderAssignment.)
 */
public final class SeedResultAdapter {

    public static List<BookingRecord> toBookings(
            Object seed, // usa el tipo real de tu seed si lo prefieres
            Object live, // no lo usamos aquí aún
            int batchNo
    ) {
        return new ArrayList<>();
    }
}
