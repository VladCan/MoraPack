package pe.edu.pucp.morapack.airscheduler.api.controllers;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import pe.edu.pucp.morapack.airscheduler.api.mapper.PedidoMapper;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.RunManager;


@Path("/pedidos")
public class PedidosController {
    // Endpoint para recibir el archivo y guardarlo
    @ConfigProperty(name = "morapack.upload.dir")
    String uploadDir;
    @Inject
    RunManager runManager;

    public static final class PedidoRequest{
        public Integer idCliente;
        public String destino;
        public String fecha;
        public Integer cantidad;
    }

    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadPedidos(
            @FormParam("file") InputStream fileInputStream) {
        // Directorio donde se guardará el archivo
        String directory = uploadDir;
        File outputFile = new File(directory + "/pedidos.txt");

        // Crear el archivo y escribir los datos
        try {
            Files.copy(fileInputStream, outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return Response
                    .ok(new JsonResponse("success", "Archivo de pedidos guardado exitosamente", outputFile.getAbsolutePath()))
                    .build();
        } catch (IOException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new JsonResponse("error", "Error al guardar el archivo: " + e.getMessage(), null))
                    .build();
        }
    }

    //Dado que la creación de un pedido sí o sí está conectada solamente a la operación diaria, podemos llamar
    //a runManager dentro del método
    @POST
    @Path("/crear")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response crearPedido(PedidoRequest request) {
        try {
            //int idGenerado = (int) (Math.random() * 1000) + 1;

            //Primero, validamos
            if (request == null) return bad("Body requerido");
            if (isBlank(String.valueOf(request.idCliente))) return bad("scenario es requerido");
            if (isBlank(request.destino)) return bad("scenario es requerido");
            if (isBlank(request.fecha)) return bad("scenario es requerido");
            if (isBlank(String.valueOf(request.cantidad))) return bad("scenario es requerido");

            //Si todo0 ok, convertimos a pedido
            Pedido pedido = PedidoMapper.toPedido(request);
            int idGenerado = pedido.getIdPedido();

            String msg = "Pedido del cliente (" + request.idCliente + ") con destino " +
                    "a " + request.destino + " creado correctamente con id " + idGenerado + " a las " + request.fecha;

            //Ahora vamos a ver si hay un run de OperaciónDiaria activo.
            //Si existe, devuelve el runId. Caso contrario, lo crea y lo devuelve.
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
