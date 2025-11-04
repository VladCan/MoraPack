package pe.edu.pucp.morapack.airscheduler.api.response;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class JsonResponse {
    public String status;
    public String message;
    public String filePath;

    public JsonResponse(String status, String message, String filePath) {
        this.status = status;
        this.message = message;
        this.filePath = filePath;
    }
}
