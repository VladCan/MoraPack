package pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model;

public record VuelosEdge(
        AereopuertoNode salida,
        AereopuertoNode destino,
        Type tipo,
        int capacidad,
        Vuelo vuelo,
        String idInstancia
) {
    public enum Type { FLIGHT, WAIT, SUPPLY }

    public VuelosEdge(AereopuertoNode salida, AereopuertoNode destino, Type tipo, int capacidad, Vuelo vuelo) {
        this(salida, destino, tipo, capacidad, vuelo, null);
    }

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