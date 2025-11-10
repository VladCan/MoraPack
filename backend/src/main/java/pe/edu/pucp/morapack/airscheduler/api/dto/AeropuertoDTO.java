package pe.edu.pucp.morapack.airscheduler.api.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
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
