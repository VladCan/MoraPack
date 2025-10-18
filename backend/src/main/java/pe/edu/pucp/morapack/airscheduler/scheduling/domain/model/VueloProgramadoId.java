package pe.edu.pucp.morapack.airscheduler.scheduling.domain.model;

import lombok.Value;

import java.time.Duration;
import java.time.Instant;

/** Identifica un vuelo programado específico (un arco FLIGHT del TEG). */
@Value
public class VueloProgramadoId {
    String origen;
    String destino;
    Instant salidaUtc;
    Instant llegadaUtc;

    public VueloProgramadoId(String origen, String destino, Instant salidaUtc, Instant llegadaUtc) {
        this.origen = origen;
        this.destino = destino;
        this.salidaUtc = salidaUtc;
        this.llegadaUtc = llegadaUtc;
    }

    public VueloProgramadoId(VueloProgramadoId otro) {
        this.origen = otro.getOrigen();
        this.destino = otro.getDestino();
        this.salidaUtc = otro.getSalidaUtc();
        this.llegadaUtc = otro.getLlegadaUtc();
    }

    //Mismo/similar que el de la clase Vuelo (recordar que Vuelo no tiene fecha y hora, solo hora)
    public double getCosto(){
        if (origen == null || destino == null || salidaUtc == null || llegadaUtc == null) return 0.0;

        // Calcular duración en segundos
        long duracionSegundos = Duration.between(llegadaUtc, salidaUtc).getSeconds();
        double duracionHoras = duracionSegundos / 3600.0;

        // Parámetros logísticos
        double costoBase = 50.0;                     // costo mínimo fijo por operación
        double penalizacionDuracion = duracionHoras * 20.0; // costo por hora de vuelo
        //double factorCapacidad = (capacidad > 0) ? (100.0 / capacidad) : 1.0; // penaliza baja capacidad

        // Fórmula final
        return costoBase + penalizacionDuracion;
    }

    public double getCostoCapacidad(int capacidad) {
        if (origen == null || destino == null || salidaUtc == null || llegadaUtc == null) return 0.0;

        // Calcular duración en segundos
        long duracionSegundos = Duration.between(llegadaUtc, salidaUtc).getSeconds();
        double duracionHoras = duracionSegundos / 3600.0;

        // Parámetros logísticos
        double costoBase = 50.0;                     // costo mínimo fijo por operación
        double penalizacionDuracion = duracionHoras * 20.0; // costo por hora de vuelo
        double factorCapacidad = (capacidad > 0) ? (100.0 / capacidad) : 1.0; // penaliza baja capacidad

        // Fórmula final
        return costoBase + penalizacionDuracion * factorCapacidad;
    }

}