package pe.edu.pucp.morapack.airscheduler.api.mapper;

import pe.edu.pucp.morapack.airscheduler.api.dto.AeropuertoDTO;
import pe.edu.pucp.morapack.airscheduler.engine.flights.model.Aeropuerto;

public final class AeropuertoMapper {
    private AeropuertoMapper() {}

    public static AeropuertoDTO toDTO(Aeropuerto a, boolean isSede) {
        if (a == null) return null;
        return new AeropuertoDTO(
                a.getCodigo(),
                a.getCiudad(),
                a.getPais(),
                a.getGMT(),
                a.getCapacidad(),
                parseCoord(a.getLatitud()),
                parseCoord(a.getLongitud()),
                a.getContinente(),
                isSede
        );
    }

    // compatibilidad si en algún sitio no necesitas la marca
    public static AeropuertoDTO toDTO(Aeropuerto a) {
        return toDTO(a, false);
    }

    private static Double parseCoord(String s) {
        if (s == null) return null;
        String t = s.trim().replace(',', '.');
        try { return Double.valueOf(t); }
        catch (NumberFormatException ex) { return null; }
    }
}
