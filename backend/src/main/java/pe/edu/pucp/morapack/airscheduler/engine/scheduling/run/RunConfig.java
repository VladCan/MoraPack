package pe.edu.pucp.morapack.airscheduler.engine.scheduling.run;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/** Esta clase es para representar los parámetros efectivos del run como tal**/
public class RunConfig {
    public enum Scenario{
        OPERACION,
        SIM_SEMANAL,
        COLAPSO
    }

    private final Scenario scenario;
    private final Instant fechaInicio;
    private final Instant fechaFin;
    private final Duration horasVentana;
    private final Duration horizon;
    /// Nota: podemos considerar eliminar sedes y que sean fijas en el algoritmo a ejecutarse.
    private final Set<String> sedes;
    private final double speed;

    // Parámetros opcionales
    private final long seedRandom;
    private final Duration maxTiempoALNS;

    public RunConfig(
            Scenario scenario,
            Instant fechaInicio,
            Instant fechaFin,
            Duration horasVentana,
            Duration horizon,
            Set<String> sedes,
            long seedRandom,
            Duration maxTiempoALNS,
            double speed
    ) {
        this.scenario = scenario;
        this.fechaInicio = fechaInicio;
        this.fechaFin = fechaFin;
        this.horasVentana = horasVentana;
        this.horizon = horizon;
        this.sedes = sedes;
        this.seedRandom = seedRandom;
        this.maxTiempoALNS = maxTiempoALNS;
        this.speed = speed;
    }

    /** Getters **/
    public Scenario scenario() { return scenario; }
    public Instant fechaInicio() { return fechaInicio; }
    public Instant fechaFin() { return fechaFin; }
    public Duration horasVentana() { return horasVentana; }
    public Duration horizon() { return horizon; }
    public Set<String> sedes() { return sedes; }
    public long seedRandom() { return seedRandom; }
    public Duration maxTiempoALNS() { return maxTiempoALNS; }
    public double speed() { return speed; }

    /** Acá implementamos 3 factories, donde cada uno representa los parámetros de cada operación **/

    /// Estamos usando horas, deberían ser minutos.
    public static RunConfig operacion(Set<String> sedes) {
        return operacion(sedes, Duration.ofHours(1));
    }
    
    public static RunConfig operacion(Set<String> sedes, Duration windowSize) {
        return new RunConfig(
                Scenario.OPERACION,
                Instant.now(),
                null,
                windowSize,
                Duration.ofHours(24),
                sedes,
                System.nanoTime(),
                Duration.ofSeconds(60),
                1
        );
    }

    public static RunConfig simSemanal(Instant inicio, Instant fin, Set<String> sedes){
        return simSemanal(inicio, fin, sedes, Duration.ofHours(6));
    }
    
    public static RunConfig simSemanal(Instant inicio, Instant fin, Set<String> sedes, Duration windowSize){
        return new RunConfig(
                Scenario.SIM_SEMANAL,
                inicio,
                fin,
                windowSize,
                Duration.ofHours(48),
                sedes,
                System.nanoTime(),
                Duration.ofSeconds(60),
                432
        );
    }

    public static RunConfig colapso(Instant inicio, Set<String> sedes) {
        return colapso(inicio, sedes, Duration.ofHours(6));
    }
    
    public static RunConfig colapso(Instant inicio, Set<String> sedes, Duration windowSize) {
        return new RunConfig(
                Scenario.COLAPSO,
                inicio,
                null,
                windowSize,
                Duration.ofHours(6),
                sedes,
                System.nanoTime(),
                Duration.ofSeconds(60),
                432
        );
    }

    @Override
    public String toString() {
        return "RunConfig[" + scenario + ", inicio=" + fechaInicio + ", fin=" + fechaFin + "]";
    }

}
