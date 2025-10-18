package pe.edu.pucp.morapack.airscheduler.engine.scheduling.domain.model;

import java.time.Instant;

import lombok.Getter;
import lombok.Setter;

/** Estado interno de un pedido activo. */
@Getter
@Setter
public final class EstadoPedido {
    final int id;
    final String destino;
    final Instant creadoUtc;
    final int demandaTotal;
    private int entregado;           // suma de llegadas al destino
    private int reservadoEnVuelo;    // suma de tramos comprometidos aún no arribados

    public EstadoPedido(int id, String destino, Instant creadoUtc, int demanda) {
        this.id = id; this.destino = destino; this.creadoUtc = creadoUtc; this.demandaTotal = demanda;
    }
    public int remanenteParaPlan() {
        int r = demandaTotal - entregado - reservadoEnVuelo;
        return Math.max(0, r);
    }
    public boolean completado() { return entregado >= demandaTotal; }
}