package pe.edu.pucp.morapack.airscheduler.api.controllers.debug;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import pe.edu.pucp.morapack.airscheduler.test.ALNSOperatorsDebug;

import java.util.Map;

@Path("/ALNSDebug")
public class ALNSDebugManager {
    // ====== Estado “como RunManager”, pero aislado ======
    @Inject
    ALNSOperatorsDebug ALNSOperatorsDebug;

    public enum DestructorType {
        RANDOM_REMOVAL,
        WORST_REMOVAL,
        WAREHOUSE_CRISIS_REMOVAL,
        SLA_BREACH_REMOVAL
    }

    public enum ConstructorType {
        SPLIT_REPAIR,
        REGRET_2,
        URGENCY_SPLIT_REPAIR
    }

    @POST
    @Path("/test")
    public Response test() {
        return Response.ok("ALNSDebugManager - OK").build();
    }

    @POST
    @Path("/load")
    public Response load() {
        try{
            ALNSOperatorsDebug.cargarBase();
            return Response
                    .ok("Carga realizada correctamente").build();
        }
        catch (Exception e){
            e.printStackTrace();
            return Response
                    .status(Response.Status.INTERNAL_SERVER_ERROR).build();

        }
    }

    @POST
    @Path("/executeSSP")
    public Response executeSSP() {
        try {
            ALNSOperatorsDebug.ejecutarSSP();
            return Response
                    .ok("Solución SSP ejecutada correctamente").build();
        }
        catch (Exception e){
            e.printStackTrace();
            return Response
                    .status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @POST
    @Path("/destructor")
    public Response destructor(Map<String, String> body) {
        try {
            String opStr = body.get("operator");
            if (opStr == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "INVALID_REQUEST", "message", "Falta 'operator'"))
                        .build();
            }

            DestructorType op = DestructorType.valueOf(opStr.toUpperCase());

            ALNSOperatorsDebug.ejecutarDestructor(op);

            /*
            switch (op) {
                case RANDOM_REMOVAL -> ejecutarRandomRemoval();
                case WORST_REMOVAL -> ejecutarWorstRemoval();
                case WAREHOUSE_CRISIS_REMOVAL -> ejecutarWarehouseCrisisRemoval();
                case SLA_BREACH_REMOVAL -> ejecutarSlaBreachRemoval();
            }*/

            return Response.ok(Map.of("message", "Destructor ejecutado: " + op)).build();

        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "INVALID_DESTRUCTOR", "message", "Destructor no soportado"))
                    .build();
        } catch (Exception e) {
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "INTERNAL_ERROR", "message", "Falló la ejecución del destructor"))
                    .build();
        }
    }

    @POST
    @Path("/constructor")
    public Response constructor(Map<String, String> body) {
        try {
            String opStr = body.get("operator");
            if (opStr == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "INVALID_REQUEST", "message", "Falta 'operator'"))
                        .build();
            }

            ConstructorType op = ConstructorType.valueOf(opStr.toUpperCase());

            ALNSOperatorsDebug.ejecutarConstructor(op);

            /*switch (op) {
                case SPLIT_REPAIR -> ejecutarSplitRepair();
                case REGRET_2 -> ejecutarRegret2Repair();
                case URGENCY_SPLIT_REPAIR -> ejecutarUrgencySplitRepair();
            }*/

            return Response.ok(Map.of("message", "Constructor ejecutado: " + op)).build();

        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "INVALID_CONSTRUCTOR", "message", "Constructor no soportado"))
                    .build();
        } catch (Exception e) {
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "INTERNAL_ERROR", "message", "Falló la ejecución del constructor"))
                    .build();
        }
    }

    @Path("/reestablish")
    @POST
    public Response reestablish() {
        try{
            ALNSOperatorsDebug.reestablecer();
            return Response.ok(Map.of("message", "Solución reestablecida" +
                    "a los valores del SSP original")).build();
        } catch (Exception e) {
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "INTERNAL_ERROR", "message", "Falló el reestablecer."))
                    .build();
        }
    }

}
