package pe.edu.pucp.morapack.airscheduler.engine.scheduling.run;

import java.time.Instant;

public final class WindowPacket {
    public final String runId;
    public final int windowId;
    public final Instant windowStartUTC;
    public final Instant windowEndUTC;

    //public final List<VueloSolDTO> vuelos;

    public WindowPacket(String runId, int idx, Instant start, Instant end){
        this.runId = runId;
        this.windowId = idx;
        this.windowStartUTC = start;
        this.windowEndUTC = end;
    }

    /*public WindowPacket(String runId, int idx, Instant start, Instant end, List<VueloSolDTO> vuelos) {
        this.runId = runId;
        this.windowId = idx;
        this.windowStartUTC = start;
        this.windowEndUTC = end;
        this.vuelos = vuelos;
    }*/


}
