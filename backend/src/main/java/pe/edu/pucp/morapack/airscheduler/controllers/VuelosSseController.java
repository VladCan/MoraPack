// src/main/java/.../controllers/VuelosSseController.java
package pe.edu.pucp.morapack.airscheduler.controllers;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.api.dto.FlightLiveDTO;
import pe.edu.pucp.morapack.airscheduler.flights.service.VuelosLiveService;

@Path("/vuelos")
@RequestScoped
public class VuelosSseController {

    @Inject VuelosLiveService service;

    /**
     * SSE: cada ~1s emite un array JSON con los vuelos EN EL AIRE.
     * Query opcional: ?time=HH:mm  (usa esa hora UTC simulada por conexión).
     */
    // src/main/java/.../controllers/VuelosSseController.java
@GET
@Path("/live")
@Produces(MediaType.SERVER_SENT_EVENTS)
@RestStreamElementType(MediaType.APPLICATION_JSON)
public Multi<List<FlightLiveDTO>> live(
        @QueryParam("time") String time,
        @QueryParam("limit") @DefaultValue("50") int limit // 👈 NUEVO
) {
    VuelosLiveService.NowSupplier nowSupplier;
    try {
        if (time != null && !time.isBlank()) {
            var t = LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm"));
            final int fixed = t.toSecondOfDay();
            nowSupplier = () -> fixed;
        } else {
            nowSupplier = service::systemNowUtcSeconds;
        }
    } catch (Exception e) {
        nowSupplier = service::systemNowUtcSeconds;
    }

    // limit <= 0 => sin límite. Tope prudente para evitar DoS involuntario.
    final int effectiveLimit = (limit <= 0) ? Integer.MAX_VALUE : Math.min(limit, 500);

    return service.streamLiveFlights(nowSupplier, effectiveLimit);
}

}
