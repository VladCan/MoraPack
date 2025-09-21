package pe.edu.pucp.morapack.airscheduler.bootstrap;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.orders.adapters.io.ArchivoUtils;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class GeneraP {

    private static final String AIRPORTS_RESOURCE = "c.1inf54.25.2.Aeropuerto.husos.v1.20250818__estudiantes.txt";

    private static final String[] DESTINOS = {
            "SKBO", "SEQM", "SVMI", "SBBR", "SLLP", "SCEL", "SABE", "SGAS", "SUAA",
            "LATI", "EDDI", "LOWW", "UMMS", "LBSF", "LKPR", "LDZA", "EKCH", "EHAM",
            "VIDP", "OSDI", "OERK", "OMDB", "OAKB", "OOMS", "OYSN", "OPKC", "OJAI"
    };

    // Formato final: yyyy-MM-dd-HH-mm-ss
    private static final DateTimeFormatter HORA_LOCAL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");

    /**
     * Genera pedidos con el formato solicitado:
     * yyyy-MM-dd-HH-mm-ss-dest-###-IdClien
     */
    public static void generarArchivo(Path ruta, int cantidadPedidos, int horasHorizonte) {
        AeropuertosMap aMap = new AeropuertosMap();
        try (Scanner sc = ArchivoUtils.getScannerFromResource(AIRPORTS_RESOURCE)) {
            if (sc == null)
                throw new IllegalStateException("No se pudo abrir " + AIRPORTS_RESOURCE);
            aMap.leerDatos(sc);
        }

        Random random = new Random();
        List<String> pedidos = new ArrayList<>();

        // ===== 1) Línea de tiempo global UTC (monótona) =====
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Duration horizonte = Duration.ofHours(horasHorizonte);
        long avgStepSec = Math.max(1, horizonte.getSeconds() / Math.max(1, cantidadPedidos));
        long jitterMax = Math.max(1, avgStepSec / 2);

        Instant t = base;

        for (int i = 1; i <= cantidadPedidos; i++) {
            // Cliente (7 dígitos)
            int idCliente = 100 + random.nextInt(51); // [100..150]
            String idClienteStr = String.format("%07d", idCliente);

            // Destino
            String destino = DESTINOS[random.nextInt(DESTINOS.length)];

            // ===== 2) Avanzar el reloj global en UTC =====
            long step = avgStepSec + (random.nextLong(-jitterMax, jitterMax + 1));
            if (step < 1) step = 1;
            t = t.plusSeconds(step); // siempre crece, asegura orden cronológico

            // ===== 3) Convertir a hora local recién aquí =====
            int gmt = Optional.ofNullable(aMap.obtener(destino))
                    .map(a -> a.getGMT())
                    .orElse(0);
            ZoneOffset offset = ZoneOffset.ofHours(gmt);
            LocalDateTime fechaLocal = LocalDateTime.ofInstant(t, offset);

            // ========================
            // Modelo logístico de demanda
            // ========================
            double k = 300; // máximo
            double x0 = cantidadPedidos / 2.0; // punto medio
            double r = 0.01 + 0.02 * random.nextDouble(); // pendiente aleatoria
            int cantidad = (int) Math.round(k / (1 + Math.exp(-r * (i - x0))));

            // Ajustar a rango válido [1..300]
            if (cantidad < 20) cantidad = 20;
            if (cantidad > 300) cantidad = 300;

            String cantidadStr = String.format("%03d", cantidad);

            // ===== 4) Formato final =====
            String linea = fechaLocal.format(HORA_LOCAL) + "-" +
                    destino + "-" +
                    cantidadStr + "-" +
                    idClienteStr;

            pedidos.add(linea);
        }

        // Ya no hace falta ordenar, porque la generación en UTC ya garantiza cronología
        // pedidos.sort(Comparator.naturalOrder());

        try (FileWriter writer = new FileWriter(ruta.toFile(), StandardCharsets.UTF_8)) {
            for (String pedido : pedidos) {
                writer.write(pedido + "\n");
            }
            System.out.println("Archivo generado en: " + ruta);
        } catch (IOException e) {
            System.out.println("Error al generar archivo: " + e.getMessage());
        }
    }

    private static Path resolveWritablePedidosPath() {
        try {
            var classesDir = java.nio.file.Paths.get(
                    GeneraP.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (java.nio.file.Files.isDirectory(classesDir)) {
                return classesDir.resolve("pedidosProfe.txt");
            }
        } catch (Exception ignore) {}

        var p1 = java.nio.file.Paths.get("air-scheduler", "src", "main", "resources", "pedidosProfe.txt");
        if (java.nio.file.Files.exists(p1.getParent())) return p1;

        var p2 = java.nio.file.Paths.get("src", "main", "resources", "pedidosProfe.txt");
        if (java.nio.file.Files.exists(p2.getParent())) return p2;

        return java.nio.file.Paths.get("data", "pedidosProfe.txt");
    }

    public static void main(String[] args) {
        Path out = resolveWritablePedidosPath();
        try {
            var parent = out.getParent();
            if (parent != null) java.nio.file.Files.createDirectories(parent);
        } catch (IOException e) {
            throw new RuntimeException("No pude crear carpeta: " + out, e);
        }
        System.out.println("Generando en: " + out.toAbsolutePath());
        generarArchivo(out, 500, 12);
    }
}
