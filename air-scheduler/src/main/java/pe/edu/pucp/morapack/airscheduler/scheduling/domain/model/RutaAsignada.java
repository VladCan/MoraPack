package pe.edu.pucp.morapack.airscheduler.scheduling.domain.model;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.time.Instant;
import java.util.List;

/** Una ruta (secuencia de tramos) por la que viaja una cantidad del pedido. */
@Value
@Builder
public class RutaAsignada {
    /** Cantidad del pedido que viaja por ESTA ruta. */
    int cantidad;

    /** Secuencia ordenada de tramos (origen..destino). */
    @Singular("tramo")
    List<TramoAsignado> tramos;

    public RutaAsignada(int cantidad, List<TramoAsignado> tramos) {
        this.cantidad = cantidad;
        this.tramos = tramos;
    }

    /** Instante de primera llegada de la ruta (arribo de su primer tramo en destino final). */
    public Instant primeraLlegada() {
        return tramos.isEmpty() ? null : tramos.get(0).getLlegadaUtc();
    }

    /** Instante de última llegada (último tramo de la ruta). */
    public Instant ultimaLlegada() {
        return tramos.isEmpty() ? null : tramos.get(tramos.size()-1).getLlegadaUtc();
    }
}
