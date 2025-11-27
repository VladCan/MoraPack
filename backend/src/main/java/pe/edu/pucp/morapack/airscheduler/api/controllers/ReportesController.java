package pe.edu.pucp.morapack.airscheduler.api.controllers;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import pe.edu.pucp.morapack.airscheduler.api.service.ReportesService;

import java.io.File;
import java.nio.file.Files;

@Path("/reportes")
@RequestScoped
public class ReportesController {
    private static final String FILENAME = "reporteSimulacion.txt";

    @Inject
    ReportesService reportesService;

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

    private java.nio.file.Path filePath(){
        return reportesService.getReportesFilePath();
    }




}
