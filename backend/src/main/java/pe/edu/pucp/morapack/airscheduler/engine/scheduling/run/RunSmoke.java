package pe.edu.pucp.morapack.airscheduler.engine.scheduling.run;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente sencillo para probar el backend:
 * - POST /runs con SIM_SEMANAL (1h) para terminar rápido
 * - Lee runId del JSON
 * - Se suscribe a /runs/{runId}/stream y muestra ventanas (vuelos/pedidos)
 *
 * Ejecutar (PowerShell, desde el folder del pom.xml):
 *   .\mvnw.cmd exec:java -Dexec.mainClass=pe.edu.pucp.morapack.airscheduler.engine.scheduling.run.RunSmoke
 *
 * (o en VS Code con "Run Java")
 */
public class RunSmoke {

    // Puedes sobreescribir con env var API_BASE_URL (p.ej. http://localhost:8080)
    private static final String API_BASE = System.getenv().getOrDefault("API_BASE_URL", "http://localhost:8080");

    private static final Pattern RUN_ID_PATTERN = Pattern.compile("\"runId\"\\s*:\\s*\"([^\"]+)\"");

    public static void main(String[] args) throws Exception {
        System.out.println("[RunSmoke] API_BASE=" + API_BASE);

        // Run de 2 horas con ventanas de 30 minutos para ver más actividad
        // Usando un rango más amplio donde probablemente haya pedidos
        String body = """
        {
          "scenario": "SIM_SEMANAL",
          "startUtc": "2025-10-19T10:45:00Z",
          "endUtc":   "2025-10-19T11:45:00Z",
          "windowHours": 1
        }
        """;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // 1) POST /runs
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/runs"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        System.out.println("[RunSmoke] POST /runs ...");
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.println("[RunSmoke] Status: " + resp.statusCode());
        System.out.println("[RunSmoke] Body: " + resp.body());

        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            System.err.println("[RunSmoke] Error: respuesta no-2xx al crear el run.");
            return;
        }

        // 2) Extraer runId del JSON (regex simple)
        Matcher m = RUN_ID_PATTERN.matcher(resp.body());
        if (!m.find()) {
            System.err.println("[RunSmoke] No pude encontrar runId en la respuesta. ¿JSON esperado?");
            return;
        }
        String runId = m.group(1);
        System.out.println("[RunSmoke] runId=" + runId);

        // 3) Suscribirse al SSE /runs/{id}/stream
        URI sseUri = URI.create(API_BASE + "/runs/" + runId + "/stream");
        System.out.println("[RunSmoke] Conectando SSE: " + sseUri);

        HttpRequest sseReq = HttpRequest.newBuilder()
                .uri(sseUri)
                .GET() // sin timeout para no cortar el stream
                .build();

        HttpResponse<InputStream> sseResp = client.send(sseReq, HttpResponse.BodyHandlers.ofInputStream());
        System.out.println("[RunSmoke] SSE status: " + sseResp.statusCode());
        if (sseResp.statusCode() < 200 || sseResp.statusCode() >= 300) {
            System.err.println("[RunSmoke] No se pudo abrir el SSE: status " + sseResp.statusCode());
            return;
        }

        long t0 = System.nanoTime();
        long startMs = System.currentTimeMillis();
        long timeoutMs = 90_000; // 90s de seguridad
        int windows = 0;

        try (InputStream is = sseResp.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;

                // Soporta: 1) JSON por línea, 2) SSE con "data: { ... }"
                String payload = line.startsWith("data:") ? line.substring(5).trim() : line;

                // RUN_STARTED/TICK (opcional): descomenta si quieres verlos
                // System.out.println(payload);

                // Ventana: imprime resumen (start→end y conteos aprox de vuelos/pedidos)
                if (payload.contains("\"type\":\"WINDOW\"")) {
                    windows++;

                    String ws = payload.replaceAll(".*\"windowStartUtc\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                    String we = payload.replaceAll(".*\"windowEndUtc\"\\s*:\\s*\"([^\"]+)\".*", "$1");

                    int vuelos = 0, pedidos = 0;
                    try {
                        var vPart = payload.split("\"vuelos\"\\s*:\\s*\\[", 2);
                        if (vPart.length == 2) {
                            var right = vPart[1];
                            var closing = right.indexOf(']');
                            if (closing >= 0) {
                                var inside = right.substring(0, closing).trim();
                                if (!inside.isEmpty()) vuelos = inside.split("\\},\\s*\\{").length;
                            }
                        }
                        var pPart = payload.split("\"pedidos\"\\s*:\\s*\\[", 2);
                        if (pPart.length == 2) {
                            var right = pPart[1];
                            var closing = right.indexOf(']');
                            if (closing >= 0) {
                                var inside = right.substring(0, closing).trim();
                                if (!inside.isEmpty()) pedidos = inside.split("\\},\\s*\\{").length;
                            }
                        }
                    } catch (Exception ignore) {}

                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    System.out.println("[RunSmoke] WINDOW " + ws + " → " + we +
                                       " | vuelos=" + vuelos + " pedidos=" + pedidos +
                                       " (" + ms + " ms)");

                    // Si solo quieres validación mínima, corta en la primera:
                    // break;
                }

                // Fin del run
                if (payload.contains("\"type\":\"FINISHED\"")) {
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    System.out.println("[RunSmoke] 🏁 FINISHED en " + ms + " ms, ventanas=" + windows);
                    break;
                }

                // Timeout de seguridad
                if (System.currentTimeMillis() - startMs > timeoutMs) {
                    System.out.println("[RunSmoke] ⏱️ Timeout (" + timeoutMs + " ms). Ventanas=" + windows + ". Saliendo.");
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("[RunSmoke] Error leyendo SSE: " + e.getMessage());
        }

        System.out.println("[RunSmoke] Listo.");
    }
}