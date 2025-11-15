package pe.edu.pucp.morapack.airscheduler.api.controllers;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.MediaType;
import jakarta.inject.Inject; 
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path; 
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

import pe.edu.pucp.morapack.airscheduler.api.service.VuelosArchivoService; // <-- NUEVO: Inyectar el service de archivos
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.VueloProgramadoId;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.RunManager;

// Nota: Usamos jakarta.ws.rs.Path explícitamente para evitar colisión con java.nio.file.Path

@jakarta.ws.rs.Path("/vuelos")
public class VueloController {
    
    // Eliminamos @ConfigProperty(name = "morapack.upload.dir") String uploadDir;
    // Eliminamos ArchivoManager, ahora solo inyectamos el service específico.
    
    @Inject
    VuelosArchivoService vuelosArchivoService; // <-- Nuevo: Service que maneja I/O
    
    // Mantenemos el FILENAME para el cuerpo de las respuestas HTTP
    private static final String FILENAME = "vuelos.txt";

    @Inject
    RunManager runManager;

    public static final class CancelarVueloRequest{
        public String origen;
        public String destino;
        public String salidaUtc;
        public String llegadaUtc;
    }

    // Endpoint para recibir el archivo y guardarlo (DELEGACIÓN AL SERVICE)
    @POST
    @jakarta.ws.rs.Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadPlanDeVuelo(
            @FormParam("file") InputStream fileInputStream) {
        
        try {
            // **DELEGACIÓN:** El service se encarga de la lógica de guardado
            Path targetPath = vuelosArchivoService.guardarArchivoPlanesDeVuelo(fileInputStream);
            
            return Response
                    .ok(Map.of("status", "success", 
                               "message", "Archivo planes de vuelos guardado exitosamente", 
                               "path", targetPath.toAbsolutePath().toString()))
                    // Reemplazado JsonResponse por Map (o puedes usar tu JsonResponse si está disponible)
                    .build(); 
        } catch (IOException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("status", "error", 
                                   "message", "Error al guardar el archivo: " + e.getMessage()))
                    .build();
        }
    }

    @GET
    @jakarta.ws.rs.Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStatus() {
        // **USO DEL SERVICE:** Obtener la ruta
        Path p = filePath();
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
        Path p = filePath();
        if(!Files.exists(p)){
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("No existe el archivo " + FILENAME).build();
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
        // **USO DEL SERVICE:** Obtener el objeto File
        File f = vuelosArchivoService.getVuelosFileForDownload();
        
        if (f == null || !f.exists()){
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("No existe el archivo " + FILENAME).build();
        }

        try{
            return Response.ok(f)
                    .header("Content-Disposition", "attachment; filename=\"" + FILENAME + "\"")
                    .build();
        }
        catch (Exception e){
            return Response.serverError().entity("Error al leer el archivo: " + e.getMessage()).build();
        }

    }

    /// Privados para rutas:
    private Path filePath(){
        // Usa el Service para obtener la ruta
        return vuelosArchivoService.getVuelosFilePath(); 
    }

    // Eliminamos ensureDirExists (la lógica está ahora en el Service)
    
    private String lastModifiedIso(Path p) throws IOException{
        FileTime ft = Files.getLastModifiedTime(p);
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ft.toInstant().atOffset(ZoneOffset.UTC));
    }
    // ... (El resto de la clase se mantiene) ...

    @POST
    @jakarta.ws.rs.Path("/{runId}/cancelar")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response cancelar(@PathParam("runId") String runId,
                             CancelarVueloRequest body) {

        //Primero, validamos
        if (body == null){
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        if (isBlank(runId) || isBlank(body.origen) || isBlank(body.destino) || isBlank(body.salidaUtc)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("runId, origen, destino y salidaUtc son requeridos").build();
        }

        //Luego, parseamos
        try{
            Instant salida = Instant.parse(body.salidaUtc);
            Instant llegada = (body.llegadaUtc != null && !body.llegadaUtc.isBlank())
                    ? Instant.parse(body.llegadaUtc)
                    : null;

            //Reconstruimos exactamente el mismo VueloProgramadoId
            VueloProgramadoId vueloProgramadoId = new VueloProgramadoId(body.origen, body.destino, salida, llegada);

            //Registramos en el RunManager
            runManager.registrarCancelacionVuelo(runId, vueloProgramadoId);

            return Response.status(Response.Status.CREATED)
                    .entity("Cancelado")
                    .build();

        }
        catch (DateTimeParseException e){
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Formato de fecha inválido. Usa ISO-8601, ej: 2025-01-02T06:32:00Z")
                    .build();
        }
        catch (Exception e) {
            e.printStackTrace();
            return Response.serverError()
                    .entity("Error al registrar cancelación de vuelo: " + e.getMessage())
                    .build();
        }

    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty() || s.isBlank();
    }

}