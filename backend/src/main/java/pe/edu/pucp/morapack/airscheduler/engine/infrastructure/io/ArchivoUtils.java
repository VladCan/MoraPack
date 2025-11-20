package pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io;

import org.eclipse.microprofile.config.ConfigProvider; // 1. IMPORTAR ESTO
import java.io.FileWriter;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

// NO ES UN BEAN. Es una clase de utilidad simple.
public class ArchivoUtils {

    /**
     * Obtiene la ruta de upload configurada en application.properties.
     * ESTE ES EL TRUCO: Lee la config directamente, sin inyección.
     * @return La ruta de upload (ej: "/uploads")
     */
    private static String getUploadDir() {
        // Lee "morapack.upload.dir" de application.properties
        return ConfigProvider.getConfig().getValue("morapack.upload.dir", String.class);
    }

    /**
     * Lee un archivo desde el directorio de uploads (definido en morapack.upload.dir).
     *
     * @param nombreArchivo El nombre del archivo (ej: "aereopuertos.txt")
     * @return un Scanner o null si falla
     */
    public static Scanner getScannerFromFilePath(String nombreArchivo) {
        try {
            // Construye la ruta completa usando el uploadDir
            Path rutaCompleta = Paths.get(getUploadDir(), nombreArchivo);

            if (!Files.exists(rutaCompleta) || !Files.isReadable(rutaCompleta)) {
                System.err.println("[ArchivoUtils] Archivo no encontrado o sin permisos en: " + rutaCompleta);
                return null;
            }

            InputStream is = Files.newInputStream(rutaCompleta);
            return new Scanner(is);

        } catch (Exception e) {
            System.err.println("[ArchivoUtils] Error al leer archivo desde " + getUploadDir() + "/" + nombreArchivo + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Crea un FileWriter para escribir en un archivo en el directorio de uploads.
     *
     * @param nombreArchivo El nombre del archivo (ej: "pedidos_salida.txt")
     * @return un FileWriter o null si falla
     */
    public static FileWriter getWriterForFilePath(String nombreArchivo) {
        try {
            // Construye la ruta completa
            Path rutaCompleta = Paths.get(getUploadDir(), nombreArchivo);

            // Asegura que el directorio exista
            Files.createDirectories(rutaCompleta.getParent());

            return new FileWriter(rutaCompleta.toFile(), StandardCharsets.UTF_8);

        } catch (IOException e) {
            System.err.println("[ArchivoUtils] Error al crear writer para " + getUploadDir() + "/" + nombreArchivo + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }


    //Esto usamos en OperacionDiariaController
    public static Scanner getScanner(InputStream is) {
        if (is == null) return null;
        return new Scanner(is);
    }

}

