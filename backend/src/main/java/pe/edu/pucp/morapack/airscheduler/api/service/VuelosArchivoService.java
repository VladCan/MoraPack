package pe.edu.pucp.morapack.airscheduler.api.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Scanner;
import java.io.File; // Importación necesaria para getVuelosFileForDownload().toFile()

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.ArchivoManager;

@ApplicationScoped
public class VuelosArchivoService {

    @Inject
    ArchivoManager archivoManager; // Para obtener la ruta base y el Scanner

    private static final String FILENAME = "planesDeVuelo.txt";

    /**
     * Obtiene el Path del archivo de planes de vuelo.
     */
    public Path getVuelosFilePath() {
        // Usa el getter del Manager para obtener el directorio base de uploads.
        return Paths.get(archivoManager.getUploadDir(), FILENAME);
    }
    
    /**
     * Guarda el archivo de planes de vuelo subido.
     */
    public Path guardarArchivoPlanesDeVuelo(InputStream fileInputStream) throws IOException {
        Path targetPath = getVuelosFilePath();

        // 1. Asegurar que el directorio exista (e.g., /uploads)
        Files.createDirectories(targetPath.getParent());

        // 2. Copiar el stream al archivo
        Files.copy(fileInputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        
        return targetPath;
    }

    /**
     * Obtiene el objeto File para descarga.
     */
    public File getVuelosFileForDownload() {
        Path p = getVuelosFilePath();
        if (Files.exists(p)) {
            return p.toFile();
        }
        return null;
    }
    
    /**
     * Obtiene un Scanner para la carga inicial de datos (usado por VuelosLiveService).
     * Delega la lógica de resource vs. filesystem al ArchivoManager.
     */
    public Optional<Scanner> getScannerForInitialLoad() {
        // Se asume que ArchivoManager ya tiene getScannerForDataFile(String filename)
        return archivoManager.getScannerForDataFile(FILENAME); 
    }
}