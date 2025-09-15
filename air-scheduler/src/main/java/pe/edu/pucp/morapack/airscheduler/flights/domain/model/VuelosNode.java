package pe.edu.pucp.morapack.airscheduler.flights.domain.model;

public class VuelosNode {
    private String icao;
    private int capacidad;

    public VuelosNode(String icao, int capacidad) {
        this.icao = icao;
        this.capacidad = capacidad;
    }

    public String getIcao() {
        return icao;
    }

    public int getCapacidad() {
        return capacidad;
    }

    @Override
    public String toString() {
        return "VuelosNode{" +
                "icao='" + icao + '\'' +
                ", capacidad=" + capacidad +
                '}';
    }
}
