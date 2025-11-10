package pe.edu.pucp.morapack.airscheduler.api.controllers;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import pe.edu.pucp.morapack.airscheduler.api.response.JsonResponse;
import pe.edu.pucp.morapack.airscheduler.api.response.PedidoResponse;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.ArchivoUtils;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.CargarPedidos;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.RunManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.Scanner;

@Path("/operacionDiaria")
public class OperacionDiariaController {

    @Inject
    RunManager runManager;

    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadPedidosOD(@FormParam("file") InputStream fileInputStream){
        try {
            //Dado que este archivo no se "guarda", sino que se utiliza para procesar pedidos, no necesitamos rutas ni nada por el estilo
            CargarPedidos pedidos = new CargarPedidos();

            try (Scanner sc = ArchivoUtils.getScanner(fileInputStream)) {
                if (sc == null){
                    return Response
                            .status(Response.Status.INTERNAL_SERVER_ERROR)
                            .entity(new JsonResponse("error", "Error al crear pedido", null))
                            .build();
                }
                pedidos.leerDatosProfe(sc);
                System.out.println("[OperacionDiariaController] Pedidos cargados: " + pedidos.getLista().size());

            } catch (Exception e) {
                System.err.println("[OperacionDiariaController] Error procesando archivo de pedidos: " + e.getMessage());
            }


            int cantPedidos = pedidos.getLista().size();
            String message = "¡Se agregaron " + cantPedidos + " pedidos en Operación Diaria!";


            return Response
                    .ok(new PedidoResponse("success", message, null))
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new PedidoResponse("error", "Error al guardar el archivo: " + e.getMessage(), null))
                    .build();
        }
    }

}
