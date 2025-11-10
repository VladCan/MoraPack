package pe.edu.pucp.morapack.airscheduler.api.controllers;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
// Eliminamos el import de ConfigProperty
import java.io.IOException;
import java.io.InputStream;
// Eliminamos los imports de Files y StandardCopyOption

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import pe.edu.pucp.morapack.airscheduler.api.mapper.PedidoMapper;
import pe.edu.pucp.morapack.airscheduler.api.response.JsonResponse;
import pe.edu.pucp.morapack.airscheduler.api.response.PedidoResponse;
import pe.edu.pucp.morapack.airscheduler.api.service.PedidosService; // <-- Nuevo: Importar el service
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.RunManager;


@Path("/pedidos")
public class PedidosController {
    
    // Eliminamos: @ConfigProperty(name = "morapack.upload.dir") String uploadDir;
    
    // Inyectamos el nuevo PedidosService (asumimos que RunManager también es inyectado aquí si se usa directamente en crearPedido)
    @Inject 
    PedidosService pedidosService; // <-- Nuevo: Service para manejar la persistencia
    
    @Inject
    RunManager runManager;

    public static final class PedidoRequest{
        public Integer idCliente;
        public String destino;
        public String fecha;
        public Integer cantidad;
    }

    // Endpoint para recibir el archivo y guardarlo (DELEGACIÓN AL SERVICE)
    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadPedidos(
            @FormParam("file") InputStream fileInputStream) {
        
        try {
            // **DELEGACIÓN:** El service se encarga de obtener la ruta, crear el directorio y copiar el archivo.
            java.nio.file.Path targetPath = pedidosService.guardarArchivoPedidos(fileInputStream);
            
            return Response
                    .ok(new JsonResponse("success", "Archivo de pedidos guardado exitosamente", targetPath.toAbsolutePath().toString()))
                    .build();
        } catch (IOException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new JsonResponse("error", "Error al guardar el archivo: " + e.getMessage(), null))
                    .build();
        }
    }

    // Dado que la creación de un pedido sí o sí está conectada solamente a la operación diaria, podemos llamar
    // a runManager dentro del método
    @POST
    @Path("/crear")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response crearPedido(PedidoRequest request) {
        try {
            // ... (Validación y conversión se mantienen) ...
            
            //Primero, validamos
            if (request == null) return bad("Body requerido");
            // Nota: isBlank es mejor para Strings, no para Integers.
            // Considera validar que idCliente y cantidad no sean null y sean positivos.
            if (isBlank(String.valueOf(request.idCliente))) return bad("El idCliente es requerido"); 
            if (isBlank(request.destino)) return bad("El destino es requerido");
            if (isBlank(request.fecha)) return bad("La fecha es requerida");
            if (isBlank(String.valueOf(request.cantidad))) return bad("La cantidad es requerida");

            //Si todo ok, convertimos a pedido
            Pedido pedido = PedidoMapper.toPedido(request);
            int idGenerado = pedido.getIdPedido();

            String msg = "Pedido del cliente (" + request.idCliente + ") con destino " +
                    "a " + request.destino + " creado correctamente con id " + idGenerado + " a las " + request.fecha;

            //Ahora vamos a ver si hay un run de OperaciónDiaria activo.
            String runId = runManager.ensureOperacionStarted();

            //Ahora, encolamos el pedido
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
}