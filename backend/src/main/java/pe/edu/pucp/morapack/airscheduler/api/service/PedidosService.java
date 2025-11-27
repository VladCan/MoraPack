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

    /// Ahora que vamos a cargar varios archivos, tenemos que refactorizar la lógica

    /// Con esto, ya no almacena en /uploads o en /src/main/resources, sino en /uploads/archivosPedidos; análogo para local
    private static final String PEDIDOS_SUBDIR  = "archivosPedidos";

    /**
     * Construye el Path del archivo de pedidos para un código dado.
     *  * Ej: codigo = "SKBO" -> .../archivosPedidos/_pedidos_SKBO_.txt
     */
    public Path getPedidosFilePath(String codigo) {
        String cleanCode = codigo.trim().toUpperCase();

        // Directorio base: MORAPACK_UPLOAD_DIR
        Path baseDir = Paths.get(archivoManager.getUploadDir()); // local: src/main/resources, prod: /uploads

        //Agregamos la subcarpeta específica de pedidos
        Path pedidosDir = baseDir.resolve(PEDIDOS_SUBDIR); // .../archivosPedidos

        //Nombre del archivo
        String fileName = "_pedidos_" + cleanCode + "_.txt";

        return pedidosDir.resolve(fileName);
    }
    
    /**
     * Guarda el archivo de pedidos para un código (uno de los 27).
     */
    public Path guardarArchivoPedidos(InputStream fileInputStream, String codigo) throws IOException {
        Path targetPath = getPedidosFilePath(codigo);

        // Asegurar que el directorio exista (e.g., /uploads)
        Files.createDirectories(targetPath.getParent());

        // Copiar el stream al archivo
        Files.copy(fileInputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);

        //Para asegurarnos
        System.out.println("[PedidosService] Archivo de pedidos guardado en: " + targetPath);

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