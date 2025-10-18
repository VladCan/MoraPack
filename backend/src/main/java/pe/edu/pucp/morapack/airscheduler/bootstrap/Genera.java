package pe.edu.pucp.morapack.airscheduler.bootstrap;

import pe.edu.pucp.morapack.airscheduler.infra.io.ArchivoUtils;
import pe.edu.pucp.morapack.airscheduler.infra.memory.AeropuertosMap;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class Genera {

    private static final String AIRPORTS_RESOURCE = "c.1inf54.25.2.Aeropuerto.husos.v1.20250818__estudiantes.txt";

    private static final String[] DESTINOS = {
            "SKBO", "SEQM", "SVMI", "SBBR", "SPIM", "SLLP", "SCEL", "SABE", "SGAS", "SUAA",
            "LATI", "EDDI", "LOWW", "EBCI", "UMMS", "LBSF", "LKPR", "LDZA", "EKCH", "EHAM",
            "VIDP", "OSDI", "OERK", "OMDB", "OAKB", "OOMS", "OYSN", "OPKC", "UBBB", "OJAI"
    };

    // Formato igual al que ya usas
    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * Genera N pedidos con tiempos globales crecientes (UTC),
     * y los escribe en hora local del destino (según GMT del aeropuerto).
     *
     * @param ruta            archivo de salida (ej.
     *                        "src/main/resources/pedidos.txt")
     * @param cantidadPedidos número de pedidos
     * @param horasHorizonte  horizonte total a cubrir aprox (ej. 72 h)
     */
    public static void generarArchivo(Path ruta, int cantidadPedidos, int horasHorizonte) {
        // 1) Cargar GMT por aeropuerto
        AeropuertosMap aMap = new AeropuertosMap();
        try (Scanner sc = ArchivoUtils.getScannerFromResource(AIRPORTS_RESOURCE)) {
            if (sc == null)
                throw new IllegalStateException("No se pudo abrir " + AIRPORTS_RESOURCE);
            aMap.leerDatos(sc);
        }

        Random random = new Random();
        try (FileWriter writer = new FileWriter(ruta.toFile(), StandardCharsets.UTF_8)) {

            // 2) Línea de tiempo global (UTC) monótona
            Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS); // base “ahora”
            Duration horizonte = Duration.ofHours(horasHorizonte);
            long avgStepSec = Math.max(1, horizonte.getSeconds() / Math.max(1, cantidadPedidos)); // paso promedio
            // Para que no quede totalmente uniforme, agregamos jitter pequeño
            long jitterMax = Math.max(1, avgStepSec / 2);

            Instant t = base;

            for (int i = 1; i <= cantidadPedidos; i++) {
                int idPedido = i;
                int idCliente = 100 + random.nextInt(51); // [100..150]
                String destino = DESTINOS[random.nextInt(DESTINOS.length)];

                // 2.a avanzar el reloj global con paso positivo + jitter
                long step = avgStepSec + (random.nextLong(-jitterMax, jitterMax + 1));
                if (step < 1)
                    step = 1; // asegura monotonía estricta
                t = t.plusSeconds(step);

                // 3) Convertir ese Instant a hora local del destino según su GMT entero
                int gmt = Optional.ofNullable(aMap.obtener(destino))
                        .map(a -> a.getGMT())
                        .orElse(0); // fallback por si faltara (no debería)
                ZoneOffset offset = ZoneOffset.ofHours(gmt);
                LocalDateTime fechaLocal = LocalDateTime.ofInstant(t, offset);

                // 4) Cantidad (arreglado el rango a [50..250])
                int cantidad = 50 + random.nextInt(201);

                // 5) Escribir línea
                writer.write(idPedido + "," + idCliente + "," + destino + ","
                        + fechaLocal.format(ISO_LOCAL) + "," + cantidad + "\n");
            }

            System.out.println("Archivo generado en: " + ruta);
        } catch (IOException e) {
            System.out.println("Error al generar archivo: " + e.getMessage());
        }
    }

    private static java.nio.file.Path resolveWritablePedidosPath() {
        try {
            // target/classes (o build/classes...) del módulo donde está Genera
            var classesDir = java.nio.file.Paths.get(
                    Genera.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (java.nio.file.Files.isDirectory(classesDir)) {
                return classesDir.resolve("pedidos.txt"); // <— aquí escribimos
            }
        } catch (Exception ignore) {
        }

        // Fallbacks si lo anterior no es un directorio (p.ej. corriendo desde un JAR):
        var p1 = java.nio.file.Paths.get("air-scheduler", "src", "main", "resources", "pedidos.txt");
        if (java.nio.file.Files.exists(p1.getParent()))
            return p1;

        var p2 = java.nio.file.Paths.get("src", "main", "resources", "pedidos.txt");
        if (java.nio.file.Files.exists(p2.getParent()))
            return p2;

        // Último recurso: carpeta 'data' en el cwd
        return java.nio.file.Paths.get("data", "pedidos.txt");
    }

    // para probarlo directamente
    public static void main(String[] args) {
        java.nio.file.Path out = resolveWritablePedidosPath();
        try {
            var parent = out.getParent();
            if (parent != null)
                java.nio.file.Files.createDirectories(parent);
        } catch (java.io.IOException e) {
            throw new RuntimeException("No pude crear carpeta: " + out, e);
        }
        System.out.println("Generando en: " + out.toAbsolutePath());
        generarArchivo(out, 100, 72);
    }
}
