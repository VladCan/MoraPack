package pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption; // Importante para sobrescribir
import java.util.Optional;
import java.util.Scanner;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class ArchivoManager {

    @ConfigProperty(name = "morapack.data.source.mode")
    String dataSourceMode;

    @ConfigProperty(name = "morapack.upload.dir")
    String uploadDir;

    @ConfigProperty(name = "morapack.reports.dir")
    String reportsDir;
    
    // Eliminamos 'private static final String FILENAME = "aereopuertos.txt";' si existía

    /**
     * Hace que la ruta base de uploads sea accesible para otras clases (PedidosService, VuelosArchivoService).
     */
    public String getUploadDir() {
        return uploadDir; 
    }

    public String getReportsDir(){
        return reportsDir;
    }
    // ELIMINAR el método getUploadFilePath() si solo se usaba para "aereopuertos.txt"
    // Ya que cada Service ahora lo construye con getUploadDir() + su propio filename.

    /**
     * Obtiene un Scanner para leer cualquier archivo de datos iniciales.
     * @param filename El nombre del archivo a buscar (e.g., "vuelos.txt").
     */
    public Optional<Scanner> getScannerForDataFile(String filename) { // <-- ¡Ahora acepta String!
        Optional<InputStream> isOpt = getInputStreamForDataFile(filename);
        if (isOpt.isPresent()) {
            return Optional.of(new Scanner(isOpt.get()));
        }
        return Optional.empty();
    }
    
    /**
     * Lógica central para obtener el InputStream, eligiendo entre resource o filesystem.
     */
    private Optional<InputStream> getInputStreamForDataFile(String filename) { // <-- Lógica ajustada
        // --- 1. Modo 'filesystem' (Producción: lee del volumen) ---
        if ("filesystem".equalsIgnoreCase(dataSourceMode)) {
            // Construye la ruta completa: /uploads/nombreArchivo.txt
            Path p = Paths.get(uploadDir, filename); 
            try {
                if (Files.exists(p)) {
                    System.out.println("[ArchivoManager] Leyendo datos iniciales desde Filesystem: " + p);
                    return Optional.of(Files.newInputStream(p));
                } else {
                    System.err.println("[ArchivoManager] Archivo no encontrado en Filesystem: " + p + ".");
                    return Optional.empty();
                }
            } catch (IOException e) {
                System.err.println("[ArchivoManager] Error leyendo de Filesystem: " + e.getMessage());
                return Optional.empty();
            }
        } 
        
        // --- 2. Modo 'resource' (Desarrollo/Default) ---
        else {
            InputStream is = ArchivoManager.class.getClassLoader().getResourceAsStream(filename); 
            if (is == null) {
                System.err.println("[ArchivoManager] Archivo no encontrado en resources: " + filename + ".");
                return Optional.empty();
            }
            System.out.println("[ArchivoManager] Leyendo datos iniciales desde Resources: " + filename);
            return Optional.of(is);
        }
    }
    // ... (Tus métodos de lectura getScanner y getInputStream se quedan igual) ...
    /* 
    public Optional<Scanner> getScannerForDataFile(String filename) {
        Optional<InputStream> isOpt = getInputStreamForDataFile(filename);
        return isOpt.map(Scanner::new);
    }*/

    public Optional<BufferedReader> getBufferedReaderForDataFile(String filename) {
        Optional<InputStream> isOpt = getInputStreamForDataFile(filename);
        return isOpt.map(inputStream -> new BufferedReader(new InputStreamReader(inputStream)));
    }
    /* 
    private Optional<InputStream> getInputStreamForDataFile(String filename) {
        // ... (Tu lógica existente se mantiene igual aquí) ...
        if ("filesystem".equalsIgnoreCase(dataSourceMode)) {
            Path p = Paths.get(uploadDir, filename);
            try {
                if (Files.exists(p)) {
                    System.out.println("[ArchivoManager] Leyendo desde Filesystem: " + p);
                    return Optional.of(Files.newInputStream(p));
                } else {
                    System.err.println("[ArchivoManager] No existe en Filesystem: " + p);
                    return Optional.empty();
                }
            } catch (IOException e) {
                return Optional.empty();
            }
        } else {
            InputStream is = ArchivoManager.class.getClassLoader().getResourceAsStream(filename);
            if (is == null) return Optional.empty();
            System.out.println("[ArchivoManager] Leyendo desde Resources: " + filename);
            return Optional.of(is);
        }
    }*/

    // -----------------------------------------------------------------------
    // NUEVO MÉTODO: Para guardar el archivo de 178MB sin explotar la memoria
    // -----------------------------------------------------------------------
    public void guardarArchivoSubido(InputStream fileInputStream, String filename) throws IOException {
        // 1. Asegurar que el directorio de uploads exista
        Path uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        // 2. Definir la ruta final
        Path destinationPath = uploadPath.resolve(filename);

        // 3. Copiar usando STREAMING (Files.copy). 
        // Esto lee el stream byte a byte y lo escribe en disco sin cargarlo todo en RAM.
        // REPLACE_EXISTING permite sobrescribir si subes una corrección del archivo.
        long bytesCopied = Files.copy(fileInputStream, destinationPath, StandardCopyOption.REPLACE_EXISTING);
        
        System.out.println("[ArchivoManager] Archivo guardado exitosamente: " + destinationPath);
        System.out.println("[ArchivoManager] Tamaño: " + (bytesCopied / 1024 / 1024) + " MB");
    }
}