package pe.edu.pucp.morapack.airscheduler.api.controllers;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files; // Necesario para abrir el stream del temp
import java.nio.file.attribute.FileTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

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
    
    private static final String FILENAME = "pedidos.txt";
    
    @Inject
    RunManager runManager;

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

        try {
            // 1. Obtenemos el path del archivo temporal que Quarkus ya guardó en disco
            java.nio.file.Path tempPath = fileUpload.uploadedFile();

            // 2. Abrimos un stream desde ese archivo temporal
            // Usamos try-with-resources para asegurar que se cierre el stream
            try (InputStream fileInputStream = Files.newInputStream(tempPath)) {
                
                // 3. Delegamos a tu servicio (que ya usa la lógica de ArchivoManager)
                // Tu servicio leerá este stream y lo copiará a la carpeta final
                java.nio.file.Path targetPath = pedidosService.guardarArchivoPedidos(fileInputStream);

                return Response
                    .ok(new JsonResponse("success", "Archivo de pedidos guardado exitosamente", targetPath.toAbsolutePath().toString()))
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
    public Response getStatus(){
        java.nio.file.Path p = filePath();
        boolean exists = Files.exists(p);

        Map<String, Object> body = new HashMap<>();
        body.put("exists", exists);
        body.put("filename", FILENAME);

        if (exists) {
            try{
                body.put("sizeBytes", Files.size(p));
                body.put("lastModified", lastModifiedIso(p));
            }
            catch (IOException e){
                body.put("error", "No se pudo leer metadatos: " + e.getMessage());
            }
        }

        return Response.ok(body).build();
    }

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

    @POST
    @Path("/crear")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response crearPedido(PedidoRequest request) {
        try {
            if (request == null) return bad("Body requerido");
            if (isBlank(String.valueOf(request.idCliente))) return bad("El idCliente es requerido"); 
            if (isBlank(request.destino)) return bad("El destino es requerido");
            if (isBlank(request.fecha)) return bad("La fecha es requerida");
            if (isBlank(String.valueOf(request.cantidad))) return bad("La cantidad es requerida");

            Pedido pedido = PedidoMapper.toPedido(request);
            int idGenerado = pedido.getIdPedido();

            String msg = "Pedido del cliente (" + request.idCliente + ") con destino " +
                    "a " + request.destino + " creado correctamente con id " + idGenerado + " a las " + request.fecha;

            String runId = runManager.ensureOperacionStarted();
            runManager.pushOrder(runId, pedido);

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

    private static boolean isBlank(String s) { return s == null || s.isEmpty(); }
    private static Response bad(String msg) {
        return Response.status(Response.Status.BAD_REQUEST).entity(new PedidosController.ErrorDTO(msg)).build();
    }
    private static final class ErrorDTO { public final String message; ErrorDTO(String m){ this.message = m; } }

    private java.nio.file.Path filePath(){
        return pedidosService.getPedidosFilePath();
    }

    private String lastModifiedIso(java.nio.file.Path p) throws IOException{
        FileTime ft = Files.getLastModifiedTime(p);
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ft.toInstant().atOffset(ZoneOffset.UTC));
    }
}