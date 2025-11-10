package pe.edu.pucp.morapack.airscheduler.api.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.ArchivoManager;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.RunManager;

@ApplicationScoped
public class PedidosService {

    @Inject
    ArchivoManager archivoManager; // Usamos el manager para la ruta

    @Inject
    RunManager runManager; // Mantenemos RunManager aquí si lo requiere la lógica de negocio

    private static final String FILENAME = "pedidos.txt";

    /**
     * Obtiene el Path del archivo de pedidos subido.
     * @return El Path del archivo en el sistema.
     */
    public Path getPedidosFilePath() {
        // Obtenemos la ruta base de uploads del manager, pero forzamos el nombre del archivo de pedidos.
        // Esto asume que ArchivoManager.uploadDir está inyectado correctamente.
        return Paths.get(archivoManager.getUploadDir(), FILENAME);
    }
    
    /**
     * Guarda el archivo de pedidos subido.
     * @param fileInputStream El stream de datos del archivo.
     * @return El Path donde se guardó el archivo.
     * @throws IOException Si falla la escritura.
     */
    public Path guardarArchivoPedidos(InputStream fileInputStream) throws IOException {
        Path targetPath = getPedidosFilePath();

        // Asegurar que el directorio exista (e.g., /uploads)
        Files.createDirectories(targetPath.getParent());

        // Copiar el stream al archivo
        Files.copy(fileInputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        
        return targetPath;
    }

    // Nota: El método ensureOperacionStarted() y pushOrder() deberían ser llamados 
    // desde el método crearPedido del Controller, si esa lógica es inmediata.
    // Si la lógica de crearPedido es más compleja, podrías tener aquí un método:
    /*
    public String crearPedido(Pedido pedido) {
        String runId = runManager.ensureOperacionStarted();
        runManager.pushOrder(runId, pedido);
        return runId;
    }
    */
}