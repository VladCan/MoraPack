package pe.edu.pucp.morapack.airscheduler.engine.scheduling.run;

import java.time.Instant;
import java.util.Objects;

/** Esta clase es la que representa el estado aislado de un run en específico
 *  Acá no se guarda catálogos ni solución aún - solo metadatos del ciclo de vida
 * **/

public class RunContext {

    private final RunId runId;
    private final RunConfig runConfig;

    // Reloj y progreso (por ahora no)
    private volatile Instant relojActual;
    private volatile long ventanaIndex;

    //Con esto modelamos el reloj
    //simStartUtc es la fecha de inicio, wallAnchor es el now y speed es el factor de aceleración
    private final Instant simStartUtc;
    private final Instant wallAnchor;
    private final double speed;

    //Ciclo de vida
    private volatile RunState state = RunState.PENDING;
    private volatile StopReason stopReason; // null si aun no para

    private volatile Instant startedAt;
    private volatile Instant lastPacketAt;
    private volatile Instant finishedAt;

    public RunContext(RunId runId, RunConfig config) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.runConfig = Objects.requireNonNull(config, "config");
        this.relojActual = config.fechaInicio();
        this.ventanaIndex = 0L;
        this.simStartUtc = config.fechaInicio();
        this.wallAnchor = Instant.now();
        this.speed = 144;
    }

    /** Getters **/
    public RunId runId() { return runId; }
    public RunConfig runConfig() { return runConfig; }
    public Instant relojActual() { return relojActual; }
    public long ventanaIndex() { return ventanaIndex; }
    public Instant simStartUtc() { return simStartUtc; }
    public Instant wallAnchor() { return wallAnchor; }
    public double speed() { return speed; }
    public RunState state() { return state; }
    public StopReason stopReason() { return stopReason; }
    public Instant startedAt() { return startedAt; }
    public Instant lastPacketAt() { return lastPacketAt; }
    public Instant finishedAt() { return finishedAt; }

    /** Transiciones de ciclo de vida **/

    public void markStarted() {
        this.state = RunState.RUNNING;
        this.startedAt = Instant.now();
    }

    public void markPacketEmitted() {
        this.lastPacketAt = Instant.now();
    }

    public void markCompleted(StopReason reason) {
        this.state = RunState.COMPLETED;
        this.stopReason = reason;
        this.finishedAt = Instant.now();
    }

    public void markStopped() {
        this.state = RunState.STOPPED;
        this.stopReason = StopReason.MANUAL;
        this.finishedAt = Instant.now();
    }

    public void markFailed() {
        this.state = RunState.FAILED;
        this.stopReason = StopReason.ERROR;
        this.finishedAt = Instant.now();
    }

    /** Avanza el reloj al fin de la ventana actual y aumenta el índice. */
    public void advanceWindow(Instant nextWindowStart) {
        this.relojActual = nextWindowStart;
        this.ventanaIndex++;
    }

}
