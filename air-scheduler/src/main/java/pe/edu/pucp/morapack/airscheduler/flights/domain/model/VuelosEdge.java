package pe.edu.pucp.morapack.airscheduler.flights.domain.model;

public class VuelosEdge {
    private VuelosNode from;
    private VuelosNode to;
    private Vuelo vuelo;

    public VuelosEdge(VuelosNode from, VuelosNode to, Vuelo vuelo) {
        this.from = from;
        this.to = to;
        this.vuelo = vuelo;
    }

    public VuelosNode getFrom() {
        return from;
    }

    public VuelosNode getTo() {
        return to;
    }

    public Vuelo getVuelo() {
        return vuelo;
    }

    @Override
    public String toString() {
        return from + "->" + to + " [" + vuelo.getHoraOrigen() + "-" + vuelo.getHoraDestino() + ", cap="
                + vuelo.getCapacidad() + "]";
    }
}
