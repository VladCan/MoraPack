package pe.edu.pucp.morapack.airscheduler.api.service;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject; // <-- Nuevo import para inyección

import java.io.IOException; // <-- Nuevo import para manejo de excepciones de I/O
import java.io.InputStream; // <-- Nuevo import para recibir el stream
import java.nio.file.Files; // <-- Nuevo import para operaciones de archivo
import java.nio.file.Path; // <-- Nuevo import para rutas
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption; // <-- Nuevo import para copiar archivos
import java.util.*;
import java.util.stream.Collectors;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.ArchivoManager; // <-- Importar el nuevo manager
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Aeropuerto;

@ApplicationScoped
public class AeropuertosService {

    private final AeropuertosMap aeropuertos = new AeropuertosMap();

    @Inject
    ArchivoManager archivoManager;

    private static final String FILENAME = "aereopuertos.txt"; // <-- 1. Definir el nombre del archivo

    // === NUEVO: set de sedes ===
    private final Set<String> sedes = new HashSet<>(Arrays.asList("SPIM", "EBCI", "UBBB"));

    @PostConstruct
    void init() {

        // CORRECCIÓN: Llamar a la versión genérica con el nombre del archivo
        Optional<Scanner> scOpt = archivoManager.getScannerForDataFile(FILENAME); 
        
        if (scOpt.isPresent()) {
            try (Scanner sc = scOpt.get()) {
                aeropuertos.leerDatos(sc);
            } catch (Exception e) {
                System.err.println("[AeropuertosService] Error procesando datos de aeropuertos: " + e.getMessage());
            }
        } else {
             System.err.println("[AeropuertosService] No se pudo obtener el Scanner para cargar datos iniciales.");
        }
        System.out.println("[AeropuertosService] Aeropuertos cargados: " + aeropuertos.size());
    }
    
    /**
     * Lógica de negocio para guardar el archivo de datos subido.
     * Delega la ruta al ArchivoManager y realiza la copia.
     * @param fileInputStream El stream de entrada del archivo.
     * @return El Path donde se guardó el archivo.
     * @throws IOException Si ocurre un error de I/O (permisos, disco, etc.).
     */
    public Path guardarArchivoDatos(InputStream fileInputStream) throws IOException {
        // 1. Obtener la ruta de destino usando el Manager
        Path targetPath = Paths.get(archivoManager.getUploadDir(), FILENAME);
        // 2. Asegurar que el directorio de destino exista
        Files.createDirectories(targetPath.getParent()); 
        
        // 3. Copiar el stream al archivo, reemplazando el existente.
        Files.copy(fileInputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        
        // (Opcional) Aquí podrías agregar lógica para recargar los datos en aeropuertos si es necesario
        // Ejemplo: init(); 
        
        return targetPath;
    }

    // ... (listarTodos, obtenerPorCodigo, esSede se mantienen sin cambios) ...

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