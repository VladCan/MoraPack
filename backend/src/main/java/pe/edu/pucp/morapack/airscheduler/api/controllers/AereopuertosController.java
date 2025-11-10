package pe.edu.pucp.morapack.airscheduler.api.controllers;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

// Importante: Calificamos jakarta.ws.rs.Path para evitar colisiones con java.nio.file.Path

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import pe.edu.pucp.morapack.airscheduler.api.dto.AeropuertoDTO;
import pe.edu.pucp.morapack.airscheduler.api.mapper.AeropuertoMapper;
import pe.edu.pucp.morapack.airscheduler.api.service.AeropuertosService;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.ArchivoManager; // <-- Nuevo
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Aeropuerto;


@jakarta.ws.rs.Path("/aereopuertos")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
public class AereopuertosController {
    
    // Eliminamos @ConfigProperty(name = "morapack.upload.dir") String uploadDir;
    // Eliminamos private static final String FILENAME = "/aereopuertos.txt";

    @Inject
    AeropuertosService service;
    
    @Inject // Inyectamos el Manager para los GETs (status, preview, download)
    ArchivoManager archivoManager;
    private static final String FILENAME = "aereopuertos.txt";

    // Endpoint para recibir el archivo y guardarlo (DELEGACIÓN AL SERVICE)
    @POST
    @jakarta.ws.rs.Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadPedidos(
            @FormParam("file") InputStream fileInputStream) {
        
        try {
            // DELEGACIÓN COMPLETA al Service para manejar la persistencia
            Path targetPath = service.guardarArchivoDatos(fileInputStream); 
            
            return Response
                    .ok(Map.of("status", "success", 
                               "message", "Archivo de aereopuertos guardado exitosamente", 
                               "path", targetPath.toAbsolutePath().toString()))
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
        Path p = filePath();
        boolean exists = Files.exists(p);

        Map<String, Object> body = new HashMap<>();
        body.put("exists", exists);
        body.put("filename", p.getFileName().toString());

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
        Path p = filePath();
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
    private Path filePath(){
        // Usa el Manager para obtener la carpeta base y anexarle el nombre del archivo.
        return java.nio.file.Paths.get(archivoManager.getUploadDir(), FILENAME);
    }

    private String lastModifiedIso(Path p) throws IOException{
        FileTime ft = Files.getLastModifiedTime(p);
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ft.toInstant().atOffset(ZoneOffset.UTC));
    }


    // ... (El resto de los métodos listar, obtener, y parseCodes se mantienen sin cambios) ...
    @GET
    public Response listar(
            @QueryParam("codes") String codes,
            @QueryParam("continente") String continente,
            @QueryParam("minCapacidad") Integer minCapacidad,
            @QueryParam("bbox") String bbox,
            @QueryParam("soloSedes") @DefaultValue("false") boolean soloSedes 
    ) {
        Set<String> codeSet = parseCodes(codes);
        Double minLon = null, minLat = null, maxLon = null, maxLat = null;
        if (bbox != null && !bbox.isBlank()) {
            String[] p = bbox.split(",");
            if (p.length == 4) {
                try {
                    minLon = Double.valueOf(p[0].trim());
                    minLat = Double.valueOf(p[1].trim());
                    maxLon = Double.valueOf(p[2].trim());
                    maxLat = Double.valueOf(p[3].trim());
                } catch (NumberFormatException ignored) {}
            }
        }

        List<Aeropuerto> resultado = service.filtrar(
                codeSet, continente, minCapacidad, minLon, minLat, maxLon, maxLat
        );

        // si piden solo sedes, filtramos aquí
        if (soloSedes) {
            resultado = resultado.stream()
                    .filter(a -> service.esSede(a.getCodigo()))
                    .collect(Collectors.toList());
        }

        List<AeropuertoDTO> dto = resultado.stream()
                .map(a -> AeropuertoMapper.toDTO(a, service.esSede(a.getCodigo())))
                .collect(Collectors.toList());

        return Response.ok(dto).build();
    }

    @GET
    @jakarta.ws.rs.Path("/{codigo}")
    public Response obtener(@PathParam("codigo") String codigo) {
        return service.obtenerPorCodigo(codigo)
                .map(a -> Response.ok(AeropuertoMapper.toDTO(a, service.esSede(a.getCodigo()))).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Aeropuerto no encontrado: " + codigo))
                        .build());
    }

    private static Set<String> parseCodes(String codes) {
        if (codes == null || codes.isBlank()) return Collections.emptySet();
        String[] arr = codes.split(",");
        Set<String> out = new HashSet<>();
        for (String s : arr) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}