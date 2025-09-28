package pe.edu.pucp.morapack.airscheduler.controllers;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.util.List;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.api.dto.FlightLiveDTO;
import pe.edu.pucp.morapack.airscheduler.flights.service.VuelosLiveService;

@Path("/vuelos")
@RequestScoped
public class VuelosSseController {

    @Inject VuelosLiveService service;

    /** SSE: emite CADA SEGUNDO un evento con un JSON Array de vuelos en el aire. */
    @GET
    @Path("/live")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<List<FlightLiveDTO>> live() {
        return service.streamLiveFlights();
    }
}
