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

    private static final String AIRPORTS_RESOURCE = "c.1inf54.25.2.Aeropuerto.husos.v1.20250818__estudiantes.txt";

    private static final String[] DESTINOS = {
            "SKBO", "SEQM", "SVMI", "SBBR", "SLLP", "SCEL", "SABE", "SGAS", "SUAA",
            "LATI", "EDDI", "LOWW", "UMMS", "LBSF", "LKPR", "LDZA", "EKCH", "EHAM",
            "VIDP", "OSDI", "OERK", "OMDB", "OAKB", "OOMS", "OYSN", "OPKC", "OJAI"
    };

    // ====== KNOBS (ajusta intensidades aquí) ======
    // Cantidad por pedido
    private static final int PISO_CANT = 250;     // mínimo
    private static final int CANT_MAX = 500;      // máximo duro del archivo (3 dígitos)
    private static final double QUANTITY_DAY_BOOST_MAX = 0.35; // +35% al final del horizonte (sobre tu logística)

    // Ritmo de llegadas (pedidos/día): el factor final vs el inicial (p.e. 3.0 => 3x más rápido al final)
    private static final double RATE_GROWTH_FACTOR = 3.0;

    // Hotspots (sobrecarga por aeropuertos)
    private static final int HOTSPOT_COUNT = 3;         // cuántos aeropuertos se vuelven calientes
    private static final double HOTSPOT_INTENSITY = 4.0; // multiplicador de peso al final (≈ 1 al inicio, ~4 al final)
    private static final double HOTSPOT_QTY_BONUS = 0.25; // hasta +25% de cantidad extra en hotspots al final

    // Suavizado AR(1) original
    private static final double ALPHA_AR1 = 0.35;

    // Formato original: yyyy-MM-dd-HH-mm-ss
    private static final DateTimeFormatter HORA_LOCAL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");

    /**
     * Genera solo el archivo:
     * - pedidosProfe.txt (formato original)
     *
     * Ahora:
     * - La tasa de pedidos aumenta a lo largo del horizonte (más pedidos/día).
     * - La cantidad por pedido aumenta con el progreso del horizonte (más productos/pedido).
     * - Algunos aeropuertos se “sobrecargan” (hotspots) al final.
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

        // ===== Línea de tiempo global (UTC) =====
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        long horizonSec = Duration.ofHours(horasHorizonte).getSeconds();
        if (horizonSec < cantidadPedidos) horizonSec = cantidadPedidos; // evita pasos 0 si horizonte es pequeño

        // ---- 1) Construimos pasos con ritmo creciente (más pedidos hacia el final) ----
        // Paso i ~ 1 / rateWeight(i), normalizado para que sum(pasos) ≈ horizonSec
        double denom = 0.0;
        double[] invWeights = new double[cantidadPedidos];
        for (int i = 0; i < cantidadPedidos; i++) {
            double prog = cantidadPedidos == 1 ? 1.0 : i / (double) (cantidadPedidos - 1); // 0..1
            double rateWeight = 1.0 + (RATE_GROWTH_FACTOR - 1.0) * prog; // crece linealmente
            invWeights[i] = 1.0 / rateWeight;
            denom += invWeights[i];
        }
        double baseStep = horizonSec / denom;

        // Hotspots (elegimos cuáles serán calientes)
        int[] hotspotIdx = pickHotspots(DESTINOS.length, HOTSPOT_COUNT, random);
        boolean[] isHotspot = new boolean[DESTINOS.length];
        for (int idx : hotspotIdx) isHotspot[idx] = true;

        Instant t = base;
        long elapsedSec = 0L;

        // ===== Variables del generador de cantidades =====
        // Logística base (misma idea que tenías; dejamos kSoft alto para no topar rápido)
        final double kSoft = 940 + random.nextInt(21); // 940..960
        final int m = 10;
        final double r = 0.05 + 0.02 * random.nextDouble(); // 0.05–0.07
        final double x0 = m + (1.0 / r) * Math.log((kSoft / (double) PISO_CANT) - 1.0);

        int prevCantidad = PISO_CANT;

        for (int i = 1; i <= cantidadPedidos; i++) {
            int i0 = i - 1;
            // ---- 1A) Avanzar el reloj con paso decreciente (ritmo creciente) + jitter proporcional ----
            double prog = cantidadPedidos == 1 ? 1.0 : i0 / (double) (cantidadPedidos - 1); // 0..1
            double stepIdeal = baseStep * invWeights[i0]; // ya es ~ 1/rateWeight
            long jitterAmp = Math.max(1L, Math.round(stepIdeal / 3.0));
            long step = Math.max(1L, Math.round(stepIdeal + random.nextLong(-jitterAmp, jitterAmp + 1)));
            t = t.plusSeconds(step);
            elapsedSec += step;

            // ---- 2) Elegir destino con pesos dinámicos (hotspots ganan tracción hacia el final) ----
            String destino = pickDestinoWeighted(random, prog, isHotspot);

            // ---- 3) Convertir a hora local del destino ----
            int gmt = Optional.ofNullable(aMap.obtener(destino))
                    .map(a -> a.getGMT())
                    .orElse(0);
            ZoneOffset offset = ZoneOffset.ofHours(gmt);
            LocalDateTime fechaLocal = LocalDateTime.ofInstant(t, offset);

            // ---- 4) Generación de cantidad (logística + AR(1) + boost por día + bonus por hotspot) ----
            double mu = kSoft / (1.0 + Math.exp(-r * (i - x0)));

            // Ruido proporcional
            double sigma = Math.max(3.0, 0.02 * mu);
            double gauss = random.nextGaussian() * sigma;

            // AR(1)
            double yCont = ALPHA_AR1 * mu + (1.0 - ALPHA_AR1) * prevCantidad + gauss;
            int y = (int) Math.round(yCont);
            if (y < PISO_CANT) y = PISO_CANT;

            // Progreso del día en [0,1] respecto al horizonte real transcurrido
            double dayProg = Math.max(0.0, Math.min(1.0, horizonSec > 0 ? (elapsedSec / (double) horizonSec) : 1.0));

            // Boost “por día”: al final del horizonte, +QUANTITY_DAY_BOOST_MAX (suave y convexa)
            double qtyBoost = 1.0 + QUANTITY_DAY_BOOST_MAX * dayProg + 0.20 * dayProg * dayProg;
            y = (int) Math.round(y * qtyBoost);

            // Bonus por hotspot que crece hacia el final
            if (isHotspotIndex(destino, isHotspot)) {
                double hotBonus = 1.0 + HOTSPOT_QTY_BONUS * dayProg;
                y = (int) Math.round(y * hotBonus);
            }

            // Pequeño jitter entero y clamps
            y += random.nextInt(7) - 3; // -3..+3
            if (y < PISO_CANT) y = PISO_CANT;

            // Evitar tocar 999 demasiado temprano (hasta el 98% del horizonte)
            if (dayProg < 0.98) {
                int softCapDyn = CANT_MAX - (5 + random.nextInt(21)); // 974..994
                if (y > softCapDyn) y = softCapDyn;
            }
            if (y > CANT_MAX) y = CANT_MAX;

            prevCantidad = y;

            // ---- 5) Rellenar línea formato original ----
            int idCliente = 100 + random.nextInt(51); // [100..150]
            String idClienteStr7 = String.format("%07d", idCliente);
            String cantidadStr3 = String.format("%03d", y);

            String lineaOriginal = fechaLocal.format(HORA_LOCAL) + "-" +
                    destino + "-" +
                    cantidadStr3 + "-" +
                    idClienteStr7;
            pedidosFormatoOriginal.add(lineaOriginal);
        }

        // ===== Escribir archivo (solo formato original) =====
        Path rutaOriginal = baseRuta;
        escribirArchivo(rutaOriginal, pedidosFormatoOriginal);
        System.out.println("Archivo (formato original) generado en: " + rutaOriginal.toAbsolutePath());
    }

    // ------------------ Helpers ------------------

    private static String pickDestinoWeighted(Random random, double prog, boolean[] isHotspot) {
        // Peso base 1.0 para todos; los hotspots ganan peso ~ (1 + HOTSPOT_INTENSITY*prog^2)
        // (prog^2 hace que la concentración sea fuerte solo al final)
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
        generarArchivo(out, 10000, 8760);
    }
}
