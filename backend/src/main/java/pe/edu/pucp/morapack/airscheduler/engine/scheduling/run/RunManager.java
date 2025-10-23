package pe.edu.pucp.morapack.airscheduler.engine.scheduling.run;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class RunManager {
    private final ExecutorService executor = Executors.newCachedThreadPool((r -> {
        Thread t = new Thread(r, "run-" + UUID.randomUUID());
        t.setDaemon(true);
        return t;
    }));

    private final Map<String, RunContext> contexts = new ConcurrentHashMap<>();
    private final Map<String, RunState> states = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> paused = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelled = new ConcurrentHashMap<>();

    public void addContext(String idRun, RunContext context) {
        contexts.put(idRun, context);
    }

    /*Devolvemos el ahora simulado del run*/
    /*public Instant currentSimNow(RunId runId){
        return currentSimNow(runId);
    }*/

    /*Overload, ya que vamos a usar el string y no el RunId*/
    public Instant currentSimNow(String runId){
        RunContext ctx = requireContext(runId);
        //Acá calculamos el tiempo real transcurrido desde que arrancó el run
        long deltaMs = Duration.between(ctx.wallAnchor(), Instant.now()).toMillis();
        //Acá calculamos la velocidad en segundos simulados por segundo real
        long simDeltaMs = (long) Math.floor(deltaMs * ctx.speed());

        //Retornamos el ahora simulado
        return ctx.simStartUtc().plusMillis(simDeltaMs);
    }

    /** Obtiene el contexto o lanza un error claro si no existe. */
    public RunContext requireContext(String runId) {
        RunContext ctx = contexts.get(runId);
        if (ctx == null) {
            throw new IllegalStateException("Run no encontrado: " + runId);
        }
        return ctx;
    }

    // Suscriptores por run (para el SSE)
    private final Map<String, java.util.concurrent.CopyOnWriteArraySet<RunListener>> listeners
            = new java.util.concurrent.ConcurrentHashMap<>();

    // ---- Listener (para SSE). Podemos dejar NOOP por ahora.
    public interface RunListener {
        void onWindow(WindowPacket packet);
        void onFinished(String runId, StopReason reason);
    }
    public static final RunListener NOOP_LISTENER = new RunListener() {
        @Override public void onWindow(WindowPacket packet) {}
        @Override public void onFinished(String runId, StopReason reason) {}
    };


    // ---- Snapshot simple para status()
    public static final class Status {
        public final String runId;
        public final RunState state;
        public final int windowIndex;
        public Status(String runId, RunState state, int windowIndex) {
            this.runId = runId; this.state = state; this.windowIndex = windowIndex;
        }
    }

    public void registerListener(RunId runId, RunListener l) {
        listeners.computeIfAbsent(runId.value(), k -> new java.util.concurrent.CopyOnWriteArraySet<>()).add(l);
    }

    public void removeListener(RunId runId, RunListener l) {
        var set = listeners.get(runId.value());
        if (set != null) set.remove(l);
    }

    // ---- Overload conveniente (sin listener)
    public void start(RunId runId, RunConfig config) {
        start(runId, config, NOOP_LISTENER);
    }


    public void start (RunId runId, RunConfig config, RunListener listener) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(listener, "listener");

        final String id = runId.value();
        listeners.computeIfAbsent(id, k -> new java.util.concurrent.CopyOnWriteArraySet<>());

        //Por si se pasa un listener como parámetro
        if (listener != null && listener != NOOP_LISTENER) {
            registerListener(runId, listener);
        }

        states.put(id, RunState.RUNNING);
        paused.put(id, new AtomicBoolean(false));
        cancelled.put(id, new AtomicBoolean(false));

        executor.submit(() -> {
            try{
                Instant wStart = config.fechaInicio();
                Instant wEnd = wStart.plus(config.horasVentana());
                int idx = 0;

                System.out.println("En esta iteración, wStart es: " + wStart + ", wEnd es: " + wEnd);
                System.out.println("Voy a entrar al bucle, mi id es:" + id);

                while (!cancelled.get(id).get() && (config.fechaFin() == null || !wStart.isAfter(config.fechaFin()))) {
                    /// Revisar esto:
                    // Pausa cooperativa entre ventanas
                    while (paused.get(id).get() && !cancelled.get(id).get()) {
                        sleepQuietly(Duration.ofMillis(80));
                    }
                    if (cancelled.get(id).get()) break;

                    //Acá debería de ir toda la logica de la planificación

                    // Emitimos paquete de ventana
                    //listener.onWindow(new WindowPacket(id, idx, wStart, wEnd, vuelos));
                    broadcastWindow(new WindowPacket(id, idx, wStart, wEnd /* ... */));

                    //Acá vamos a que el reloj simulado cruce el fin de ventana
                    while (true){
                        //En caso de existir pausa o cancelación (por ahora, esto no ocurrirá)
                        if (cancelled.get(id).get()) break;

                        while (paused.get(id).get() && !cancelled.get(id).get()) {
                            sleepQuietly(Duration.ofMillis(100)); // dormimos cortito mientras esté pausado
                        }
                        if (cancelled.get(id).get()) break;

                        Instant simNow = currentSimNow(id);

                        //Verificamos si ya cruzó el fin de ventana
                        if (!simNow.isBefore(wEnd)){
                            break;
                        }

                        //Lo que viene acá abajo es para evitar busy-wait, osea
                        //que el CPU no este ejecutando a cada rato lo de arriba

                        long remainingSimMs = Duration.between(simNow, wEnd).toMillis();
                        if (remainingSimMs <= 0) break;

                        //Acá calculamos lo que falta simular a "cuanto dormir"
                        RunContext ctx = requireContext(id);
                        double speed = ctx.speed();
                        long remainingRealMs = (long) Math.ceil(remainingSimMs / speed);

                        //Dormimos por tramos cortos para poder reaccionar a pausa o cancel
                        long napMs = Math.min(Math.max(remainingRealMs, 50L), 500L);
                        sleepQuietly(Duration.ofMillis(napMs));

                    }

                    /*System.out.println("Vamos a dormir 6 segundos, mi id es:" + id);
                    sleepQuietly(Duration.ofSeconds(6));
                    System.out.println("Ya desperté 6, mi id es:" + id);*/


                    // Siguiente ventana
                    idx++;
                    wStart = wEnd;
                    wEnd   = wEnd.plus(config.horasVentana());
                }

                System.out.println("Salí del bucle, mi id es:" + id);

                // Fin normal
                states.put(id, RunState.COMPLETED);
                broadcastFinished(id, StopReason.FIN_DE_RANGO);

            }
            catch (Throwable e){
                states.put(id, RunState.FAILED);
                broadcastFinished(id, StopReason.ERROR);
            }

        });
    }

    private void broadcastWindow(WindowPacket pkt) {
        var set = listeners.getOrDefault(pkt.runId, new java.util.concurrent.CopyOnWriteArraySet<>());
        set.forEach(l -> safe(() -> l.onWindow(pkt)));
    }

    private void broadcastFinished(String runId, StopReason reason) {
        var set = listeners.getOrDefault(runId, new java.util.concurrent.CopyOnWriteArraySet<>());
        set.forEach(l -> safe(() -> l.onFinished(runId, reason)));
    }

    // para no romper el stream si un listener falla
    private static void safe(Runnable r) { try { r.run(); } catch (Throwable ignored) {} }

    public void pause(RunId id){ paused.getOrDefault(id.value(), new AtomicBoolean()).set(true); }
    public void resume(RunId id){ paused.getOrDefault(id.value(), new AtomicBoolean()).set(false); }
    public void cancel(RunId id){ cancelled.getOrDefault(id.value(), new AtomicBoolean()).set(true); }
    public Status status(RunId id) {
        RunState st = states.getOrDefault(id.value(), RunState.PENDING);
        // Si quieres, guarda el índice real; por ahora 0 (suficiente para compilar).
        return new Status(id.value(), st, 0);
    }

    private static void sleepQuietly(Duration d) {
        try { Thread.sleep(d.toMillis()); } catch (InterruptedException ignored) {}
    }

}
