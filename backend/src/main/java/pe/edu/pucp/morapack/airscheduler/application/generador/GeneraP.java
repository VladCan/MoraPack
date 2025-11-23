package pe.edu.pucp.morapack.airscheduler.application.generador;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.ArchivoUtils;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.AeropuertosMap;

public class GeneraP {

    private static final String AIRPORTS_RESOURCE = "aereopuertos.txt";

    private static final String[] DESTINOS = {
            "SKBO", "SEQM", "SVMI", "SBBR", "SLLP", "SCEL", "SABE", "SGAS", "SUAA",
            "LATI", "EDDI", "LOWW", "UMMS", "LBSF", "LKPR", "LDZA", "EKCH", "EHAM",
            "VIDP", "OSDI", "OERK", "OMDB", "OAKB", "OOMS", "OYSN", "OPKC", "OJAI"
    };

    // ====== KNOBS (ajusta intensidades aquí) ======
    private static final int PISO_CANT = 250;
    private static final int CANT_MAX = 999;
    private static final double QUANTITY_DAY_BOOST_MAX = 0.35;
    private static final double RATE_GROWTH_FACTOR = 3.0;
    private static final int HOTSPOT_COUNT = 3;
    private static final double HOTSPOT_INTENSITY = 4.0;
    private static final double HOTSPOT_QTY_BONUS = 0.25;
    private static final double ALPHA_AR1 = 0.35;

    // Formato SOLO FECHA: yyyyMMdd (ej: 20261201)
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    // ID Base para simular la secuencia del ejemplo (003964189...)
    private static final int ID_PEDIDO_BASE = 3964189;

    public static void generarArchivo(Path baseRuta, int cantidadPedidos, int horasHorizonte) {
        AeropuertosMap aMap = new AeropuertosMap();
        try (Scanner sc = ArchivoUtils.getScannerFromFilePath(AIRPORTS_RESOURCE)) {
            if (sc == null)
                throw new IllegalStateException("No se pudo abrir " + AIRPORTS_RESOURCE);
            aMap.leerDatos(sc);
        }

        Random random = new Random();
        List<String> pedidosFormatoOriginal = new ArrayList<>();

        // ===== Línea de tiempo global (UTC) =====
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        // Si quieres que empiece en 2026 como tu ejemplo, descomenta esto:
        // base = LocalDateTime.of(2026, 12, 1, 0, 0).toInstant(ZoneOffset.UTC);

        long horizonSec = Duration.ofHours(horasHorizonte).getSeconds();
        if (horizonSec < cantidadPedidos) horizonSec = cantidadPedidos;

        double denom = 0.0;
        double[] invWeights = new double[cantidadPedidos];
        for (int i = 0; i < cantidadPedidos; i++) {
            double prog = cantidadPedidos == 1 ? 1.0 : i / (double) (cantidadPedidos - 1);
            double rateWeight = 1.0 + (RATE_GROWTH_FACTOR - 1.0) * prog;
            invWeights[i] = 1.0 / rateWeight;
            denom += invWeights[i];
        }
        double baseStep = horizonSec / denom;

        int[] hotspotIdx = pickHotspots(DESTINOS.length, HOTSPOT_COUNT, random);
        boolean[] isHotspot = new boolean[DESTINOS.length];
        for (int idx : hotspotIdx) isHotspot[idx] = true;

        Instant t = base;
        long elapsedSec = 0L;

        // Logística base
        final double kSoft = 940 + random.nextInt(21);
        final int m = 10;
        final double r = 0.05 + 0.02 * random.nextDouble();
        final double x0 = m + (1.0 / r) * Math.log((kSoft / (double) PISO_CANT) - 1.0);

        int prevCantidad = PISO_CANT;

        for (int i = 1; i <= cantidadPedidos; i++) {
            int i0 = i - 1;
            // 1) Avanzar reloj
            double prog = cantidadPedidos == 1 ? 1.0 : i0 / (double) (cantidadPedidos - 1);
            double stepIdeal = baseStep * invWeights[i0];
            long jitterAmp = Math.max(1L, Math.round(stepIdeal / 3.0));
            long step = Math.max(1L, Math.round(stepIdeal + random.nextLong(-jitterAmp, jitterAmp + 1)));
            t = t.plusSeconds(step);
            elapsedSec += step;

            // 2) Elegir destino
            String destino = pickDestinoWeighted(random, prog, isHotspot);

            // 3) Convertir a hora local
            int gmt = Optional.ofNullable(aMap.obtener(destino))
                    .map(a -> a.getGMT())
                    .orElse(0);
            ZoneOffset offset = ZoneOffset.ofHours(gmt);
            LocalDateTime fechaLocal = LocalDateTime.ofInstant(t, offset);

            // 4) Generar cantidad
            double mu = kSoft / (1.0 + Math.exp(-r * (i - x0)));
            double sigma = Math.max(3.0, 0.02 * mu);
            double gauss = random.nextGaussian() * sigma;

            double yCont = ALPHA_AR1 * mu + (1.0 - ALPHA_AR1) * prevCantidad + gauss;
            int y = (int) Math.round(yCont);
            if (y < PISO_CANT) y = PISO_CANT;

            double dayProg = Math.max(0.0, Math.min(1.0, horizonSec > 0 ? (elapsedSec / (double) horizonSec) : 1.0));
            double qtyBoost = 1.0 + QUANTITY_DAY_BOOST_MAX * dayProg + 0.20 * dayProg * dayProg;
            y = (int) Math.round(y * qtyBoost);

            if (isHotspotIndex(destino, isHotspot)) {
                double hotBonus = 1.0 + HOTSPOT_QTY_BONUS * dayProg;
                y = (int) Math.round(y * hotBonus);
            }

            y += random.nextInt(7) - 3;
            if (y < PISO_CANT) y = PISO_CANT;
            if (dayProg < 0.98) {
                int softCapDyn = CANT_MAX - (5 + random.nextInt(21));
                if (y > softCapDyn) y = softCapDyn;
            }
            if (y > CANT_MAX) y = CANT_MAX;
            prevCantidad = y;

            // ==== 5) CONSTRUCCIÓN DE LA LÍNEA CORREGIDA ====

            // A. ID Pedido (9 dígitos)
            int idPedidoActual = ID_PEDIDO_BASE + i0;
            String idPedidoStr = String.format("%09d", idPedidoActual);

            // B. Fecha (yyyyMMdd)
            String fechaStr = fechaLocal.format(DATE_FORMATTER);

            // C. Hora (HH) y Minuto (mm) separados
            String horaStr = String.format("%02d", fechaLocal.getHour());
            String minStr = String.format("%02d", fechaLocal.getMinute());

            // D. Cantidad (3 dígitos)
            String cantidadStr3 = String.format("%03d", y);

            // E. Cliente (7 dígitos)
            int idCliente = 100 + random.nextInt(51);
            // El ejemplo mostraba IDs de cliente más largos (ej. 0025956),
            // ajusto aquí para generar algo similar o mantengo tu rango si prefieres.
            // Para coincidir con tu ejemplo visual '0025956', usaré un rango más alto:
            int idClienteRandom = random.nextInt(99999); 
            String idClienteStr7 = String.format("%07d", idClienteRandom);

            // FORMATO: ID-FECHA-HH-MM-DESTINO-CANT-CLIENTE
            // Total 7 campos separados por '-'
            String lineaCorregida = idPedidoStr + "-" +
                    fechaStr + "-" +
                    horaStr + "-" +
                    minStr + "-" +
                    destino + "-" +
                    cantidadStr3 + "-" +
                    idClienteStr7;

            pedidosFormatoOriginal.add(lineaCorregida);
        }

        // ===== Escribir archivo =====
        Path rutaOriginal = baseRuta;
        escribirArchivo(rutaOriginal, pedidosFormatoOriginal);
        System.out.println("Archivo generado correctamente en: " + rutaOriginal.toAbsolutePath());
    }

    // ------------------ Helpers (Sin cambios) ------------------

    private static String pickDestinoWeighted(Random random, double prog, boolean[] isHotspot) {
        double[] weights = new double[DESTINOS.length];
        double sum = 0.0;
        for (int i = 0; i < DESTINOS.length; i++) {
            double w = 1.0;
            if (isHotspot[i]) {
                w *= (1.0 + HOTSPOT_INTENSITY * prog * prog);
            }
            weights[i] = w;
            sum += w;
        }
        double r = random.nextDouble() * sum;
        double acc = 0.0;
        for (int i = 0; i < DESTINOS.length; i++) {
            acc += weights[i];
            if (r <= acc) return DESTINOS[i];
        }
        return DESTINOS[DESTINOS.length - 1];
    }

    private static boolean isHotspotIndex(String destino, boolean[] isHotspot) {
        for (int i = 0; i < DESTINOS.length; i++) {
            if (DESTINOS[i].equals(destino)) return isHotspot[i];
        }
        return false;
    }

    private static int[] pickHotspots(int n, int k, Random random) {
        if (k <= 0 || n <= 0) return new int[0];
        if (k > n) k = n;
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < n; i++) idx.add(i);
        Collections.shuffle(idx, random);
        int[] res = new int[k];
        for (int i = 0; i < k; i++) res[i] = idx.get(i);
        return res;
    }

    private static void escribirArchivo(Path ruta, List<String> lineas) {
        try (FileWriter writer = new FileWriter(ruta.toFile(), StandardCharsets.UTF_8)) {
            for (String linea : lineas) writer.write(linea + "\n");
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
        // Generamos 10000 pedidos para probar
        generarArchivo(out, 10000, 168);
    }
}