package pe.edu.pucp.morapack.airscheduler.flights.domain.model;

import java.time.Instant;
import java.util.Objects;

public final class VuelosNode {
    private final String icao;            // código aeropuerto o "OMEGA"
    private final Instant timeUtc;        // null sólo para Ω
    private final int capacidadAlmacen;   // informativo (para no-sedes)
    private final boolean superSource;    // true si es Ω

    public VuelosNode(String icao, Instant timeUtc, int capacidadAlmacen, boolean superSource) {
        this.icao = Objects.requireNonNull(icao, "icao");
        this.timeUtc = timeUtc;
        this.capacidadAlmacen = capacidadAlmacen;
        this.superSource = superSource;
    }

    public String getIcao() { return icao; }
    public Instant getTimeUtc() { return timeUtc; }
    public int getCapacidadAlmacen() { return capacidadAlmacen; }
    public boolean isSuperSource() { return superSource; }

    @Override public String toString() {
        return superSource ? "Ω" : (icao + "@" + timeUtc);
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VuelosNode v)) return false;
        return superSource == v.superSource
                && Objects.equals(icao, v.icao)
                && Objects.equals(timeUtc, v.timeUtc);
    }

    @Override public int hashCode() {
        return Objects.hash(icao, timeUtc, superSource);
    }
}
