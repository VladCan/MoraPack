package pe.edu.pucp.morapack.airscheduler.flights.domain.model;

public final class VuelosEdge {
    public enum Type { FLIGHT, WAIT, SUPPLY }

    private final VuelosNode from;
    private final VuelosNode to;
    private final Type type;
    private final int capacity;   // cap del vuelo, del almacén (WAIT), o del supply (SUPPLY)
    private final Vuelo vuelo;    // sólo para FLIGHT; null en WAIT/SUPPLY

    public VuelosEdge(VuelosNode from, VuelosNode to, Type type, int capacity, Vuelo vuelo) {
        this.from = from; this.to = to; this.type = type; this.capacity = capacity; this.vuelo = vuelo;
    }

    public VuelosNode getFrom() { return from; }
    public VuelosNode getTo() { return to; }
    public Type getType() { return type; }
    public int getCapacity() { return capacity; }
    public Vuelo getVuelo() { return vuelo; }

    @Override public String toString() {
        switch (type) {
            case FLIGHT -> {
                return from + " => " + to + " [FLIGHT cap=" + capacity + "]";
            }
            case WAIT -> {
                return from + " -> " + to + " [WAIT cap=" + capacity + "]";
            }
            default -> {
                return "Ω -> " + to + " [SUPPLY cap=" + capacity + "]";
            }
        }
    }
}
