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

import io.quarkus.runtime.annotations.RegisterForReflection; 

import pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.RunContext;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.RunId;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.RunManager;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.StopReason;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.WindowPacket;

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

            // 1. BLINDAJE INICIAL: Verificamos si el Run existe antes de empezar
            try {
                RunContext ctx = runManager.requireContext(runId.value());
                // Si existe, emitimos el evento de inicio
                emitter.emit(new RunStartedEvt(runId.value(), ctx.simStartUtc().toString(),
                        ctx.wallAnchor().toString(), ctx.speed()));
            } catch (IllegalStateException | IllegalArgumentException e) {
                // Si el run no existe (ya terminó o id incorrecto), cerramos el stream suavemente
                // System.out.println("SSE: El run " + runIdStr + " no existe o ya terminó. Cerrando conexión.");
                emitter.complete();
                return; // Salimos para no registrar listeners fantasmas
            }


            // Listener que reenvía los eventos del RunManager al SSE
            RunManager.RunListener listener = new RunManager.RunListener() {
                @Override
                public void onWindow(WindowPacket p) {
                    emitter.emit(new WindowEvt(p.runId, p.windowId, p.windowStartUTC, p.windowEndUTC, p.vuelos, p.pedidos));
                }

                @Override
                public void onFinished(String id, StopReason reason) {
                    emitter.emit(new FinishedEvt(id, reason.name()));
                    // NO cerramos el SSE aquí: los vuelos deben continuar hasta llegar
                }

                @Override
                public void onLoading(String id, String message) {
                    emitter.emit(new LoadingEvt(id, message, 0.0));
                }
            };
            
            // Suscribimos al run
            // Nota: Aquí también podría fallar si se borra en el milisegundo exacto entre el try de arriba y esto,
            // así que un try-catch extra no hace daño.
            try {
                runManager.registerListener(runId, listener);
            } catch (Exception e) {
                emitter.complete();
                return;
            }
            
            // Tick cada 1s real
            ScheduledExecutorService tickExec = Executors.newSingleThreadScheduledExecutor();
            ScheduledFuture<?> tickFuture = tickExec.scheduleAtFixedRate(() -> {
                try {
                    // 2. BLINDAJE DEL TICK: Si el run se borra, estos métodos lanzan excepción
                    Instant simNow = runManager.currentSimNow(runId.value());
                    var ocupacionAeropuertos = runManager.getCurrentAirportOccupancy(runId.value());

                    emitter.emit(new TickEvt(runId.value(), simNow.toString(), ocupacionAeropuertos));
                }
                catch (IllegalStateException ie) {
                    // El run fue eliminado de memoria (normal al finalizar).
                    // Capturamos la excepción para evitar el stack trace gigante en logs.
                    // Lanzamos RuntimeException para detener el scheduler silenciosamente o no hacemos nada.
                    throw new RuntimeException("Run finished, stopping ticker");
                }
                catch (Exception e) {
                    // Otros errores reales sí los imprimimos
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

    // ---------- DTOs del SSE ----------

    @RegisterForReflection
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

    @RegisterForReflection
    public static final class LoadingEvt {
        public final String type = "LOADING";
        public final String runId;
        public final String message;
        public final Double progress; 
        
        public LoadingEvt(String runId, String message, Double progress) {
            this.runId = runId;
            this.message = message;
            this.progress = progress;
        }
    }

    @RegisterForReflection
    public static final class TickEvt{
        public final String type = "TICK";
        public final String runId;
        public final String simNowUtc;
        public final java.util.Map<String, java.util.Map<String, Object>> aeropuertos;
        public TickEvt(String runId, String simNowUtc, java.util.Map<String, java.util.Map<String, Object>> aeropuertos) {
            this.runId = runId; this.simNowUtc = simNowUtc; this.aeropuertos = aeropuertos != null ? aeropuertos : java.util.Collections.emptyMap();
        }
    }

    @RegisterForReflection
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

    @RegisterForReflection
    public static final class FinishedEvt {
        public final String type = "FINISHED";
        public final String runId;
        public final String reason; // MANUAL | FIN_DE_RANGO | COLAPSO | ERROR
        public FinishedEvt(String runId, String reason) { this.runId = runId; this.reason = reason; }
    }
}