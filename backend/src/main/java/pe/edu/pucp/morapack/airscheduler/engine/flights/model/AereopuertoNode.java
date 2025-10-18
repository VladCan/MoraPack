package pe.edu.pucp.morapack.airscheduler.engine.flights.model;

import java.time.Instant;
import java.util.Objects;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public final class AereopuertoNode {

    @EqualsAndHashCode.Include
    private String codigoAP;

    @EqualsAndHashCode.Include
    private Instant tiempoUTC; // válido siempre, excepto si es Ω

    private int capacidadAP; // informativo

    @EqualsAndHashCode.Include
    private boolean esSede; // true = Ω

    public AereopuertoNode(String codigoAP, Instant tiempoUTC, int capacidadAP, boolean esSede) {
        this.codigoAP = Objects.requireNonNull(codigoAP, "codigoAP");
        this.esSede = esSede;
        if (!esSede) {
            this.tiempoUTC = Objects.requireNonNull(tiempoUTC, "tiempoUTC");
        } else {
            this.tiempoUTC = null; // sólo Ω
        }
        this.capacidadAP = capacidadAP;
    }

    @Override
    public String toString() {
        return esSede ? "Ω" : (codigoAP + "@" + tiempoUTC);
    }
}
