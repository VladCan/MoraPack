package pe.edu.pucp.morapack.airscheduler.flights.domain.model;

import java.time.Instant;
import java.util.Objects;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public final class VuelosNode {
    @EqualsAndHashCode.Include
    private String codigoAP; // código aeropuerto o "OMEGA"

    @EqualsAndHashCode.Include
    private Instant tiempoUTC; // null sólo para Ω

    private int capacidadAP; // informativo (para no-sedes)

    @EqualsAndHashCode.Include
    private boolean esSede; // true si es Ω

    public VuelosNode(String codigoAP, Instant tiempoUTC, int capacidadAP, boolean esSede) {
        this.codigoAP = Objects.requireNonNull(codigoAP, "codigoAP");
        this.tiempoUTC = Objects.requireNonNull(tiempoUTC, "tiempoUTC");
        this.capacidadAP = capacidadAP;
        this.esSede = esSede;
    }

    @Override
    public String toString() {
        return esSede ? "Ω" : (codigoAP + "@" + tiempoUTC);
    }
}