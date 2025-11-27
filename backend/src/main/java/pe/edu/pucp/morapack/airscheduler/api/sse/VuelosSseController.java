// src/main/java/.../controllers/VuelosSseController.java
package pe.edu.pucp.morapack.airscheduler.api.sse;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import pe.edu.pucp.morapack.airscheduler.api.dto.FlightLiveDTO;
import pe.edu.pucp.morapack.airscheduler.api.service.VuelosLiveService;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.RunManager;

import org.jboss.resteasy.reactive.RestStreamElementType;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Path("/vuelos")
@RequestScoped
public class VuelosSseController {

    @Inject VuelosLiveService service;
    
    @Inject RunManager runManager;

        /**
         * SSE: cada ~1s emite un array JSON con los vuelos EN EL AIRE.
         * Query opcional: ?time=HH:mm  (usa esa hora UTC simulada por conexión).
         * Query opcional: ?runId=xxx  (usa el tiempo simulado del run activo).
         */
        // src/main/java/.../controllers/VuelosSseController.java
    @GET
    @Deprecated
    @Path("/live")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<List<FlightLiveDTO>> live(
            @QueryParam("time") String time,
            @QueryParam("runId") String runId,
            @QueryParam("limit") @DefaultValue("50") int limit
    ) {
        VuelosLiveService.NowSupplier nowSupplier;
        try {
            // Prioridad 1: Si hay runId, usar el tiempo simulado del run
            if (runId != null && !runId.isBlank()) {
                final int effectiveLimit = (limit <= 0) ? Integer.MAX_VALUE : Math.min(limit, 500);
                return Multi.createFrom().ticks().every(Duration.ofSeconds(1))
                        .onItem().transform(t -> {
                            try {
                                Instant simNow = runManager.currentSimNow(runId);
                                // Convertir Instant a segundos del día UTC
                                LocalTime utcTime = simNow.atZone(ZoneOffset.UTC).toLocalTime();
                                int nowUtcSeconds = utcTime.toSecondOfDay();
                                return service.snapshot(nowUtcSeconds, effectiveLimit);
                            } catch (Exception e) {
                                System.err.println("[VuelosSseController] Error obteniendo tiempo simulado para runId " + runId + ": " + e.getMessage());
                                return service.snapshot(service.systemNowUtcSeconds(), effectiveLimit);
                            }
                        });
            }
            // Prioridad 2: Si hay time, usar esa hora
            if (time != null && !time.isBlank()) {
                var t = LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm"));
                final int fixed = t.toSecondOfDay();
                nowSupplier = () -> fixed;
            } else {
                // Prioridad 3: Usar hora del sistema
                nowSupplier = service::systemNowUtcSeconds;
            }
        } catch (Exception e) {
            nowSupplier = service::systemNowUtcSeconds;
        }

        // limit <= 0 => sin límite. Tope prudente para evitar DoS involuntario.
        final int effectiveLimit = (limit <= 0) ? Integer.MAX_VALUE : Math.min(limit, 500);

        return service.streamLiveFlights(nowSupplier, effectiveLimit);
    }

    /**
     * SSE: cada ~1s emite un array JSON con todos los vuelos planificados del día siguiente.
     * Incluye vuelos planificados del día siguiente desde el tiempo actual de simulación.
     * No tiene límite de cantidad (solo muestra información, no renderiza vuelos).
     * 
     * Este endpoint está diseñado para permitir la cancelación de vuelos planificados
     * antes de que despeguen, mostrando todos los vuelos relevantes sin límite.
     * 
     * @param runId ID del run activo (opcional). Si no se proporciona, usa el run de operación activo.
     * @return Multi que emite cada segundo la lista de vuelos planificados del día siguiente
     */
    @GET
    @Path("/scheduled/next-day")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<List<Map<String, Object>>> scheduledNextDay(@QueryParam("runId") String runIdParam) {
        return Multi.createFrom().ticks().every(Duration.ofSeconds(1))
                .onItem().transform(t -> {
                    // Si se proporciona un runId, usarlo; sino buscar el run activo apropiado
                    String runId = runIdParam;
                    if (runId == null || runId.isBlank()) {
                        // Primero intentar el run de operación
                        runId = runManager.currentOperacionRunId();
                        // Si no hay run de operación, buscar el último run activo (para simulaciones)
                        if (runId == null) {
                            runId = runManager.getLastActiveRunId();
                        }
                    }
                    
                    System.out.println("[VuelosSseController] scheduledNextDay - runIdParam: " + runIdParam + ", runId final: " + runId);
                    
                    if (runId == null) {
                        System.out.println("[VuelosSseController] No hay run activo disponible");
                        return List.<Map<String, Object>>of(); // Retornar lista vacía si no hay run activo
                    }
                    
                    // Obtener vuelos planificados del día siguiente
                    try {
                        List<Map<String, Object>> vuelos = runManager.getScheduledFlightsNextDay(runId);
                        System.out.println("[VuelosSseController] Vuelos programados obtenidos: " + vuelos.size());
                        return vuelos;
                    } catch (Exception e) {
                        System.err.println("[VuelosSseController] Error obteniendo vuelos programados para runId " + runId + ": " + e.getMessage());
                        e.printStackTrace();
                        return List.<Map<String, Object>>of();
                    }
                });
    }
}
/* 
COSAS A TESTER
 - CLASES A VERFICAR
    - VuelosTEG
        - ver que los vuelos son únicos EN EL GRAFO
        - ver que los aereopuertos no están sobrecargadon con la solución anterior
        - ...
    - SolucionProgramacion
        - ver que los vuelos en la solución no se sobrecargan
        - ver que los vuelos en la salen antes de la hora actual
        - ver que los pedidos se completan
        - ver que los aereopuertos no se sobrecargan
        - ...
- COSAS A IMPLEMENTAR
    - CANCELACIONES
        HORA DE CANCELACIÓN (boing 009 explotó una turbina)
        VUELO CANCELADO dia,hora,origen,destino 
        2025-09-30-06:56,SKBO-SVMI-13:56-17:19
        yyyy-MM-dd-HH:mm,ORIGEN-DESTINO-HH:mm-HH:mm 



FRONT:

SE GENERÓ TODO RESULTADO ====> solucionAnterior

vuelos{
    string idVuelo //origen+destino+fechaSalida LIMEEUU20250930150000
    string salida
    string llega
    date horaUTCsalida
    date horaUTCllegada
}
vueloConPedido{
    Vuelo vuelo
    List<string> idsPedidos
    List<double> cantidadEntragadaPorPedido
    double cargatotal
}


vuelosConPedidos => List<vueloConPedido>

pedidos= sacarTodosLosPedidos(solucionAnterior) //lista que no cambia está estático 10am 4pm
    pedidosActivos
    pedidosCompletados //tiene el historial
vuelosConPedidos= convertirSolucion(solucionAnterior) //lista que no cambia está estático
    vuelosActivos
    vuelosCompletados //tiene el historial
aereopuertos= aereopuertos //lista que no cambia está estático
    aereopuertoEstadoActual
    estadoAntiguo (on demand) //tiene el historial

SSE --> conexion con el back (verifica si la info del front está actualizada)
    pedidosActivos
    pedidosCompletados
    vuelosActivos
    vuelosCompletados
    aereopuertoEstadoActual

cola colaDeEventos= new cola(solucionAnterior)
    2025-09-30-22-06 avion despega 202509302206LIMEEUU
    2025-09-30-22-10 aereopuerto brucelas +50 productos
    2025-09-31-06-15 avion llega 202509302215LIMEEUU

for(;;){// si ocurre algo le a avisar al front
    if(reloj=colaDeEventos.ultimoEvento().tiempo()){
        actualizar(colaDeEventos){
            pedidosActivos
            pedidosCompletados
            vuelosActivos
            vuelosCompletados
            aereopuertoEstadoActual
        }
    }
    if(pedidosActivos.vacio())break;
}
*/
/*
 * linea de tiempo entre ejecuciones
 * 200 pedidos
 * 10:00am                                  solucionAnterior1
 * 300 pedidos                              solucionAnterior1
 * 4:00pm se ejecuta el algoritmo           solucionAnterior1
 * aterrizo un avion
 * se recogio un pedido
 * 4:04pm se sigue ejecutando               solucionAnterior1
 * 4:04pm                                   solucionAnterior1 (estado10002693)
 * 4:04:01pm                                solucionAnterior2 (estado10002693)
 * 
 * 10:00 pm
 * 
 * 
 * 
 */