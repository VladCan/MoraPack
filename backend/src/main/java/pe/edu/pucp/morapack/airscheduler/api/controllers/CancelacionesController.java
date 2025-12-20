package pe.edu.pucp.morapack.airscheduler.api.controllers;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import pe.edu.pucp.morapack.airscheduler.api.response.JsonResponse;
import pe.edu.pucp.morapack.airscheduler.api.service.CancelacionesService;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Path("/cancelaciones")
public class CancelacionesController {

    @Inject
    CancelacionesService cancelacionesService;

    // Mantenemos el FILENAME para el cuerpo de las respuestas HTTP
    private static final String FILENAME = "cancelaciones.txt";

    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadCancelaciones(
            @FormParam("file") InputStream fileInputStream) {

        try (InputStream is = fileInputStream) {
            // **DELEGACIÓN:** El service se encarga de obtener la ruta, crear el directorio y copiar el archivo.
            java.nio.file.Path targetPath = cancelacionesService.guardarArchivoCancelaciones(is);
            System.out.println(cancelacionesService.isNuevoArchivoSubido());
            // Levanta una bandera para saver que se subio un nuevo archivo
            return Response
                    .ok(new JsonResponse("success", "Archivo de pedidos guardado exitosamente", targetPath.toAbsolutePath().toString()))
                    .build();
        } catch (IOException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new JsonResponse("error", "Error al guardar el archivo: " + e.getMessage(), null))
                    .build();
        }
    }

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
            File f = p.toFile();
            return Response.ok(f)
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .build();
        }
        catch (Exception e){
            return Response.serverError().entity("Error al leer el archivo: " + e.getMessage()).build();
        }
    }

    /// Privados para rutas:
    private java.nio.file.Path filePath(){
        return cancelacionesService.getCancelacionesFilePath();
    }

    private String lastModifiedIso(java.nio.file.Path p) throws IOException{
        FileTime ft = Files.getLastModifiedTime(p);
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ft.toInstant().atOffset(ZoneOffset.UTC));
    }


}
