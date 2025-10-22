package pe.edu.pucp.morapack.airscheduler.api.sse;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
                    emitter.emit(new WindowEvt(p.runId, p.windowId, p.windowStartUTC, p.windowEndUTC));
                }
                @Override
                public void onFinished(String id, StopReason reason) {
                    emitter.emit(new FinishedEvt(id, reason.name()));
                    emitter.complete(); // cerramos el SSE
                }
            };
            // Suscribimos al run
            runManager.registerListener(runId, listener);

            //Tick cada 1s real (esto es simNowUtc)
            ScheduledExecutorService tickExec = Executors.newSingleThreadScheduledExecutor();
            ScheduledFuture<?> tickFuture = tickExec.scheduleAtFixedRate(() -> {
                try {
                    System.out.println("Tick executed at " + Instant.now());
                    Instant simNow = runManager.currentSimNow(runId.value());
                    emitter.emit(new TickEvt(runId.value(), simNow.toString()));
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
        public TickEvt(String runId, String simNowUtc) {
            this.runId = runId; this.simNowUtc = simNowUtc;
        }
    }

    public static final class WindowEvt {
        public final String type = "WINDOW";
        public final String runId;
        public final int windowIndex;
        public final Instant windowStartUtc;
        public final Instant windowEndUtc;
        public WindowEvt(String runId, int idx, Instant s, Instant e) {
            this.runId = runId; this.windowIndex = idx; this.windowStartUtc = s; this.windowEndUtc = e;
        }
    }

    public static final class FinishedEvt {
        public final String type = "FINISHED";
        public final String runId;
        public final String reason; // NORMAL | COLAPSO | MANUAL | ERROR
        public FinishedEvt(String runId, String reason) { this.runId = runId; this.reason = reason; }
    }
}
