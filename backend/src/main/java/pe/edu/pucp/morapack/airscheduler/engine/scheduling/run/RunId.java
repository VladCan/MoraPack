package pe.edu.pucp.morapack.airscheduler.engine.scheduling.run;

import java.util.Objects;
import java.util.UUID;

/** Representa el ID de una instancia de ejecución**/
public class RunId {
    private final String runId;

    private RunId(String runId) {
        this.runId = runId;
    }

    /** Acá se crea un RunId aleatorio legible (por ejemplo: "run-3f9a2d5a") */
    public static RunId create() {
        return new RunId("run-"+ UUID.randomUUID().toString().substring(0, 8));
    }

    /** También se puede crear un RunId a partir de una cadena existente */
    public static RunId of(String value) {
        return new RunId(value);
    }

    /// Métodos generales:

    public String value() {
        return runId;
    }

    @Override
    public String toString() {
        return runId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RunId)) return false;
        RunId runID = (RunId) o;
        return Objects.equals(runId, runID.runId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(runId);
    }

}
