package pe.edu.pucp.morapack.airscheduler.api.dto;

public record FlightLiveDTO(
        String id,                         // ej. "LIM->BOG#12"
        String origen, String destino,     // códigos icao/iata que uses
        double originLat, double originLon,
        double destLat,   double destLon,
        double progress,                   // 0..1
        String pathColor,                  // ej. "#60a5fa"
        String planeColor                  // ej. "#005097"
) {}
