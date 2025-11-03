package pe.edu.pucp.morapack.airscheduler.api.sse;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import java.time.format.DateTimeFormatter;

import pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.RunContext;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.RunId;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.RunManager;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.StopReason;

/**
 * SSE de resultados por ventana para una corrida.
 * Emite objetos JSON con dos tipos (POR AHORA):
 *  - { type: "WINDOW", runId, windowIndex, windowStartUtc, windowEndUtc }
 *  - { type: "FINISHED", runId, reason }  // y se completa el stream
 */

@Path("/runs")
@RequestScoped
public class RunsSseController {

    @Inject RunManager runManager;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    
    @GET
    @Path("/{id}/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<Object> stream(@PathParam("id") String runIdStr) {
        final RunId runId = RunId.of(Objects.requireNonNull(runIdStr));

        return Multi.createFrom().emitter(emitter -> {

            //Apenas se conecta, va a emitir un RUN_STARTED
            RunContext ctx = runManager.requireContext(runId.value());
            //Internamente se hace un throw, por eso no se pone. Aunque es local
            /*if (ctx == null) {
                emitter.fail(new NotFoundException("Run no encontrado: " + runId.value()));
                return;
            }*/

            emitter.emit(new RunStartedEvt(runId.value(), ctx.simStartUtc().toString(),
                    ctx.wallAnchor().toString(), ctx.speed()));


            // Listener que reenvía los eventos del RunManager al SSE
            RunManager.RunListener listener = new RunManager.RunListener() {
                @Override
                public void onWindow(pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.WindowPacket p) {
                    emitter.emit(new WindowEvt(p.runId, p.windowId, p.windowStartUTC, p.windowEndUTC, p.vuelos, p.pedidos));
                }
                @Override
                public void onFinished(String id, StopReason reason) {
                    emitter.emit(new FinishedEvt(id, reason.name()));
                    // NO cerramos el SSE aquí: los vuelos deben continuar hasta llegar
                }
            };
            // Suscribimos al run
            runManager.registerListener(runId, listener);
            
            //Tick cada 1s real (esto es simNowUtc)
            ScheduledExecutorService tickExec = Executors.newSingleThreadScheduledExecutor();
            ScheduledFuture<?> tickFuture = tickExec.scheduleAtFixedRate(() -> {
                try {
                   Instant now = Instant.now();
                    Instant simNow = runManager.currentSimNow(runId.value());

                    // Imprime una sola línea, bien formateada
                    System.out.printf("Tick executed at %s | simNow=%s%n",
                            ISO.format(now),
                            ISO.format(simNow));

                    // Obtener ocupación actual de aeropuertos
                    var ocupacionAeropuertos = runManager.getCurrentAirportOccupancy(runId.value());

                    emitter.emit(new TickEvt(runId.value(), simNow.toString(), ocupacionAeropuertos));
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }, 0L, 1L, TimeUnit.SECONDS);


            // Limpieza al cortar la conexión del cliente
            emitter.onTermination(() -> {
                try { runManager.removeListener(runId, listener); } catch (Throwable ignored) {}
                try { tickFuture.cancel(true); } catch (Throwable ignored) {}
                try { tickExec.shutdownNow(); } catch (Throwable ignored) {}
            });
        });
    }

    // ---------- DTOs mínimos del SSE (solo metadatos por ahora) ----------

    public static final class RunStartedEvt {
        public final String type = "RUN_STARTED";
        public final String runId;
        public final String simStartUtc;
        public final String wallAnchorUtc;
        public final double speed;
        public RunStartedEvt(String runId, String simStartUtc, String wallAnchorUtc, double speed) {
            this.runId = runId; this.simStartUtc = simStartUtc; this.wallAnchorUtc = wallAnchorUtc; this.speed = speed;
        }
    }

    public static final class TickEvt{
        public final String type = "TICK";
        public final String runId;
        public final String simNowUtc;
        public final java.util.Map<String, java.util.Map<String, Object>> aeropuertos;
        public TickEvt(String runId, String simNowUtc, java.util.Map<String, java.util.Map<String, Object>> aeropuertos) {
            this.runId = runId; this.simNowUtc = simNowUtc; this.aeropuertos = aeropuertos != null ? aeropuertos : java.util.Collections.emptyMap();
        }
    }

    public static final class WindowEvt {
        public final String type = "WINDOW";
        public final String runId;
        public final int windowIndex;
        public final Instant windowStartUtc;
        public final Instant windowEndUtc;
        public final List<Object> vuelos;
        public final List<Object> pedidos;
        public final String windowIdISO;
        
        public WindowEvt(String runId, int idx, Instant s, Instant e, List<Object> vuelos, List<Object> pedidos) {
            this.runId = runId; 
            this.windowIndex = idx; 
            this.windowStartUtc = s; 
            this.windowEndUtc = e;
            this.vuelos = vuelos != null ? vuelos : List.of();
            this.pedidos = pedidos != null ? pedidos : List.of();
            this.windowIdISO = s.toString();
        }
    }

    public static final class FinishedEvt {
        public final String type = "FINISHED";
        public final String runId;
        public final String reason; // MANUAL | FIN_DE_RANGO | COLAPSE | ERROR (StopReason.java)
        public FinishedEvt(String runId, String reason) { this.runId = runId; this.reason = reason; }
    }
}
