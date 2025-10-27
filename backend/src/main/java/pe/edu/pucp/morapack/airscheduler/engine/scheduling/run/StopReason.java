package pe.edu.pucp.morapack.airscheduler.engine.scheduling.run;

/** Esta clase representa cómo terminó un run **/
public enum StopReason {
    MANUAL,
    FIN_DE_RANGO,
    COLAPSE,
    ERROR
}
