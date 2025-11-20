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

    private static final String FILENAME = "cancelaciones.txt";

    public Path getCancelacionesFilePath() {
        // Obtenemos la ruta base de uploads del manager, pero forzamos el nombre del archivo de pedidos.
        // Esto asume que ArchivoManager.uploadDir está inyectado correctamente.
        return Paths.get(archivoManager.getUploadDir(), FILENAME);
    }

    public Path guardarArchivoCancelaciones(InputStream fileInputStream) throws IOException {
        Path targetPath = getCancelacionesFilePath();

        // Asegurar que el directorio exista (e.g., /uploads)
        Files.createDirectories(targetPath.getParent());

        // Copiar el stream al archivo
        Files.copy(fileInputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);

        return targetPath;
    }
}
