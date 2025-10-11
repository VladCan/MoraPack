// air-scheduler/src/main/java/pe/edu/pucp/morapack/airscheduler/scheduling/adapters/io/ImpresorSolucion.java
package pe.edu.pucp.morapack.airscheduler.scheduling.adapters.io;

import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.*;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.AeropuertosMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Utilidad para imprimir/guardar soluciones en TXT y CSV.
 * Genera:
 * - out/<prefijo>-reporte.txt
 * - out/<prefijo>-pedidos.csv
 * - out/<prefijo>-vuelos.csv
 */
public final class ImpresorSolucion {
        private ImpresorSolucion() {
        }

        // ========= Formatos de tiempo =========
        private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
        private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .withZone(ZoneOffset.UTC);
        private static final Locale LOCALE_TS = Locale.US;
        private static final DateTimeFormatter TS_SHORT = DateTimeFormatter.ofPattern("dd MMM HH:mm'Z'", LOCALE_TS)
                        .withZone(ZoneOffset.UTC);

        // ========= Reglas de SLA =========
        private static final Duration VENTANA_46H = Duration.ofHours(46); // llegada ≤ 46h por pickup 2h
        private static final Duration SLA_48H = Duration.ofHours(48);

        // ========= Helpers =========
        private static String ts(Instant t) {
                return (t == null ? "-" : TS_SHORT.format(t));
        }

        private static String fmt(Instant t) {
                return (t == null ? "-" : ISO.format(t));
        }

        private static String safe(String s) {
                return (s == null ? "-" : s);
        }

        private static String bar(int val, int tot, int width) {
                if (tot <= 0)
                        return "[" + "=".repeat(width) + "]";
                int fill = Math.min(width, (int) Math.round((double) val * width / Math.max(1, tot)));
                return "[" + "#".repeat(fill) + ".".repeat(width - fill) + "]";
        }

        private static String descripcionCortaVuelo(VueloProgramadoId vuelo) {
                if (vuelo == null) {
                        return "-";
                }
                return describirVuelo(vuelo) + " (sale " + ts(vuelo.getSalidaUtc()) + ")";
        }

        private static String describirVuelo(VueloProgramadoId vuelo) {
                if (vuelo == null) {
                        return "-";
                }
                return safe(vuelo.getOrigen()) + "→" + safe(vuelo.getDestino());
        }

        // ===== API principal =====
        public static void imprimirEnConsola(SolucionProgramacion sol) {
                System.out.println(formatearReporte(sol));
        }

        public static void imprimirEnArchivo(SolucionProgramacion sol, String nombreArchivo) {
                String contenido = formatearReporte(sol);

                // 2) archivo (APPEND, sin truncar)
                Path path = Paths.get(nombreArchivo == null || nombreArchivo.isBlank()
                                ? "solucion.txt"
                                : nombreArchivo);

                try (var writer = Files.newBufferedWriter(
                                path,
                                StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE, // crea si no existe
                                StandardOpenOption.WRITE,
                                StandardOpenOption.APPEND // escribe al final
                )) {
                        writer.write(contenido);
                        // separador opcional entre ejecuciones
                        writer.write(System.lineSeparator());
                        writer.write(System.lineSeparator());
                } catch (IOException e) {
                        throw new RuntimeException("No se pudo escribir en " + path.toAbsolutePath(), e);
                }
        }

        public static Path guardarTodo(SolucionProgramacion sol, Instant presenteUtc, String prefijo) {
                try {
                        String base = (prefijo == null || prefijo.isBlank())
                                        ? "solucion-" + STAMP.format(presenteUtc == null ? Instant.now() : presenteUtc)
                                        : prefijo + "-" + STAMP
                                                        .format(presenteUtc == null ? Instant.now() : presenteUtc);

                        Path outDir = Paths.get("out");
                        Files.createDirectories(outDir);

                        Path txt = outDir.resolve(base + "-reporte.txt");
                        Path csvPedidos = outDir.resolve(base + "-pedidos.csv");
                        Path csvVuelos = outDir.resolve(base + "-vuelos.csv");

                        Files.writeString(txt, formatearReporte(sol), StandardCharsets.UTF_8,
                                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        Files.writeString(csvPedidos, csvPedidos(sol), StandardCharsets.UTF_8,
                                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        Files.writeString(csvVuelos, csvVuelos(sol), StandardCharsets.UTF_8,
                                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

                        System.out.println("✅ Reporte guardado en: " + txt.toAbsolutePath());
                        System.out.println("✅ CSV pedidos: " + csvPedidos.toAbsolutePath());
                        System.out.println("✅ CSV vuelos:  " + csvVuelos.toAbsolutePath());
                        return outDir;
                } catch (IOException e) {
                        throw new RuntimeException("Error escribiendo archivos de reporte", e);
                }
        }

        public static void imprimirReporteAeropuertos(SolucionProgramacion sol,
                        AeropuertosMap aeropuertosMap,
                        String nombreArchivo) {
                if (sol == null)
                        return;

                Map<Integer, PlanPedido> planes = sol.asMap();
                if (planes.isEmpty())
                        return;

                Map<String, AeropuertoResumen> resumenes = new TreeMap<>();

                for (PlanPedido plan : planes.values()) {
                        for (RutaAsignada ruta : plan.getRutas()) {
                                List<TramoAsignado> tramos = ruta.getTramos();
                                if (tramos == null || tramos.isEmpty())
                                        continue;

                                for (int i = 0; i < tramos.size(); i++) {
                                        TramoAsignado tramo = tramos.get(i);
                                        VueloProgramadoId vuelo = tramo.getVuelo();
                                        if (vuelo == null)
                                                continue;

                                        String origenKey = safe(vuelo.getOrigen());
                                        String destinoKey = safe(vuelo.getDestino());
                                        AeropuertoResumen origen = resumenes.computeIfAbsent(origenKey,
                                                        k -> new AeropuertoResumen());
                                        AeropuertoResumen destino = resumenes.computeIfAbsent(destinoKey,
                                                        k -> new AeropuertoResumen());

                                        int cantidad = tramo.getCantidad();
                                        String etiquetaPedido = "Pedido " + plan.getIdPedido();
                                        String siguiente = (i + 1 < tramos.size())
                                                        ? "Conexión " + descripcionCortaVuelo(tramos.get(i + 1).getVuelo())
                                                        : "Cliente final en " + safe(plan.getAeropuertoDestino());
                                        String procedencia = (i == 0)
                                                        ? "Cliente origen en " + safe(vuelo.getOrigen())
                                                        : "Desde " + descripcionCortaVuelo(tramos.get(i - 1).getVuelo());

                                        destino.llegadas.add(new EventoArribo(describirVuelo(vuelo), cantidad,
                                                        vuelo.getSalidaUtc(), vuelo.getLlegadaUtc(), etiquetaPedido,
                                                        siguiente));
                                        origen.salidas.add(new EventoSalida(describirVuelo(vuelo), cantidad,
                                                        vuelo.getSalidaUtc(), vuelo.getLlegadaUtc(), etiquetaPedido,
                                                        procedencia));

                                        Instant esperaInicioOrigen;
                                        if (i == 0) {
                                                esperaInicioOrigen = plan.getCreadoUtc();
                                        } else {
                                                VueloProgramadoId anterior = tramos.get(i - 1).getVuelo();
                                                esperaInicioOrigen = (anterior != null) ? anterior.getLlegadaUtc() : null;
                                        }
                                        Instant esperaFinOrigen = vuelo.getSalidaUtc();
                                        if (esperaInicioOrigen != null && esperaFinOrigen != null
                                                        && !esperaFinOrigen.isBefore(esperaInicioOrigen)) {
                                                origen.periodos.add(new PeriodoBodega(esperaInicioOrigen, esperaFinOrigen, cantidad));
                                        }

                                        Instant esperaInicioDestino = vuelo.getLlegadaUtc();
                                        Instant esperaFinDestino;
                                        if (i + 1 < tramos.size()) {
                                                VueloProgramadoId siguienteVuelo = tramos.get(i + 1).getVuelo();
                                                esperaFinDestino = (siguienteVuelo != null) ? siguienteVuelo.getSalidaUtc() : null;
                                        } else {
                                                esperaFinDestino = esperaInicioDestino;
                                        }
                                        if (esperaInicioDestino != null && esperaFinDestino != null
                                                        && !esperaFinDestino.isBefore(esperaInicioDestino)) {
                                                destino.periodos.add(new PeriodoBodega(esperaInicioDestino, esperaFinDestino, cantidad));
                                        }
                                }
                        }
                }

                if (resumenes.isEmpty())
                        return;

                int[] arriboCols = { 18, 10, 18, 18, 30, 18 };
                int[] salidaCols = { 18, 10, 18, 18, 30, 18 };
                int[] bodegaCols = { 18, 18, 10, 10 };
                String headerArribos = row(arriboCols, "VUELO", "CARGA", "SALE (UTC)", "LLEGA (UTC)", "SIGUIENTE ETAPA",
                                "PEDIDO");
                String ruleArribos = rule(arriboCols);
                String headerSalidas = row(salidaCols, "VUELO", "CARGA", "SALE (UTC)", "LLEGA (UTC)", "PROVIENE",
                                "PEDIDO");
                String ruleSalidas = rule(salidaCols);

                Comparator<EventoArribo> ordenLlegadas = Comparator
                                .comparing(EventoArribo::llegada, Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(EventoArribo::vuelo);
                Comparator<EventoSalida> ordenSalidas = Comparator
                                .comparing(EventoSalida::salida, Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(EventoSalida::vuelo);

                final String IND = "	";

                StringBuilder sb = new StringBuilder();
                sb.append("╔════════════════════════════════════════════════════════════╗").append('\n');
                sb.append("║           MOVIMIENTOS POR AEROPUERTO (VISTA GLOBAL)        ║").append('\n');
                sb.append("╚════════════════════════════════════════════════════════════╝").append('\n').append('\n');

                for (Map.Entry<String, AeropuertoResumen> entry : resumenes.entrySet()) {
                        String aeropuerto = entry.getKey();
                        AeropuertoResumen resumen = entry.getValue();
                        resumen.llegadas.sort(ordenLlegadas);
                        resumen.salidas.sort(ordenSalidas);

                        sb.append("== AEROPUERTO ").append(safe(aeropuerto)).append(" ==").append('\n').append('\n');

                        sb.append(IND).append(">> LLEGADAS").append('\n');
                        if (resumen.llegadas.isEmpty()) {
                                sb.append(IND).append("Sin llegadas registradas.").append('\n').append('\n');
                        } else {
                                sb.append(IND).append(headerArribos).append('\n');
                                sb.append(IND).append(ruleArribos).append('\n');
                                for (EventoArribo arribo : resumen.llegadas) {
                                        sb.append(IND).append(row(arriboCols,
                                                        arribo.vuelo(),
                                                        String.format("%,d", arribo.cantidad()),
                                                        ts(arribo.salida()),
                                                        ts(arribo.llegada()),
                                                        safe(arribo.siguiente()),
                                                        safe(arribo.pedido()))).append('\n');
                                }
                                sb.append('\n');
                        }

                        sb.append(IND).append(">> SALIDAS").append('\n');
                        if (resumen.salidas.isEmpty()) {
                                sb.append(IND).append("Sin salidas registradas.").append('\n').append('\n');
                        } else {
                                sb.append(IND).append(headerSalidas).append('\n');
                                sb.append(IND).append(ruleSalidas).append('\n');
                                for (EventoSalida salida : resumen.salidas) {
                                        sb.append(IND).append(row(salidaCols,
                                                        salida.vuelo(),
                                                        String.format("%,d", salida.cantidad()),
                                                        ts(salida.salida()),
                                                        ts(salida.llegada()),
                                                        safe(salida.procedencia()),
                                                        safe(salida.pedido()))).append('\n');
                                }
                                sb.append('\n');
                        }

                        sb.append(IND).append(">> CAPACIDAD DE BODEGA").append('\n');
                        int capacidad = (aeropuertosMap == null) ? 0 : aeropuertosMap.getCapBodega(aeropuerto);
                        if (capacidad <= 0) {
                                sb.append(IND).append("Capacidad no informada para este aeropuerto.").append('\n').append('\n');
                        } else {
                                var segmentos = calcularSegmentos(resumen.periodos);
                                if (segmentos.isEmpty()) {
                                        sb.append(IND).append(String.format(
                                                        "Capacidad declarada: %,d  |  Sin ocupación registrada.%n%n",
                                                        capacidad));
                                } else {
                                        int maxOcupacion = segmentos.stream()
                                                        .mapToInt(SegmentoOcupacion::ocupacion)
                                                        .max()
                                                        .orElse(0);
                                        sb.append(IND).append(String.format(
                                                        "Capacidad declarada: %,d  |  Máxima ocupación: %,d (%.1f%%)%n",
                                                        capacidad, maxOcupacion,
                                                        porcentaje(maxOcupacion, capacidad))).append('\n');
                                        sb.append(IND).append(row(bodegaCols, "DESDE", "HASTA", "CARGA", "%CAP"))
                                                        .append('\n');
                                        sb.append(IND).append(rule(bodegaCols)).append('\n');
                                        for (SegmentoOcupacion seg : segmentos) {
                                                if (seg.ocupacion() <= 0)
                                                        continue;
                                                sb.append(IND).append(row(bodegaCols,
                                                                ts(seg.desde()),
                                                                ts(seg.hasta()),
                                                                String.format("%,d", seg.ocupacion()),
                                                                String.format("%5.1f%%",
                                                                                porcentaje(seg.ocupacion(), capacidad))))
                                                                .append('\n');
                                        }
                                        sb.append('\n');
                                }
                        }

                        sb.append('\n');
                }

                Path path = Paths.get((nombreArchivo == null || nombreArchivo.isBlank())
                                ? "reporteAereopuertos.txt"
                                : nombreArchivo);

                try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                        writer.write(sb.toString());
                        writer.write(System.lineSeparator());
                        writer.write(System.lineSeparator());
                } catch (IOException e) {
                        throw new RuntimeException("No se pudo escribir en " + path.toAbsolutePath(), e);
                }
        }

        // ===== TXT (reemplaza TODO el método y añade los 2 helpers) =====
        private static String formatearReporte(SolucionProgramacion sol) {
                StringBuilder sb = new StringBuilder(64_000);

                var planes = sol.getPlanPorPedido().values();
                int totalPedidos = planes.size();
                int completos = (int) planes.stream().filter(PlanPedido::estaCompleto).count();
                int incompletos = totalPedidos - completos;
                int demandaTotal = planes.stream().mapToInt(PlanPedido::getDemanda).sum();
                int asignadoTotal = planes.stream().mapToInt(PlanPedido::totalAsignado).sum();

                boolean capOK = sol.respetaCapacidadesVuelos();
                boolean sla46OK = sol.respetaSLAConPickupTodos(VENTANA_46H);
                boolean sla48OK = sol.respetaSLA48hTodos();

                sb.append("╔════════════════════════════════════════════════════════════╗\n");
                sb.append("║               RESUMEN DE LA SOLUCIÓN DE PLANEO             ║\n");
                sb.append("╚════════════════════════════════════════════════════════════╝\n");
                sb.append(String.format("Pedidos: %,d  |  Completos: %,d  |  Incompletos: %,d%n",
                                totalPedidos, completos, incompletos));
                sb.append(String.format("Demanda total: %,d  |  Asignado: %,d  (%.1f%%)%n",
                                demandaTotal, asignadoTotal,
                                (demandaTotal == 0 ? 100.0 : 100.0 * asignadoTotal / demandaTotal)));
                sb.append(String.format(
                                "Capacidades vuelos: %s  |  SLA llegada≤46h (todos): %s  |  SLA 48h (todos): %s%n%n",
                                capOK ? "OK" : "FALLA", sla46OK ? "OK" : "FALLA", sla48OK ? "OK" : "FALLA"));

                // ───────────────────────────────────────────────────────────
                // Encabezado PEDIDOS
                // ───────────────────────────────────────────────────────────
                sb.append("== PEDIDOS ==\n");
                String hPedidos = row(
                                new int[] { 5, 6, 15, 10, 10, 7, 15, 15, 6, 6, 18 },
                                "ID", "DST", "CREADO", "DEMANDA", "ASIGN", "%ASIG", "1a_LLEG", "ÚLT_LLEG", "≤46h",
                                "SLA48", "PROGRESO");
                sb.append(hPedidos).append('\n');
                sb.append(rule(new int[] { 5, 6, 15, 10, 10, 7, 15, 15, 6, 6, 18 })).append('\n');

                var orden = planes.stream()
                                .sorted(Comparator.comparing(PlanPedido::getCreadoUtc)
                                                .thenComparing(PlanPedido::getIdPedido))
                                .toList();

                // Subtabla de rutas
                String hRutas = "      " + row(
                                new int[] { 12, 5, 5, 16, 16, 8 },
                                "RUTA(q)", "ORI", "DST", "SALE", "LLEGA", "ASIG");
                String rRutas = "      " + rule(new int[] { 12, 5, 5, 16, 16, 8 });

                for (PlanPedido p : orden) {
                        Instant a = p.primeraLlegada();
                        Instant b = p.ultimaLlegada();
                        boolean ok46 = p.respetaSLAConPickup(VENTANA_46H);
                        boolean ok48 = p.respetaSLA(SLA_48H);
                        int dem = p.getDemanda();
                        int asg = p.totalAsignado();
                        double pct = (dem == 0 ? 100.0 : 100.0 * asg / dem);

                        sb.append(row(
                                        new int[] { 5, 6, 15, 10, 10, 7, 15, 15, 6, 6, 18 },
                                        String.valueOf(p.getIdPedido()),
                                        safe(p.getAeropuertoDestino()),
                                        ts(p.getCreadoUtc()),
                                        String.format("%,d", dem),
                                        String.format("%,d", asg),
                                        String.format("%5.1f%%", pct),
                                        ts(a), ts(b),
                                        ok46 ? "OK" : "NO",
                                        ok48 ? "OK" : "NO",
                                        bar(asg, dem, 16))).append('\n');

                        List<RutaAsignada> rutas = p.getRutas();
                        if (rutas != null && !rutas.isEmpty()) {
                                sb.append(hRutas).append('\n');
                                sb.append(rRutas).append('\n');

                                int idx = 1;
                                for (RutaAsignada r : rutas) {
                                        int q = r.getCantidad();
                                        List<TramoAsignado> tramos = r.getTramos();

                                        if (tramos == null || tramos.isEmpty()) {
                                                sb.append("      ").append(row(
                                                                new int[] { 12, 5, 5, 16, 16, 8 },
                                                                "Ruta" + idx + "(" + q + ")", "-", "-", "-", "-",
                                                                String.format("%,d", q))).append('\n');
                                                idx++;
                                                continue;
                                        }

                                        for (int i = 0; i < tramos.size(); i++) {
                                                TramoAsignado t = tramos.get(i);
                                                var v = t.getVuelo();
                                                String etiqueta = (i == 0) ? ("Ruta" + idx + "(" + q + ")") : "";
                                                sb.append("      ").append(row(
                                                                new int[] { 12, 5, 5, 16, 16, 8 },
                                                                etiqueta,
                                                                v.getOrigen(), v.getDestino(),
                                                                ts(v.getSalidaUtc()), ts(v.getLlegadaUtc()),
                                                                String.format("%,d", q))).append('\n');
                                        }
                                        idx++;
                                }
                        }

                        sb.append('\n'); // separación entre pedidos
                }

                // ───────────────────────────────────────────────────────────
                // VUELOS UTILIZADOS
                // ───────────────────────────────────────────────────────────
                sb.append("== VUELOS UTILIZADOS ==\n");
                String hV = row(new int[] { 7, 7, 16, 16, 10, 10, 10 },
                                "ORI", "DST", "SALIDA", "LLEGADA", "CAPAC", "ASIG", "RESID");
                sb.append(hV).append('\n');
                sb.append(rule(new int[] { 7, 7, 16, 16, 10, 10, 10 })).append('\n');

                var usados = sol.getCargaPorVuelo().getAsignado().entrySet().stream()
                                .filter(e -> e.getValue() != null && e.getValue() > 0)
                                .sorted(Comparator
                                                .comparing((Map.Entry<VueloProgramadoId, Integer> e) -> e.getKey()
                                                                .getLlegadaUtc())
                                                .thenComparing(e -> e.getKey().getOrigen())
                                                .thenComparing(e -> e.getKey().getDestino()))
                                .toList();

                for (var e : usados) {
                        var id = e.getKey();
                        int asig = e.getValue();
                        int cap = sol.getCargaPorVuelo().capacidad(id);
                        int resid = Math.max(0, cap - asig);

                        sb.append(row(new int[] { 7, 7, 16, 16, 10, 10, 10 },
                                        id.getOrigen(), id.getDestino(),
                                        ts(id.getSalidaUtc()), ts(id.getLlegadaUtc()),
                                        String.format("%,d", cap),
                                        String.format("%,d", asig),
                                        String.format("%,d", resid))).append('\n');
                }

                return sb.toString();
        }

        private static final class AeropuertoResumen {
                private final List<EventoArribo> llegadas = new ArrayList<>();
                private final List<EventoSalida> salidas = new ArrayList<>();
                private final List<PeriodoBodega> periodos = new ArrayList<>();
        }

        private record EventoArribo(String vuelo, int cantidad, Instant salida, Instant llegada, String pedido,
                        String siguiente) {
        }

        private record EventoSalida(String vuelo, int cantidad, Instant salida, Instant llegada, String pedido,
                        String procedencia) {
        }

        private record PeriodoBodega(Instant desde, Instant hasta, int cantidad) {
        }

        private record SegmentoOcupacion(Instant desde, Instant hasta, int ocupacion) {
        }

        private static List<SegmentoOcupacion> calcularSegmentos(List<PeriodoBodega> periodos) {
                if (periodos == null || periodos.isEmpty())
                        return List.of();

                Map<Instant, Integer> deltas = new TreeMap<>();
                for (PeriodoBodega p : periodos) {
                        if (p.desde() != null)
                                deltas.merge(p.desde(), p.cantidad(), Integer::sum);
                        if (p.hasta() != null)
                                deltas.merge(p.hasta(), -p.cantidad(), Integer::sum);
                }

                if (deltas.isEmpty())
                        return List.of();

                List<SegmentoOcupacion> segmentos = new ArrayList<>();
                int cargaActual = 0;
                Instant anterior = null;
                for (Map.Entry<Instant, Integer> e : deltas.entrySet()) {
                        Instant instante = e.getKey();
                        if (anterior != null && cargaActual > 0 && anterior.isBefore(instante)) {
                                segmentos.add(new SegmentoOcupacion(anterior, instante, cargaActual));
                        }
                        cargaActual += e.getValue();
                        anterior = instante;
                }
                return segmentos;
        }

        private static double porcentaje(int valor, int capacidad) {
                if (capacidad <= 0)
                        return 0.0;
                return (100.0 * valor) / capacidad;
        }

        // Helpers para filas/reglas con box-drawing
        private static String row(int[] widths, String... cols) {
                StringBuilder b = new StringBuilder();
                b.append('│');
                for (int i = 0; i < widths.length; i++) {
                        String c = (i < cols.length ? safe(cols[i]) : "");
                        // left-align salvo números: si empieza con dígito o contiene % o , lo alineamos
                        // a la derecha
                        boolean numeric = c.matches("^[-+]?\\d[\\d,\\.]*$") || c.contains("%");
                        String fmt = numeric ? "%" + widths[i] + "s" : "%-" + widths[i] + "s";
                        b.append(' ').append(String.format(fmt, c)).append(' ').append('│');
                }
                return b.toString();
        }

        private static String rule(int[] widths) {
                StringBuilder b = new StringBuilder();
                b.append('├');
                for (int i = 0; i < widths.length; i++) {
                        int w = widths[i] + 2; // espacios de padding
                        b.append("─".repeat(w));
                        b.append(i == widths.length - 1 ? '┤' : '┼');
                }
                return b.toString();
        }

        // ===== CSV =====
        private static String csvPedidos(SolucionProgramacion sol) {
                StringBuilder sb = new StringBuilder();
                sb.append("pedido_id,destino,creado_utc,demanda,asignado,completo,primera_llegada,ultima_llegada,ventana_46h_ok,sla_48h_ok\n");
                for (PlanPedido p : sol.getPlanPorPedido().values().stream()
                                .sorted(Comparator.comparing(PlanPedido::getCreadoUtc)
                                                .thenComparing(PlanPedido::getIdPedido))
                                .toList()) {
                        sb.append(String.join(",",
                                        String.valueOf(p.getIdPedido()),
                                        csv(p.getAeropuertoDestino()),
                                        csv(fmt(p.getCreadoUtc())),
                                        String.valueOf(p.getDemanda()),
                                        String.valueOf(p.totalAsignado()),
                                        String.valueOf(p.estaCompleto()),
                                        csv(fmt(p.primeraLlegada())),
                                        csv(fmt(p.ultimaLlegada())),
                                        String.valueOf(p.respetaSLAConPickup(VENTANA_46H)),
                                        String.valueOf(p.respetaSLA(SLA_48H)))).append('\n');
                }
                return sb.toString();
        }

        private static String csvVuelos(SolucionProgramacion sol) {
                StringBuilder sb = new StringBuilder();
                sb.append("origen,destino,salida_utc,llegada_utc,capacidad,asignado,residual\n");
                var todos = new HashSet<>(sol.getCargaPorVuelo().getCapacidad().keySet());
                todos.addAll(sol.getCargaPorVuelo().getAsignado().keySet());

                var orden = todos.stream()
                                .sorted(Comparator.comparing(VueloProgramadoId::getLlegadaUtc)
                                                .thenComparing(VueloProgramadoId::getOrigen)
                                                .thenComparing(VueloProgramadoId::getDestino))
                                .toList();

                for (var id : orden) {
                        int cap = sol.getCargaPorVuelo().capacidad(id);
                        int asg = sol.getCargaPorVuelo().asignado(id);
                        int res = Math.max(0, cap - asg);
                        sb.append(String.join(",",
                                        csv(id.getOrigen()),
                                        csv(id.getDestino()),
                                        csv(fmt(id.getSalidaUtc())),
                                        csv(fmt(id.getLlegadaUtc())),
                                        String.valueOf(cap),
                                        String.valueOf(asg),
                                        String.valueOf(res))).append('\n');
                }
                return sb.toString();
        }

        private static String csv(String s) {
                return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\"";
        }
}
