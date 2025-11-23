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
 * Cliente para probar el sistema de ventanas y ver los vuelos/pedidos
 */
public class TestWindowClient {
    
    private static final String API_BASE = "http://localhost:8080";
    private static final Pattern RUN_ID_PATTERN = Pattern.compile("\"runId\"\\s*:\\s*\"([^\"]+)\"");
    
    public static void main(String[] args) throws Exception {
        System.out.println("[TestWindowClient] Iniciando prueba...");
        
        // Crear un nuevo run
        String body = """
        {
            "scenario": "SIM_SEMANAL",
            "startUtc": "2025-10-19T10:45:00Z",
            "endUtc": "2025-10-19T12:45:00Z",
            "windowHours": 1
        }
        """;
        
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        
        // 1) Crear run
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/runs"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        
        System.out.println("[TestWindowClient] Creando run...");
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.println("[TestWindowClient] Status: " + resp.statusCode());
        System.out.println("[TestWindowClient] Response: " + resp.body());
        
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            System.err.println("[TestWindowClient] Error creando run");
            return;
        }
        
        // 2) Extraer runId
        Matcher m = RUN_ID_PATTERN.matcher(resp.body());
        if (!m.find()) {
            System.err.println("[TestWindowClient] No se encontró runId");
            return;
        }
        String runId = m.group(1);
        System.out.println("[TestWindowClient] RunId: " + runId);
        
        // 3) Suscribirse al SSE
        URI sseUri = URI.create(API_BASE + "/runs/" + runId + "/stream");
        System.out.println("[TestWindowClient] Conectando a: " + sseUri);
        
        HttpRequest sseReq = HttpRequest.newBuilder()
                .uri(sseUri)
                .GET()
                .build();
        
        HttpResponse<InputStream> sseResp = client.send(sseReq, HttpResponse.BodyHandlers.ofInputStream());
        System.out.println("[TestWindowClient] SSE Status: " + sseResp.statusCode());
        
        if (sseResp.statusCode() < 200 || sseResp.statusCode() >= 300) {
            System.err.println("[TestWindowClient] Error conectando al SSE");
            return;
        }
        
        // 4) Leer eventos
        try (InputStream is = sseResp.body(); 
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            
            String line;
            int windowCount = 0;
            
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                
                // Extraer payload
                String payload = line.startsWith("data:") ? line.substring(5).trim() : line;
                
                // Mostrar todos los eventos
                System.out.println("[TestWindowClient] Evento: " + payload);
                
                // Detectar eventos de ventana
                if (payload.contains("\"type\":\"WINDOW\"")) {
                    windowCount++;
                    System.out.println("🎯 ¡VENTANA ENCONTRADA! #" + windowCount);
                    
                    // Extraer información básica
                    String windowStart = extractField(payload, "windowStartUtc");
                    String windowEnd = extractField(payload, "windowEndUtc");
                    String windowIdx = extractField(payload, "windowIdx");
                    
                    System.out.println("   Ventana " + windowIdx + ": " + windowStart + " → " + windowEnd);
                    
                    // Contar vuelos y pedidos
                    int vuelos = countArrayElements(payload, "vuelos");
                    int pedidos = countArrayElements(payload, "archivosPedidos");
                    
                    System.out.println("   Vuelos: " + vuelos + ", Pedidos: " + pedidos);
                    
                    if (vuelos > 0) {
                        System.out.println("   📋 Detalles de vuelos:");
                        extractArrayDetails(payload, "vuelos");
                    }
                    
                    if (pedidos > 0) {
                        System.out.println("   📦 Detalles de pedidos:");
                        extractArrayDetails(payload, "archivosPedidos");
                    }
                    
                    System.out.println();
                }
                
                // Detectar fin del run
                if (payload.contains("\"type\":\"FINISHED\"")) {
                    System.out.println("🏁 Run terminado. Total ventanas: " + windowCount);
                    break;
                }
            }
        }
        
        System.out.println("[TestWindowClient] Prueba completada.");
    }
    
    private static String extractField(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : "N/A";
    }
    
    private static int countArrayElements(String json, String arrayName) {
        try {
            String arrayPattern = "\"" + arrayName + "\"\\s*:\\s*\\[";
            String[] parts = json.split(arrayPattern, 2);
            if (parts.length < 2) return 0;
            
            String right = parts[1];
            int closing = right.indexOf(']');
            if (closing < 0) return 0;
            
            String inside = right.substring(0, closing).trim();
            if (inside.isEmpty()) return 0;
            
            return inside.split("\\},\\s*\\{").length;
        } catch (Exception e) {
            return 0;
        }
    }
    
    private static void extractArrayDetails(String json, String arrayName) {
        try {
            String arrayPattern = "\"" + arrayName + "\"\\s*:\\s*\\[";
            String[] parts = json.split(arrayPattern, 2);
            if (parts.length < 2) return;
            
            String right = parts[1];
            int closing = right.indexOf(']');
            if (closing < 0) return;
            
            String inside = right.substring(0, closing).trim();
            if (inside.isEmpty()) return;
            
            // Mostrar los primeros elementos
            String[] elements = inside.split("\\},\\s*\\{");
            for (int i = 0; i < Math.min(elements.length, 3); i++) {
                String element = elements[i].trim();
                if (!element.startsWith("{")) element = "{" + element;
                if (!element.endsWith("}")) element = element + "}";
                System.out.println("     " + (i+1) + ": " + element);
            }
            
            if (elements.length > 3) {
                System.out.println("     ... y " + (elements.length - 3) + " más");
            }
        } catch (Exception e) {
            System.out.println("     Error extrayendo detalles: " + e.getMessage());
        }
    }
}
