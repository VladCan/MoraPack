package pe.edu.pucp.morapack.airscheduler.engine.scheduling.run;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class WindowPacket {
    public final String runId;
    public final int windowId;
    public final Instant windowStartUTC;
    public final Instant windowEndUTC;
    public final List<Object> vuelos; // Vuelos de la ventana
    public final List<Object> pedidos; // Pedidos de la ventana
    public final String windowIdISO; // ID único de ventana basado en wStart ISO

    public WindowPacket(String runId, int idx, Instant start, Instant end){
        this.runId = runId;
        this.windowId = idx;
        this.windowStartUTC = start;
        this.windowEndUTC = end;
        this.vuelos = List.of();
        this.pedidos = List.of();
        this.windowIdISO = start.toString();
    }

    public WindowPacket(String runId, int idx, Instant start, Instant end, 
                       List<Object> vuelos, List<Object> pedidos) {
        this.runId = runId;
        this.windowId = idx;
        this.windowStartUTC = start;
        this.windowEndUTC = end;
        this.vuelos = vuelos != null ? vuelos : List.of();
        this.pedidos = pedidos != null ? pedidos : List.of();
       
        this.windowIdISO = start.toString();
    }
}
