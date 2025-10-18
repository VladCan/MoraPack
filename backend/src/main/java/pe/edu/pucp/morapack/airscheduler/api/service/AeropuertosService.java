package pe.edu.pucp.morapack.airscheduler.api.service;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.*;
import java.util.stream.Collectors;

import pe.edu.pucp.morapack.airscheduler.engine.flights.model.Aeropuerto;
import pe.edu.pucp.morapack.airscheduler.infra.io.ArchivoUtils;
import pe.edu.pucp.morapack.airscheduler.infra.memory.AeropuertosMap;

@ApplicationScoped
public class AeropuertosService {

    private final AeropuertosMap aeropuertos = new AeropuertosMap();

    // === NUEVO: set de sedes ===
    private final Set<String> sedes = new HashSet<>(Arrays.asList("SPIM", "EBCI", "UBBB"));

    @PostConstruct
    void init() {
        try (Scanner sc = ArchivoUtils.getScannerFromResource("aereopuertos.txt")) {
            if (sc != null) aeropuertos.leerDatos(sc);
            else System.err.println("[AeropuertosService] No se encontró aereopuertos.txt");
        } catch (Exception e) {
            System.err.println("[AeropuertosService] Error cargando aeropuertos: " + e.getMessage());
        }
        System.out.println("[AeropuertosService] Aeropuertos cargados: " + aeropuertos.size());
    }

    public List<Aeropuerto> listarTodos() {
        return new ArrayList<>(aeropuertos.values());
    }

    public Optional<Aeropuerto> obtenerPorCodigo(String codigo) {
        if (codigo == null) return Optional.empty();
        return Optional.ofNullable(aeropuertos.obtener(codigo));
    }

    public boolean esSede(String codigo) {
        if (codigo == null) return false;
        return sedes.contains(codigo.toUpperCase(Locale.ROOT));
    }

    public List<Aeropuerto> filtrar(
            Set<String> codigos,
            String continente,
            Integer minCapacidad,
            Double minLon, Double minLat,
            Double maxLon, Double maxLat
    ) {
        return aeropuertos.values().stream()
                .filter(a -> codigos == null || codigos.isEmpty() || codigos.contains(a.getCodigo()))
                .filter(a -> continente == null || continente.isBlank() || continente.equalsIgnoreCase(a.getContinente()))
                .filter(a -> minCapacidad == null || a.getCapacidad() >= minCapacidad)
                .filter(a -> dentroDeBBox(a, minLon, minLat, maxLon, maxLat))
                .collect(Collectors.toList());
    }

    private boolean dentroDeBBox(Aeropuerto a, Double minLon, Double minLat, Double maxLon, Double maxLat) {
        if (minLon == null || minLat == null || maxLon == null || maxLat == null) return true;
        Double lat = safeParse(a.getLatitud());
        Double lon = safeParse(a.getLongitud());
        if (lat == null || lon == null) return false;
        return lon >= minLon && lon <= maxLon && lat >= minLat && lat <= maxLat;
    }

    private static Double safeParse(String s) {
        if (s == null) return null;
        try { return Double.valueOf(s.trim().replace(',', '.')); }
        catch (NumberFormatException e) { return null; }
    }
}
