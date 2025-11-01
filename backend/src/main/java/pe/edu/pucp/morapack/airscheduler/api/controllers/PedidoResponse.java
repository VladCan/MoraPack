package pe.edu.pucp.morapack.airscheduler.api.controllers;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class PedidoResponse {
    public String status;
    public String message;
    public String runId;

    public PedidoResponse(String status, String message, String runId) {
        this.status = status;
        this.message = message;
        this.runId = runId;
    }

}
