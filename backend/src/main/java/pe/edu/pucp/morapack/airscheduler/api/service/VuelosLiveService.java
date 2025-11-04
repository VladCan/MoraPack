package pe.edu.pucp.morapack.airscheduler.api.service;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import pe.edu.pucp.morapack.airscheduler.api.dto.FlightLiveDTO;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Vuelo;
import io.smallrye.mutiny.Multi;

// Importaciones necesarias
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class VuelosLiveService {

    @Inject 
    AeropuertosService aeropuertosService;
    
    @Inject 
    Clock clock; 
    
    @Inject
    VuelosArchivoService vuelosArchivoService; // <-- Nuevo: Inyectamos el servicio que maneja el archivo

    private final List<Vuelo> vuelos = new ArrayList<>();
    private static final String DEFAULT_PLANE = "#005097";
    private static final String[] PALETTE = {
            "#f472b6", "#60a5fa", "#34d399", "#f59e0b", "#a78bfa", "#f97316"
    };

    @PostConstruct
    void init() {
        // **USO DEL VUELOSARCHIVOSERVICE:** Obtenemos el Scanner del servicio
        Optional<Scanner> scOpt = vuelosArchivoService.getScannerForInitialLoad(); 
        
        if (scOpt.isEmpty()) {
            System.err.println("[VuelosLiveService] No se encontró planesDeVuelo.txt. Carga inicial omitida.");
            return;
        }
        
        try (Scanner sc = scOpt.get()) { // Usamos el Scanner si está presente
            for (int i = 1; sc.hasNextLine(); i++) {
                Vuelo v = new Vuelo();
                String key = v.leer(sc, i);
                if (key == null)
                    continue;

                var ao = aeropuertosService.obtenerPorCodigo(v.getOrigen()).orElse(null);
                var ad = aeropuertosService.obtenerPorCodigo(v.getDestino()).orElse(null);
                if (ao == null || ad == null)
                    continue;

                v.llenarHoraGMT(ao.getGMT(), ad.getGMT());
                vuelos.add(v);
            }
            System.out.println("[VuelosLiveService] Vuelos cargados: " + vuelos.size());
        } catch (Exception e) {
            System.err.println("[VuelosLiveService] Error cargando vuelos: " + e.getMessage());
        }
    }

    /** Proveedor de hora UTC (segundos del día) por conexión. */
    @FunctionalInterface
    public interface NowSupplier {
        int nowUtcSeconds();
    }

    /**
     * Stream SSE: emite cada 1s la lista de vuelos en aire según el "now" que se le
     * pase.
     */
    public Multi<List<FlightLiveDTO>> streamLiveFlights(NowSupplier nowSupplier) {
        return Multi.createFrom().ticks().every(Duration.ofSeconds(1))
                .onItem().transform(t -> snapshot(nowSupplier.nowUtcSeconds()));
    }

    private List<FlightLiveDTO> snapshot(int nowUtcSec) {
        return vuelos.stream()
                .map(v -> toLive(nowUtcSec, v))
                .filter(Objects::nonNull) // 👈 solo “en el aire”
                .collect(Collectors.toList());
    }

    /**
     * Devuelve DTO si el vuelo está en el aire; si no, null. Soporta cruce de
     * medianoche.
     */
    private FlightLiveDTO toLive(int nowUtcSec, Vuelo v) {
        if (v.getHoraGMTOrigen() == null || v.getHoraGMTDestino() == null)
            return null;

        int start = v.getHoraGMTOrigen().toSecondOfDay();
        int end = v.getHoraGMTDestino().toSecondOfDay();

        int endAdj = end <= start ? end + 24 * 3600 : end;
        int nowAdj = nowUtcSec < start ? nowUtcSec + 24 * 3600 : nowUtcSec;

        if (nowAdj < start || nowAdj >= endAdj)
            return null; // 🚫 no está volando

        double progress = (double) (nowAdj - start) / (double) (endAdj - start);

        var ao = aeropuertosService.obtenerPorCodigo(v.getOrigen()).orElse(null);
        var ad = aeropuertosService.obtenerPorCodigo(v.getDestino()).orElse(null);
        if (ao == null || ad == null)
            return null;

        Double oLat = parse(ao.getLatitud()), oLon = parse(ao.getLongitud());
        Double dLat = parse(ad.getLatitud()), dLon = parse(ad.getLongitud());
        if (oLat == null || oLon == null || dLat == null || dLon == null)
            return null;

        String id = v.getOrigen() + "->" + v.getDestino() + "#" + v.getId();
        return new FlightLiveDTO(
                id, v.getOrigen(), v.getDestino(),
                oLat, oLon, dLat, dLon,
                progress,
                pickColor(id),
                DEFAULT_PLANE);
    }

    private static Double parse(String s) {
        if (s == null)
            return null;
        try {
            return Double.valueOf(s.trim().replace(',', '.'));
        } catch (Exception e) {
            return null;
        }
    }

    private static String pickColor(String id) {
        int h = Math.abs(id.hashCode());
        return PALETTE[h % PALETTE.length];
    }

    /** Hora actual del sistema en UTC (segundos del día). */
    public int systemNowUtcSeconds() {
        return LocalTime.now(clock).toSecondOfDay(); // clock ya es UTC
    }

    public Multi<List<FlightLiveDTO>> streamLiveFlights(NowSupplier nowSupplier, int limit) {
        return Multi.createFrom().ticks().every(Duration.ofSeconds(1))
                .onItem().transform(t -> snapshot(nowSupplier.nowUtcSeconds(), limit));
    }

    private List<FlightLiveDTO> snapshot(int nowUtcSec, int limit) {
        var stream = vuelos.stream()
                .map(v -> toLive(nowUtcSec, v))
                .filter(Objects::nonNull)
                // orden estable para que no “parpadeen” los N primeros
                .sorted(Comparator.comparing(FlightLiveDTO::id));

        if (limit != Integer.MAX_VALUE)
            stream = stream.limit(limit);

        return stream.collect(Collectors.toList());
    }
}