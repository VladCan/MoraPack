package pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

public class VueloCancelado {

    private final String origen;
    private final String destino;
    private final LocalDate fecha;
    private final LocalTime hora;
    private final Instant instante;

    public VueloCancelado(String origen,
                          String destino,
                          LocalDate fecha,
                          LocalTime hora,
                          Instant instante) {
        this.origen = origen;
        this.destino = destino;
        this.fecha = fecha;
        this.hora = hora;
        this.instante = instante;
    }

    public String getOrigen() {
        return origen;
    }

    public String getDestino() {
        return destino;
    }

    public LocalDate getFecha() {
        return fecha;
    }

    public LocalTime getHora() {
        return hora;
    }

    public Instant getInstante() {
        return instante;
    }

    @Override
    public String toString() {
        return "VueloCancelado{" +
                "origen='" + origen + '\'' +
                ", destino='" + destino + '\'' +
                ", fecha=" + fecha +
                ", hora=" + hora +
                ", instante=" + instante +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VueloCancelado)) return false;
        VueloCancelado that = (VueloCancelado) o;
        return Objects.equals(origen, that.origen) &&
                Objects.equals(destino, that.destino) &&
                Objects.equals(fecha, that.fecha) &&
                Objects.equals(hora, that.hora) &&
                Objects.equals(instante, that.instante);
    }

    @Override
    public int hashCode() {
        return Objects.hash(origen, destino, fecha, hora, instante);
    }
}
