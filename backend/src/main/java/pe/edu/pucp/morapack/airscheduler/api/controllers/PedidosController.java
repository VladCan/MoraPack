package pe.edu.pucp.morapack.airscheduler.api.controllers;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files; // Necesario para abrir el stream del temp
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// --- IMPORTS NUEVOS PARA SUBIDA EFICIENTE ---
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
// --------------------------------------------

import io.smallrye.common.annotation.Blocking;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import pe.edu.pucp.morapack.airscheduler.api.response.JsonResponse;
import pe.edu.pucp.morapack.airscheduler.api.response.PedidoResponse;
import pe.edu.pucp.morapack.airscheduler.api.service.PedidosService;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.RunManager;
import pe.edu.pucp.morapack.airscheduler.api.mapper.PedidoMapper;

@Path("/pedidos")
public class PedidosController {

    @Inject 
    PedidosService pedidosService;

    @Inject
    RunManager runManager;

    private static final String FILENAME = "pedidos.txt";

    private static final String[] CODIGOS = {
            "EDDI", "EHAM", "EKCH", "LATI", "LBSF", "LDZA", "LKPR","LOWW", "OAKB", "OERK", "OJAI", "OMDB", "OOMS",
            "OPKC", "OSDI", "OYSN", "SABE", "SBBR", "SCEL", "SEQM", "SGAS", "SKBO", "SLLP", "SUAA", "SVMI", "UMMS", "VIDP"
    };

    // Conjunto para validación rápida
    private static final Set<String> CODIGOS_VALIDOS = new HashSet<>(Arrays.asList(CODIGOS));

    // Patrón para nombres del tipo _pedidos_SKBO_.txt
    private static final Pattern PEDIDOS_FILENAME_PATTERN =
            Pattern.compile("^_pedidos_([A-Z0-9]{4})_\\.txt$");
    

    public static final class PedidoRequest{
        public Integer idCliente;
        public String destino;
        public String fecha;
        public Integer cantidad;
    }

    // ---------------------------------------------------------
    // MÉTODO CORREGIDO PARA ARCHIVOS GRANDES (178MB+)
    // ---------------------------------------------------------
    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Blocking
    public Response uploadPedidos(@RestForm("file") FileUpload fileUpload) { // <--- CAMBIO AQUÍ
        
        // Validación rápida por si el archivo llega vacío
        if (fileUpload == null || fileUpload.fileName() == null) {
             return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new JsonResponse("error", "No se envió ningún archivo", null))
                    .build();
        }

        /// Esta parte es para validar el formato adecuado de los nombres del archivo

        // Nombre que viene del front (puede ser solo el nombre, sin ruta)
        String originalName = fileUpload.fileName().trim();

        // 1) Validar formato del nombre
        Matcher matcher = PEDIDOS_FILENAME_PATTERN.matcher(originalName);
        if (!matcher.matches()) {
            String msg = "Nombre de archivo inválido. Se esperaba formato _pedidos_XXXX_.txt y se recibió: "
                    + originalName;
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new JsonResponse("error", msg, null))
                    .build();
        }

        // 2) Extraer el código XXXX
        String codigo = matcher.group(1).toUpperCase(Locale.ROOT);

        // 3) Validar que el código esté en la lista de permitidos
        if (!CODIGOS_VALIDOS.contains(codigo)) {
            String msg = "Código de aeropuerto inválido en el nombre del archivo: " + codigo;
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new JsonResponse("error", msg, null))
                    .build();
        }

        /// Si estamos aquí, el código y nombre del archivo fueron válidos. Procedemos a guardarlo

        try {
            // 1. Obtenemos el path del archivo temporal que Quarkus ya guardó en disco
            java.nio.file.Path tempPath = fileUpload.uploadedFile();

            // 2. Abrimos un stream desde ese archivo temporal
            // Usamos try-with-resources para asegurar que se cierre el stream
            try (InputStream fileInputStream = Files.newInputStream(tempPath)) {
                
                // 3. Delegamos a tu servicio (que ya usa la lógica de ArchivoManager)
                // Tu servicio leerá este stream y lo copiará a la carpeta final
                java.nio.file.Path targetPath = pedidosService.guardarArchivoPedidos(fileInputStream, codigo);

                return Response
                    .ok(new JsonResponse("success", "¡Archivo de pedidos guardado exitosamente para " +
                            "código " + codigo + "!", targetPath.toAbsolutePath().toString()))
                    .build();
            }

        } catch (IOException e) {
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new JsonResponse("error", "Error al procesar el archivo: " + e.getMessage(), null))
                    .build();
        }
    }
    // ---------------------------------------------------------

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStatus(@QueryParam("codigo") String codigo){

        int totalCodigos = CODIGOS.length;
        int archivosPresentes = 0;
        long totalSizeBytes = 0L;

        java.util.List<Map<String, Object>> archivos = new java.util.ArrayList<>();

        for (String cod : CODIGOS) {
            Map<String, Object> info = new HashMap<>();
            info.put("codigo", cod);

            try {
                // Ruta esperada, e.g. .../archivosPedidos/_pedidos_SKBO_.txt
                java.nio.file.Path p = pedidosService.getPedidosFilePath(cod);
                boolean exists = Files.exists(p);
                info.put("exists", exists);

                // Aunque no exista, esto devuelve el nombre esperado
                info.put("filename", p.getFileName().toString());

                if (exists) {
                    long size = Files.size(p);
                    info.put("sizeBytes", size);
                    info.put("lastModified", lastModifiedIso(p));

                    archivosPresentes++;
                    totalSizeBytes += size;
                }

            } catch (IOException e) {
                info.put("error", "No se pudo leer metadatos: " + e.getMessage());
            } catch (Exception e) {
                info.put("error", "Error evaluando archivo: " + e.getMessage());
            }

            archivos.add(info);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("totalCodigos", totalCodigos);
        result.put("archivosPresentes", archivosPresentes);
        result.put("totalSizeBytes", totalSizeBytes);
        result.put("archivos", archivos);

        return Response.ok(result).build();
    }

    /// Dado que ahora la lógica es multi archivo, preview y download ya no van
    /*
    @GET
    @jakarta.ws.rs.Path("/preview")
    @Produces(MediaType.TEXT_PLAIN)
    public Response preview(@QueryParam("lines") @DefaultValue("20") int lines){
        java.nio.file.Path p = filePath();
        String filename = p.getFileName().toString();

        if(!Files.exists(p)){
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("No existe el archivo " + filename).build();
        }
        if (lines <= 0) lines = 20;

        try (java.io.BufferedReader br = Files.newBufferedReader(
                p, java.nio.charset.StandardCharsets.UTF_8)) {
            String content = br.lines().limit(lines).reduce((a, b) -> a + "\n" + b).orElse("");
            return Response.ok(content).build();
        } catch (java.io.IOException e) {
            return Response.serverError()
                    .entity("Error al leer el archivo: " + e.getMessage()).build();
        }
    }

    @GET
    @jakarta.ws.rs.Path("/download")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response download(){
        java.nio.file.Path p = filePath();
        String filename = p.getFileName().toString();

        if (!Files.exists(p)){
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("No existe el archivo " + filename).build();
        }

        try{
            // Nota: java.io.File está bien aquí, pero Files.newInputStream(p) sería más moderno.
            File f = p.toFile();
            return Response.ok(f)
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .build();
        }
        catch (Exception e){
            return Response.serverError().entity("Error al leer el archivo: " + e.getMessage()).build();
        }
    }
     */

    @POST
    @Path("/crear")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response crearPedido(PedidoRequest request) {
        try {

            /// Primero, validamos si existe un run de OD activo
            String runId = runManager.ensureOperacionStarted();
            if (runId == null) {
                return Response
                        .status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(new JsonResponse("error", "Inicie una ejecución de Operación Diaria primero.", null))
                        .build();
            }

            if (request == null) return bad("Body requerido");
            if (isBlank(String.valueOf(request.idCliente))) return bad("El idCliente es requerido"); 
            if (isBlank(request.destino)) return bad("El destino es requerido");
            if (isBlank(request.fecha)) return bad("La fecha es requerida");
            if (isBlank(String.valueOf(request.cantidad))) return bad("La cantidad es requerida");

            /// Obtenemos la fecha para transformarla a la del destino
            LocalDateTime fechaPeru = LocalDateTime.parse(request.fecha);
            String destino = request.destino;
            LocalDateTime fecha = runManager.ajustarFechaPedidoPorDestino(fechaPeru, destino);

            Pedido pedido = PedidoMapper.toPedido(request, fecha);
            int idGenerado = pedido.getIdPedido();

            String msg = "Pedido del cliente (" + request.idCliente + ") con destino " +
                    "a " + request.destino + " creado correctamente con id " + idGenerado + " a las " + fecha + " (" + destino + ")";

            runManager.pushOrder(runId, pedido);
            pedidosService.appendPedido(pedido);

            return Response
                    .ok(new PedidoResponse("success", msg, runId))
                    .build();
        }
        catch(Exception e){
            return Response
                    .status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new JsonResponse("error", "Error al crear pedido: " + e.getMessage(), null))
                    .build();
        }
    }

    /// Endpoint para testing
    @POST
    @Path("/crear-test")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response crearPedidoTest() {
        try {
            // 1) Construimos el pedido DIRECTAMENTE con el constructor
            //    Ajusta los valores en duro como quieras
            int idPedido = 9999; // o cualquier id de prueba
            int idCliente = 333;
            String destino = "SKBO";
            // "AAAA-MM-DDT:HH:MM:SS"
            LocalDateTime fecha = LocalDateTime.parse("2025-12-12T00:00:35");
            int cantidad = 1;

            Pedido pedido = new Pedido(
                    idPedido,
                    idCliente,
                    destino,
                    fecha,
                    cantidad
            );

            // 2) Obtenemos / creamos el run de Operación Diaria
            String runId = runManager.ensureOperacionStarted();

            System.out.println("El runId es " + runId);

            // 3) Encolamos el pedido en la cola de dicho run
            runManager.pushOrder(runId, pedido);

            // 4) Armamos un mensaje similar al de crearPedido "normal"
            String msg = "Pedido de prueba (cliente " + idCliente + ") con destino " +
                    destino + " creado correctamente con id " + idPedido +
                    " a las " + fecha + ".";

            return Response.ok(
                    new PedidoResponse("success", msg, runId)
            ).build();

        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new JsonResponse("error",
                            "Error al crear pedido de prueba: " + e.getMessage(), null))
                    .build();
        }
    }



    private static boolean isBlank(String s) { return s == null || s.isEmpty(); }
    private static Response bad(String msg) {
        return Response.status(Response.Status.BAD_REQUEST).entity(new PedidosController.ErrorDTO(msg)).build();
    }
    private static final class ErrorDTO { public final String message; ErrorDTO(String m){ this.message = m; } }

    private java.nio.file.Path filePath(String codigo){
        return pedidosService.getPedidosFilePath(codigo);
    }

    private String lastModifiedIso(java.nio.file.Path p) throws IOException{
        FileTime ft = Files.getLastModifiedTime(p);
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ft.toInstant().atOffset(ZoneOffset.UTC));
    }
}