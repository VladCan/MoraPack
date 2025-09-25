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
            // Modelo logístico de demanda (igual que antes)
            // ========================
            double k = 999; // máximo
            double x0 = cantidadPedidos / 2.0; // punto medio
            double r = 0.01 + 0.02 * random.nextDouble(); // pendiente aleatoria
            int cantidad = (int) Math.round(k / (1 + Math.exp(-r * (i - x0))));
            if (cantidad < 20) cantidad = 20;
            if (cantidad > 999) cantidad = 999;

            String cantidadStr3 = String.format("%03d", cantidad);

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
                    cantidad;
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
<<<<<<< HEAD
        generarArchivo(out, 50, 12);
=======
        generarArchivo(out, 100, 12);
>>>>>>> refs/remotes/origin/daniel
    }
}
