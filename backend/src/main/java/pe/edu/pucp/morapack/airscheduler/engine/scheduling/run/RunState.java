package pe.edu.pucp.morapack.airscheduler.engine.scheduling.run;

/** Esta clase representa el estado actual de un run**/
public enum RunState {
    PENDING,
    LOADING,
    RUNNING,
    STOPPED,
    COMPLETED,
    FAILED
}
