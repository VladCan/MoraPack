package pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

public class ArchivoUtils {

    /**
     * Lee un archivo desde una ruta absoluta del sistema de archivos.
     *
     * @param rutaCompleta La ruta completa al archivo (ej: "/app/data/aereopuertos.txt")
     * @return un Scanner o null si falla
     */
    public static Scanner getScannerFromFilePath(String rutaCompleta) {
        try {
            Path path = Paths.get(rutaCompleta);

            if (!Files.exists(path) || !Files.isReadable(path)) {
                System.err.println("[ArchivoUtils] Archivo no encontrado o sin permisos en: " + rutaCompleta);
                return null;
            }

            // Crea el Scanner desde la ruta del archivo
            InputStream is = Files.newInputStream(path);
            return new Scanner(is);

        } catch (Exception e) {
            System.err.println("[ArchivoUtils] Error al leer archivo desde " + rutaCompleta + ": " + e.getMessage());
            e.printStackTrace(); // Útil para debug
            return null;
        }
    }
}