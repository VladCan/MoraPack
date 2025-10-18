package pe.edu.pucp.morapack.airscheduler.bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import pe.edu.pucp.morapack.airscheduler.engine.flights.domain.model.ArriboExogeno;
import pe.edu.pucp.morapack.airscheduler.engine.flights.domain.model.OcupacionAlmacen;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

public final class DebugEstado {

    private DebugEstado() {}

    public static void debugEstado(Map<String, List<ArriboExogeno>> enVuelo,
                                   List<OcupacionAlmacen> reservas,
                                   Instant presenteUTC,
                                   Path outputFile) {
        final int WIDTH = 86;
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                               .withZone(ZoneOffset.UTC);

        StringBuilder sb = new StringBuilder();
        sb.append(line(WIDTH)).append('\n');
        sb.append(center("ENVUELO Y RESERVAS", WIDTH, '─')).append('\n');
        sb.append(line(WIDTH)).append('\n');

        // ========= En vuelo =========
        sb.append("\nVUELOS EN VUELO\n");
        if (enVuelo == null || enVuelo.isEmpty()) {
            sb.append("  (sin vuelos en vuelo)\n");
        } else {
            enVuelo.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> {
                        List<ArriboExogeno> lista = (e.getValue() == null) ? List.of() : e.getValue();
                        sb.append(String.format("  %-5s (%2d):%n", e.getKey(), lista.size()));
                        lista.stream()
                             .sorted(Comparator.comparing(ArriboExogeno::arriboUtc))
                             .forEach(a -> sb.append(String.format("    → %s  qty=%3d%n",
                                     f.format(a.arriboUtc()), a.cantidad())));
                    });
        }

        // ========= Reservas =========
        sb.append("\nRESERVAS (ventana 2h)\n");
        if (reservas == null || reservas.isEmpty()) {
            sb.append("  (sin reservas)\n");
        } else {
            Map<String, List<OcupacionAlmacen>> porAeropuerto =
                    reservas.stream()
                            .collect(groupingBy(OcupacionAlmacen::aeropuerto, TreeMap::new, toList()));

            int wA = 6, wD = 19, wH = 19, wC = 6;
            sb.append(String.format("  %-" + wA + "s  %-" + wD + "s  %-" + wH + "s  %-" + wC + "s%n",
                    "AEROP", "DESDE (UTC)", "HASTA (UTC)", "CANT."));
            sb.append("  ").append("─".repeat(wA)).append("  ")
              .append("─".repeat(wD)).append("  ")
              .append("─".repeat(wH)).append("  ")
              .append("─".repeat(wC)).append('\n');

            porAeropuerto.forEach((ap, lista) -> {
                lista.stream()
                     .sorted(Comparator.comparing(OcupacionAlmacen::desde))
                     .forEach(r -> sb.append(String.format("  %-" + wA + "s  %-" + wD + "s  %-" + wH + "s  %4d%n",
                             ap,
                             f.format(r.desde()),
                             f.format(r.hasta()),
                             r.cantidad())));
            });
        }

        sb.append('\n').append(center("PRESENTE: " + f.format(presenteUTC) + "Z", WIDTH, '─')).append('\n');

        appendToFile(outputFile, sb.toString());
        // <-- ya no se imprime en consola
    }

    private static void appendToFile(Path file, String content) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            if (Files.exists(file)) {
                Files.writeString(file, System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.APPEND);
            }
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("No se pudo escribir/apendizar " + file + ": " + e.getMessage());
        }
    }

    private static String line(int width) {
        return "─".repeat(Math.max(1, width));
    }

    private static String center(String text, int width, char ch) {
        if (text == null) text = "";
        if (text.length() >= width) return text;
        int left = (width - text.length()) / 2;
        int right = width - left - text.length();
        return String.valueOf(ch).repeat(left) + text + String.valueOf(ch).repeat(right);
    }
}
