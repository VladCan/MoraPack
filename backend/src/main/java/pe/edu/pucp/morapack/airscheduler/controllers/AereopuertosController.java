package pe.edu.pucp.morapack.airscheduler.controllers;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.api.dto.AeropuertoDTO;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.api.mapper.AeropuertoMapper;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.Aeropuerto;
import pe.edu.pucp.morapack.airscheduler.flights.service.AeropuertosService;

@Path("/aereopuertos")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
public class AereopuertosController {

    @Inject
    AeropuertosService service;
    // Endpoint para recibir el archivo y guardarlo
    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadPedidos(
            @FormParam("file") InputStream fileInputStream) {
        // Directorio donde se guardará el archivo
        String directory = "src/main/resources/";
        File outputFile = new File(directory + "aereopuertos.txt");

        // Crear el archivo y escribir los datos
        try {
            Files.copy(fileInputStream, outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return Response
                    .ok(new JsonResponse("success", "Archivo de aereopuertos guardado exitosamente", outputFile.getAbsolutePath()))
                    .build();
        } catch (IOException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new JsonResponse("error", "Error al guardar el archivo: " + e.getMessage(), null))
                    .build();
        }
    }

    @GET
    public Response listar(
            @QueryParam("codes") String codes,
            @QueryParam("continente") String continente,
            @QueryParam("minCapacidad") Integer minCapacidad,
            @QueryParam("bbox") String bbox,
            @QueryParam("soloSedes") @DefaultValue("false") boolean soloSedes // <--- opcional
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
    @Path("/{codigo}")
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
