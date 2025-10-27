package pe.edu.pucp.morapack.airscheduler.api.controllers;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Path("/vuelos")
public class VueloController {
    @ConfigProperty(name = "morapack.upload.dir")
    String uploadDir;
    private final String DIRECTORY = uploadDir;
    private static final String FILENAME  = "planesDeVuelo.txt";

    // Endpoint para recibir el archivo y guardarlo
    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadPlanDeVuelo(
            @FormParam("file") InputStream fileInputStream) {
        // Directorio donde se guardará el archivo

        File outputFile = new File(DIRECTORY + FILENAME);

        // Crear el archivo y escribir los datos
        try {
            Files.copy(fileInputStream, outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return Response
                    .ok(new JsonResponse("success", "Archivo planes de vuelos guardado exitosamente", outputFile.getAbsolutePath()))
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
    public Response getStatus() {
        /// Hay que poner java.nio.file.Path para que no se confunda con el Path de Jakarta (There's no other way)
        java.nio.file.Path p = java.nio.file.Paths.get("src/main/resources/planesDeVuelo.txt");
        boolean exists = Files.exists(p);

        Map<String, Object> body  = new HashMap<>();
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

        // Si usas tu JsonResponse, cámbialo aquí. Devuelvo map simple para rapidez.
        return Response.ok(body).build();
    }

    @GET
    @Path("/preview")
    @Produces(MediaType.TEXT_PLAIN)
    public Response preview(@QueryParam("lines") @DefaultValue("20") int lines){
        java.nio.file.Path p = filePath();
        if(!Files.exists(p)){
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("No existe el archivo " + FILENAME).build();
        }
        if (lines <= 0) lines = 20;

        try (java.io.BufferedReader br = java.nio.file.Files.newBufferedReader(
                p, java.nio.charset.StandardCharsets.UTF_8)) {
            String content = br.lines().limit(lines).reduce((a, b) -> a + "\n" + b).orElse("");
            return Response.ok(content).build();
        } catch (java.io.IOException e) {
            return Response.serverError()
                    .entity("Error al leer el archivo: " + e.getMessage()).build();
        }
    }

    @GET
    @Path("/download")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response download(){
        java.nio.file.Path p = filePath();
        if (!java.nio.file.Files.exists(p)){
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("No existe el archivo " + FILENAME).build();
        }

        try{
            java.io.File f = p.toFile();
            return Response.ok(f)
                    .header("Content-Disposition", "attachment; filename=\"" + FILENAME + "\"")
                    .build();
        }
        catch (Exception e){
            return Response.serverError().entity("Error al leer el archivo: " + e.getMessage()).build();
        }

    }

    /// Privados para rutas:
    private java.nio.file.Path filePath(){
        return Paths.get(DIRECTORY, FILENAME);
    }

    private void ensureDirExists() throws IOException{
        Files.createDirectories(Paths.get(DIRECTORY));
    }

    private String lastModifiedIso(java.nio.file.Path p) throws IOException{
        FileTime ft = Files.getLastModifiedTime(p);
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ft.toInstant().atOffset(ZoneOffset.UTC));
    }

}