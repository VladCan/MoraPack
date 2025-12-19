package pe.edu.pucp.morapack.airscheduler.api.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.ArchivoManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@ApplicationScoped
public class CancelacionesService {

    @Inject
    ArchivoManager archivoManager;

    private boolean nuevoArchivoSubido;

    private static final String FILENAME = "cancelaciones.txt";

    public Path getCancelacionesFilePath() {
        return Paths.get(archivoManager.getUploadDir(), FILENAME);
    }

    public synchronized Path guardarArchivoCancelaciones(InputStream fileInputStream) throws IOException {
        Path targetPath = getCancelacionesFilePath();

        Files.createDirectories(targetPath.getParent());
        Files.copy(fileInputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);

        // marcar evento
        nuevoArchivoSubido = true;

        return targetPath;
    }

    /**
     * Devuelve true SOLO UNA VEZ por cada archivo subido.
     * Luego resetea el estado.
     */
    public synchronized boolean isNuevoArchivoSubido() {
        if (nuevoArchivoSubido) {
            nuevoArchivoSubido = false;
            return true;
        }
        return false;
    }
}

