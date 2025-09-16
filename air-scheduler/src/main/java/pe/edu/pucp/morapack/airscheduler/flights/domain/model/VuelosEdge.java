package pe.edu.pucp.morapack.airscheduler.flights.domain.model;

public record VuelosEdge(
        VuelosNode salida,
        VuelosNode destino,
        Type tipo,
        int capacidad,
        Vuelo vuelo
) {
    public enum Type { FLIGHT, WAIT, SUPPLY }

    public boolean isFlight()  { return tipo == Type.FLIGHT; }
    public boolean isWait()    { return tipo == Type.WAIT; }
    public boolean isSupply()  { return tipo == Type.SUPPLY; }

    @Override
    public String toString() {
        return switch (tipo) {
            case FLIGHT -> salida + " => " + destino + " [FLIGHT cap=" + capacidad + "]";
            case WAIT   -> salida + " -> " + destino + " [WAIT cap=" + capacidad + "]";
            case SUPPLY -> "Ω -> " + destino + " [SUPPLY cap=" + capacidad + "]";
        };
    }
}