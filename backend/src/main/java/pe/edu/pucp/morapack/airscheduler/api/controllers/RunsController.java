package pe.edu.pucp.morapack.airscheduler.api.controllers;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.RunConfig;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.RunContext;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.RunId;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.RunManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.hibernate.internal.util.StringHelper.isBlank;

@Path("/runs")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class RunsController {

    @Inject
    RunManager runManager;

    public static final class StartRunRequest {
        public String scenario;          // OPERACION | SIM_SEMANAL | COLAPSO
        public String startUtc;          // ISO-8601
        public String endUtc;            // ISO-8601 (alternativo a horizonHours)
        public Integer horizonHours;     // alternativo a endUtc
        public Integer windowHours;    // tamaño de ventana
        public Long seed;                // opcional
        public Object params;            // opcional (ALNS/config extra)
        public OrdersSource ordersSource; // { type:"FILE"|"LIVE", fileId? }

        public static final class OrdersSource {
            public String type;   // "FILE" | "LIVE"
            public String fileId; // requerido si type == "FILE"
        }
    }
    @RegisterForReflection
    public static final class StartRunResponse {
        public String runId;
        public String status; // "STARTED"
        public  StartRunResponse(String runId) { this.runId = runId; this.status = "STARTED"; }
    }

    @POST
    public Response start(StartRunRequest body) {

        Set<String> sedes = new HashSet<>(Arrays.asList("SPIM", "EBCI", "UBBB"));

        //Primero hacemos validaciones claras
        if (body == null) return bad("Body requerido");
        if (isBlank(body.scenario)) return bad("scenario es requerido");
        if (isBlank(body.startUtc)) return bad("startUtc es requerido");
        if ((isBlank(body.endUtc) && body.horizonHours == null) ||
                (!isBlank(body.endUtc) && body.horizonHours != null)) {
            return bad("Debes enviar endUtc O horizonHours (uno, no ambos).");
        }
        if (body.windowHours == null || body.windowHours <= 0)
            return bad("windowHours debe ser > 0");
        /*if (body.ordersSource == null || isBlank(body.ordersSource.type))
            return bad("ordersSource.type es requerido (FILE o LIVE)");
        if ("FILE".equalsIgnoreCase(body.ordersSource.type) && isBlank(body.ordersSource.fileId))
            return bad("ordersSource.fileId es requerido cuando type = FILE");*/

        //Hacemos parse a los parámetros
        final RunConfig.Scenario scenario;
        try {
            scenario = RunConfig.Scenario.valueOf(body.scenario.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return bad("scenario inválido. Usa OPERACION | SIM_SEMANAL | COLAPSO");
        }
        final Instant start = Instant.parse(body.startUtc);
        final Instant end = (body.endUtc == null || body.endUtc.isBlank()) ? null : Instant.parse(body.endUtc);
        final int windowHours = body.windowHours;

        //Construimos el RunConfig con el tamaño de ventana configurable
        RunConfig config;
        Duration windowSize = Duration.ofHours(windowHours);
        
        if (end != null) {
            switch (scenario) {
                case SIM_SEMANAL:
                    config = RunConfig.simSemanal(start, end, sedes, windowSize);
                    break;
                case COLAPSO:
                    config = RunConfig.colapso(start, end, sedes, windowSize);
                    break;
                case OPERACION:
                    throw new BadRequestException("OPERACION no requiere endUtc.");
                default:
                    throw new BadRequestException("Scenario no soportado.");
            }
        } else {
            switch (scenario) {
                case SIM_SEMANAL:
                    throw new BadRequestException("SIM_SEMANAL requiere endUtc.");
                case COLAPSO:
                    throw new BadRequestException("COLAPSO requiere endUtc.");
                case OPERACION:
                    config = RunConfig.operacion(sedes, windowSize);
                    break;
                default:
                    throw new BadRequestException("Scenario no soportado.");
            }
        }

        /// TODO, para simulación diaria se tiene que consumir el pedido en vivo.
        /*//EL origen de pedidos:
        config = config.withOrder;
         */



        //Creamos runId
        RunId runId = RunId.create();

        //Creamos runContext
        RunContext runContext = new RunContext(runId, config);
        runManager.addContext(runId.value(), runContext);

        /*
        System.out.println("Estamos en RunsController y vamos a dar 30 sec para que coloques el link del SSE y " +
                "veas los datos enviados. El url es: http://localhost:8080/runs/" + runId.value() + "/stream");
        try { Thread.sleep(30000); } catch (InterruptedException ignored) {}
         */

        System.out.println("Revisa: http://localhost:8080/runs/" + runId.value() + "/stream ");

        //Delegamos al motor
        runManager.start(runId, config);

        return Response.status(Response.Status.CREATED)
                .entity(new StartRunResponse(runId.value()))
                .build();
    }

    private static boolean isBlank(String s) { return s == null || s.isEmpty(); }
    private static Response bad(String msg) {
        return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorDTO(msg)).build();
    }
    private static final class ErrorDTO { public final String message; ErrorDTO(String m){ this.message = m; } }

}
