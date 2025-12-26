package pe.edu.pucp.morapack.airscheduler.api.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.ArchivoManager;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.PedidoDTO;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.RunManager;

@ApplicationScoped
public class PedidosService {

    @Inject
    ArchivoManager archivoManager; // Usamos el manager para la ruta

    @Inject
    RunManager runManager; // Mantenemos RunManager aquí si lo requiere la lógica de negocio

    private final Object lock = new Object();

    /// Ahora que vamos a cargar varios archivos, tenemos que refactorizar la lógica

    /// Con esto, ya no almacena en /uploads o en /src/main/resources, sino en /uploads/archivosPedidos; análogo para local
    private static final String PEDIDOS_SUBDIR  = "archivosPedidos";

    /// on esto, ya no almacena en /uploads o en /src/main/resources, sino en /uploads/BDPedidos; análogo para local
    private static final String BDPEDIDOS_SUBDIR = "BDPedidos";

    private static final String FILENAME = "pedidos.txt";

    // Regex robusta para el formato:
    // PEDIDO #1 | UTC: 2025-... | Dest: SCEL | Cant: 990 | Cliente: 7729
    private static final Pattern BD_LINE_PATTERN = Pattern.compile(
            "^\\s*PEDIDO\\s*#(?<id>\\d+)\\s*\\|\\s*UTC:\\s*(?<utc>[^|]+)\\s*\\|\\s*Dest:\\s*(?<dest>[A-Za-z]{3,4})\\s*\\|\\s*Cant:\\s*(?<cant>\\d+)\\s*\\|\\s*Cliente:\\s*(?<cli>\\d+)\\s*$"
    );

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

    public Path getBDPedidosFilePath() {
        // Directorio base: MORAPACK_UPLOAD_DIR
        Path baseDir = Paths.get(archivoManager.getUploadDir()); // local: src/main/resources, prod: /uploads

        //Agregamos la subcarpeta específica de BDPedidos
        Path pedidosDir = baseDir.resolve(BDPEDIDOS_SUBDIR); // .../BDPedidos

        //String fileName = FILENAME;
        //return pedidosDir.resolve(fileName);

        return pedidosDir.resolve(FILENAME);
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

    /**
     * Escribe 1 solo pedido en el archivo
     */
    public void appendPedido(Pedido p){
        Path targetPath = getBDPedidosFilePath();

        if (p == null) return;

        String line = formatLine(p);
        synchronized (lock){
            try {
                archivoManager.appendText(targetPath, line);
            }
            catch (IOException e){
                System.err.println("[OperacionDiariaPedidosService] Error append: " + e.getMessage());
            }
        }
    }

    /**
     * Escribe varios pedidos en el archivo
     */
    public void appendPedidos(List<Pedido> pedidos) {
        Path targetPath = getBDPedidosFilePath();

        if (pedidos == null || pedidos.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        for (Pedido p : pedidos) sb.append(formatLine(p));

        synchronized (lock) {
            try {
                archivoManager.appendText(targetPath, sb.toString());
            } catch (IOException e) {
                System.err.println("[OperacionDiariaPedidosService] Error append batch: " + e.getMessage());
            }
        }
    }

    private String formatLine(Pedido p) {
        // AJUSTA getters según tu Pedido real
        int id = p.getIdPedido();
        String dest = p.getDestino();      // "SCEL"
        int cant = p.getCantidad();
        int cliente = p.getIdCliente();

        // fecha en UTC idealmente ya en Instant:
        // si es LocalDateTime, conviene convertirlo antes a Instant UTC
        String utc = String.valueOf(p.getFecha()); // reemplazar por ISO_INSTANT si tienes Instant

        return "PEDIDO #" + id +
                " | UTC: " + utc +
                " | Dest: " + dest +
                " | Cant: " + cant +
                " | Cliente: " + cliente +
                System.lineSeparator();
    }

    public List<PedidoDTO> leerPedidosBD() throws IOException {
        Path file = getBDPedidosFilePath();

        if (!Files.exists(file)) {
            return Collections.emptyList();
        }

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<PedidoDTO> out = new ArrayList<>();

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            Matcher m = BD_LINE_PATTERN.matcher(line);
            if (!m.matches()) {
                // Si quieres, loguea para detectar líneas rotas
                System.err.println("[PedidosService] Línea inválida en pedidos.txt: " + line);
                continue;
            }

            int id = Integer.parseInt(m.group("id"));
            String utc = m.group("utc").trim();
            String dest = m.group("dest").trim();
            int cant = Integer.parseInt(m.group("cant"));
            int cli = Integer.parseInt(m.group("cli"));

            PedidoDTO dto = new PedidoDTO();
            dto.id = id;
            dto.idCliente = cli;
            dto.destino = dest;
            dto.cantidad = cant;

            dto.numPaquetes = null;
            dto.origen = null;                 // para tu union (string | string[] | null)
            dto.cantidadAsignada = 0;
            dto.estadoAsignacion = "PENDIENTE"; // el front lo valida con zod enum
            dto.fechaCreacion = utc;            // string ISO (como viene en archivo)
            dto.fechaLocal = null;
            dto.continenteDestino = null;

            dto.rutas = List.of();
            dto.recojos = List.of();

            out.add(dto);
        }

        // opcional: ordenarlos por id
        out.sort(Comparator.comparingInt(p -> p.id));
        return out;
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