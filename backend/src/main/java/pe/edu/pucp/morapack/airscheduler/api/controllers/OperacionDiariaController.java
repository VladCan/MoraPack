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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

@Path("/operacionDiaria")
public class OperacionDiariaController {

    private static boolean archivoCargado = false;

    @Inject
    RunManager runManager;

    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadPedidosOD(@FormParam("file") InputStream fileInputStream){
        try {
            /// 1) Verificamos si existe run activo. Caso contrario, se retorna mensaje de error.
            /// pd: es un consenso tomado. Se puede cambiar, en el código comentando se explica como
            String runId = runManager.currentOperacionRunId();
            if (runId == null){
                System.out.println("No hay Operación Diaria activa. Iníciala antes de subir pedidos.");
                return Response
                        .status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(new JsonResponse("error",
                                "No hay Operación Diaria activa. Iníciala antes de subir pedidos.",
                                null))
                        .build();
            }
            //Si vamos a generar un run a partir del archivo:
            //runId = runManager.ensureOperacionStarted();

            /// 2.1) Leemos el contenido del archivo (NO normalizamos UTC, eso es dentro del RunManager)

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
                archivoCargado = false;
                System.err.println("[OperacionDiariaController] Error procesando archivo de pedidos: " + e.getMessage());
            }

            /// 2.2) Anclamos la fecha actual (por ahora, solo dd/mm/aaaa, no las horas)
            runManager.normalizarFechasOD(runId, pedidos.getLista());

            /// 3) Encolamos en la cola existente en RunManager
            runManager.pushOrders(runId, pedidos.getLista());

            /// 4) Operación exitosa, mostramos mensajes de conformidad.
            int cantPedidos = pedidos.getLista().size();
            String message = "¡Se agregaron " + cantPedidos + " pedidos en Operación Diaria!";

            archivoCargado = true;

            return Response
                    .ok(new PedidoResponse("success", message, null))
                    .build();
        } catch (Exception e) {
            archivoCargado = false;
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new PedidoResponse("error", "Error al guardar el archivo: " + e.getMessage(), null))
                    .build();
        }
    }

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStatus() {
        Map<String, Object> body = new HashMap<>();
        body.put("exists", archivoCargado);

        return Response.ok(body).build();
    }

    @POST
    @Path("/{id}/force")
    public Response forceReplan(@PathParam("id") String runId){
        System.out.println("[OperacionDiariaController]: Se recibió un forceReplan");

        runManager.setForcedReplan(runId);

        return Response.ok().build();
    }

}
