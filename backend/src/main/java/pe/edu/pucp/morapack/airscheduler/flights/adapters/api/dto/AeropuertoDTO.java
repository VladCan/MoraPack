package pe.edu.pucp.morapack.airscheduler.flights.adapters.api.dto;

public record AeropuertoDTO(
        String codigo,
        String ciudad,
        String pais,
        Integer gmt,
        Integer capacidad,
        Double lat,
        Double lon,
        String continente,
        boolean sede       // <--- NUEVO
) {}
