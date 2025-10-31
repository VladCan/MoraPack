package pe.edu.pucp.morapack.airscheduler.api.controllers;
import jakarta.ws.rs.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/pedidos")
public class PedidosController {
    // Endpoint para recibir el archivo y guardarlo
    @ConfigProperty(name = "morapack.upload.dir")
    String uploadDir;

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

    @POST
    @Path("/crear")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response crearPedido(PedidoRequest request) {
        try{
            int idGenerado = (int) (Math.random() * 1000) + 1;

            String msg = "Pedido del cliente (" + request.idCliente +") con destino " +
                    "a " + request.destino + " creado correctamente con id " + idGenerado + "a las " + request.fecha;

            return Response
                    .ok(new JsonResponse("success", msg, null))
                    .build();
        }
        catch(Exception e){
            return Response
                    .status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new JsonResponse("error", "Error al crear pedido: " + e.getMessage(), null))
                    .build();
        }
    }

    private static final class ErrorDTO { public final String message; ErrorDTO(String m){ this.message = m; } }

}
