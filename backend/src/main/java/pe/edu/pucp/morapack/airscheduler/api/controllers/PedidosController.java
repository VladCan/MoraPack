package pe.edu.pucp.morapack.airscheduler.api.controllers;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/pedidos")
public class PedidosController {
    // Endpoint para recibir el archivo y guardarlo
    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadPedidos(
            @FormParam("file") InputStream fileInputStream) {
        // Directorio donde se guardará el archivo
        String directory = "src/main/resources/";
        File outputFile = new File(directory + "pedidos.txt");

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
}
