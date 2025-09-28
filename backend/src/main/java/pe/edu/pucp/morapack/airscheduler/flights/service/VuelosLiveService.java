package pe.edu.pucp.morapack.airscheduler.flights.service;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Multi;

import java.io.File;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.api.dto.FlightLiveDTO;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.Aeropuerto;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.Vuelo;

@ApplicationScoped
public class VuelosLiveService {

    @Inject AeropuertosService aeropuertosService;

    private final List<Vuelo> vuelos = new ArrayList<>();
    private static final String DEFAULT_PLANE = "#005097";
    private static final String[] PALETTE = {
            "#f472b6", "#60a5fa", "#34d399", "#f59e0b", "#a78bfa", "#f97316"
    };

    @PostConstruct
    void init() {
        // Carga vuelos desde resources/planesDeVuelo.txt (formato ORIGEN-DESTINO-HH:mm-HH:mm-capacidad)
        File f = new File("src/main/resources/planesDeVuelo.txt");
        if (!f.exists()) {
            System.err.println("[VuelosLiveService] No se encontró planesDeVuelo.txt");
            return;
        }
        try (Scanner sc = new Scanner(f)) {
            for (int i = 1; sc.hasNextLine(); i++) {
                Vuelo v = new Vuelo();
                String key = v.leer(sc, i); // key = origen (puede ser null si línea inválida)
                if (key == null) continue;

                // completar horas GMT con offsets de aeropuertos
                Aeropuerto ao = aeropuertosService.obtenerPorCodigo(v.getOrigen()).orElse(null);
                Aeropuerto ad = aeropuertosService.obtenerPorCodigo(v.getDestino()).orElse(null);
                if (ao == null || ad == null) continue;

                v.llenarHoraGMT(ao.getGMT(), ad.getGMT());
                vuelos.add(v);
            }
            System.out.println("[VuelosLiveService] Vuelos cargados: " + vuelos.size());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Stream SSE: cada tick emite la LISTA de vuelos en aire (con progress y coords). */
    public Multi<List<FlightLiveDTO>> streamLiveFlights() {
        return Multi.createFrom().ticks().every(Duration.ofSeconds(1))
                .onItem().transform(tick -> snapshot());
    }

    private List<FlightLiveDTO> snapshot() {
        LocalTime nowUtc = LocalTime.now(ZoneOffset.UTC);
        int now = nowUtc.toSecondOfDay();

        return vuelos.stream()
                .map(v -> toLive(now, v))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /** Devuelve DTO si el vuelo está "en el aire" según la hora UTC actual; si no, null. */
    private FlightLiveDTO toLive(int nowUtcSec, Vuelo v) {
        if (v.getHoraGMTOrigen() == null || v.getHoraGMTDestino() == null) return null;

        int start = v.getHoraGMTOrigen().toSecondOfDay();
        int end   = v.getHoraGMTDestino().toSecondOfDay();

        // Duración y normalización para cruces de medianoche
        int endAdj   = end;
        if (endAdj <= start) endAdj += 24 * 3600;

        int nowAdj = nowUtcSec;
        if (nowAdj < start) nowAdj += 24 * 3600;

        if (nowAdj < start || nowAdj >= endAdj) return null; // no está volando

        double progress = (double)(nowAdj - start) / (double)(endAdj - start);

        // Coordenadas de origen/destino
        Aeropuerto ao = aeropuertosService.obtenerPorCodigo(v.getOrigen()).orElse(null);
        Aeropuerto ad = aeropuertosService.obtenerPorCodigo(v.getDestino()).orElse(null);
        if (ao == null || ad == null) return null;

        Double oLat = parse(ao.getLatitud());
        Double oLon = parse(ao.getLongitud());
        Double dLat = parse(ad.getLatitud());
        Double dLon = parse(ad.getLongitud());
        if (oLat == null || oLon == null || dLat == null || dLon == null) return null;

        String id = v.getOrigen() + "->" + v.getDestino() + "#" + v.getId();
        String pathColor  = pickColor(id);
        String planeColor = DEFAULT_PLANE;

        return new FlightLiveDTO(
                id, v.getOrigen(), v.getDestino(),
                oLat, oLon, dLat, dLon,
                progress, pathColor, planeColor
        );
    }

    private static Double parse(String s) {
        if (s == null) return null;
        try { return Double.valueOf(s.trim().replace(',', '.')); }
        catch (Exception e) { return null; }
    }

    private static String pickColor(String id) {
        int h = Math.abs(id.hashCode());
        return PALETTE[h % PALETTE.length];
    }
}
