package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class RelojSimulacion {
    private LocalDateTime tiempoActual;  // Tiempo en la simulación
    private boolean enMarcha;            // Indica si el reloj está corriendo
    private int minutosPorTick;          // Avance del reloj en cada tick (ej. 60 min = 1 hora)

    public RelojSimulacion() {
    }

    public RelojSimulacion(LocalDateTime tiempoActual, boolean enMarcha, int minutosPorTick) {
        this.tiempoActual = tiempoActual;
        this.enMarcha = enMarcha;
        this.minutosPorTick = minutosPorTick;
    }

    public void iniciar() {
        this.enMarcha = true;
    }

    // Pausar el reloj
    public void pausar() {
        this.enMarcha = false;
    }

    public void reiniciar(LocalDateTime nuevoInicio) {
        this.tiempoActual = nuevoInicio;
        this.enMarcha = false;
    }

    public void tick() {
        if (enMarcha) {
            tiempoActual = tiempoActual.plusMinutes(minutosPorTick);
        }
    }

    public LocalDateTime getTiempoActual() {
        return tiempoActual;
    }

    public String getTiempoFormateado() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
        return tiempoActual.format(formatter);
    }

    public void setMinutosPorTick(int minutos) {
        this.minutosPorTick = minutos;
    }

    public int getMinutosPorTick() {
        return minutosPorTick;
    }

    public boolean isEnMarcha() {
        return enMarcha;
    }

    @Override
    public String toString() {
        return "RelojSimulacion{" +
                "tiempoActual=" + getTiempoFormateado() +
                ", enMarcha=" + enMarcha +
                ", minutosPorTick=" + minutosPorTick +
                '}';
    }

}
