package pe.edu.pucp.morapack.airscheduler.bootstrap;

import pe.edu.pucp.morapack.airscheduler.engine.flights.adapters.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.engine.orders.adapters.io.ArchivoUtils;

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

    // Formato original: yyyy-MM-dd-HH-mm-ss
    private static final DateTimeFormatter HORA_LOCAL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");

    // Para el CSV: ISO local sin milisegundos
    private static final DateTimeFormatter ISO_LOCAL_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * Genera ambos archivos:
     * - pedidosProfe.txt (formato original)
     * - pedidosProfe_alt.csv (idpedido,idcliente,codigoaeropuerto,fechaPedido,cantidad)
     */
    public static void generarArchivo(Path baseRuta, int cantidadPedidos, int horasHorizonte) {
        AeropuertosMap aMap = new AeropuertosMap();
        try (Scanner sc = ArchivoUtils.getScannerFromResource(AIRPORTS_RESOURCE)) {
            if (sc == null)
                throw new IllegalStateException("No se pudo abrir " + AIRPORTS_RESOURCE);
            aMap.leerDatos(sc);
        }

        Random random = new Random();

        List<String> pedidosFormatoOriginal = new ArrayList<>();
        List<String> pedidosFormatoNuevo = new ArrayList<>();

        // ===== 1) Línea de tiempo global UTC (monótona) =====
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Duration horizonte = Duration.ofHours(horasHorizonte);
        long avgStepSec = Math.max(1, horizonte.getSeconds() / Math.max(1, cantidadPedidos));
        long jitterMax = Math.max(1, avgStepSec / 2);

        Instant t = base;

        // ===== Variables del generador de cantidades =====
        final int piso = 250;
        final int techo = 400;

        // Techo "blando" para la logística base (evita llegar rápido a 999)
        final double kSoft = 940 + random.nextInt(21); // 940..960

        // Queremos dejar el piso alrededor del pedido 10
        final int m = 10;

        // Pendiente moderada
        final double r = 0.05 + 0.02 * random.nextDouble(); // 0.05–0.07

        // x0 para que L(m) ~ piso (usando kSoft)
        final double x0 = m + (1.0 / r) * Math.log((kSoft / (double) piso) - 1.0);

        // Suavizado AR(1): combinación de la media logística con el valor previo
        final double alpha = 0.35; // cercanía a la media (0 -> sigue mucho al previo, 1 -> sigue la media)

        int prevCantidad = piso;

        // Definir ventanas de cola
        final int tailStart = (int) Math.floor(0.90 * cantidadPedidos); // último 10%
        final int allowHardMaxFrom = (int) Math.floor(0.98 * cantidadPedidos); // último 2% permite 999

        for (int i = 1; i <= cantidadPedidos; i++) {
            // Cliente
            int idCliente = 100 + random.nextInt(51); // [100..150]
            String idClienteStr7 = String.format("%07d", idCliente); // para el archivo original

            // Destino
            String destino = DESTINOS[random.nextInt(DESTINOS.length)];

            // ===== 2) Avanzar el reloj global en UTC =====
            long step = avgStepSec + (random.nextLong(-jitterMax, jitterMax + 1));
            if (step < 1) step = 1;
            t = t.plusSeconds(step); // siempre crece

            // ===== 3) Convertir a hora local =====
            int gmt = Optional.ofNullable(aMap.obtener(destino))
                    .map(a -> a.getGMT())
                    .orElse(0);
            ZoneOffset offset = ZoneOffset.ofHours(gmt);
            LocalDateTime fechaLocal = LocalDateTime.ofInstant(t, offset);

            // ========================
            // 4) Generación de cantidad con:
            //    - media logística creciente (hasta kSoft)
            //    - suavizado AR(1) hacia la media
            //    - ruido gaussiano proporcional
            //    - impulso de cola al final
            //    - tope dinámico (evitar 999 temprano)
            // ========================
            // Media logística base
            double mu = kSoft / (1.0 + Math.exp(-r * (i - x0)));

            // Ruido: gaussiano con desviación proporcional a la media (más variedad en altos)
            double sigma = Math.max(3.0, 0.02 * mu); // 2% de mu, mínimo 3
            double gauss = random.nextGaussian() * sigma;

            // Suavizado hacia la media (AR(1))
            double yCont = alpha * mu + (1.0 - alpha) * prevCantidad + gauss;

            int y = (int) Math.round(yCont);

            // Piso
            if (y < piso) y = piso;

            // Impulso de cola en el último 10%
            if (i >= tailStart) {
                double prog = (i - tailStart) / (double) Math.max(1, (cantidadPedidos - tailStart));
                // Factor crece de 1.0 a ~1.25 (curva suave)
                double tailFactor = 1.0 + 0.15 * prog + 0.10 * prog * prog; // hasta +25% al final
                y = (int) Math.round(y * tailFactor);
            }

            // Tope dinámico: antes del último 2% no permitimos tocar 999;
            // imponemos un techo variable ligeramente por debajo de 999.
            if (i < allowHardMaxFrom) {
                int softCapDyn = techo - (5 + random.nextInt(21)); // 999-(5..25) => 974..994
                if (y > softCapDyn) y = softCapDyn;
            }

            // Jitter entero pequeño para romper empates
            y += random.nextInt(7) - 3; // -3..+3

            // Clamps finales
            if (y < piso) y = piso;
            if (y > techo) y = techo;

            // Guardar para la próxima iteración (suavizado)
            prevCantidad = y;

            String cantidadStr3 = String.format("%03d", y);

            // ===== 4A) Línea formato original =====
            String lineaOriginal = fechaLocal.format(HORA_LOCAL) + "-" +
                    destino + "-" +
                    cantidadStr3 + "-" +
                    idClienteStr7;
            pedidosFormatoOriginal.add(lineaOriginal);

            // ===== 4B) Línea formato nuevo =====
            // idpedido,idcliente,codigoaeropuerto,fechaPedido,cantidad
            String lineaNueva = i + "," +
                    idCliente + "," +
                    destino + "," +
                    fechaLocal.format(ISO_LOCAL_SECONDS) + "," +
                    y;
            pedidosFormatoNuevo.add(lineaNueva);
        }

        // ===== Escribir archivos =====
        // Archivo original
        Path rutaOriginal = baseRuta;
        escribirArchivo(rutaOriginal, pedidosFormatoOriginal);

        // Archivo nuevo (mismo folder, distinto nombre)
        Path rutaNueva = rutaOriginal.getParent() != null
                ? rutaOriginal.getParent().resolve("pedidosProfe_alt.csv")
                : Path.of("pedidosProfe_alt.csv");
        escribirArchivo(rutaNueva, pedidosFormatoNuevo);

        System.out.println("Archivo (formato original) generado en: " + rutaOriginal.toAbsolutePath());
        System.out.println("Archivo (formato nuevo) generado en: " + rutaNueva.toAbsolutePath());
    }

    private static void escribirArchivo(Path ruta, List<String> lineas) {
        try (FileWriter writer = new FileWriter(ruta.toFile(), StandardCharsets.UTF_8)) {
            for (String linea : lineas) {
                writer.write(linea + "\n");
            }
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
        generarArchivo(out, 300, 24);
    }
}
