package pe.edu.pucp.morapack.airscheduler.engine.scheduling.service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Reloj simple para avanzar el tiempo en la simulación.
 * Usa Lombok para exponer lecturas básicas y mantener el código liviano.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class RelojSimulacion {

    private LocalDateTime tiempoActual;  // Tiempo en la simulación
    private boolean enMarcha;            // Indica si el reloj está corriendo
    @Setter
    private int minutosPorTick;          // Avance del reloj en cada tick (ej. 60 min = 1 hora)

    public void iniciar() {
        this.enMarcha = true;
    }

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

    public String getTiempoFormateado() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
        return tiempoActual.format(formatter);
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

