package pe.edu.pucp.morapack.airscheduler.scheduling.adapters.io;

import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.*;

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
        private static final Locale LOCALE_TS = Locale.US; // "Sep", "Oct", etc.
        /** Formato compacto y legible en UTC para consola. */
        private static final DateTimeFormatter TS_SHORT = DateTimeFormatter.ofPattern("dd MMM HH:mm'Z'", LOCALE_TS)
                        .withZone(ZoneOffset.UTC);

        // ========= Reglas de SLA =========
        private static final Duration VENTANA_46H = Duration.ofHours(46); // llegada ≤ 46h por pickup 2h
        private static final Duration SLA_48H = Duration.ofHours(48);

        // ========= Helpers generales =========
        private static String ts(Instant t) {
                return (t == null ? "-" : TS_SHORT.format(t));
        }

        private static String fmt(Instant t) {
                return (t == null ? "-" : ISO.format(t));
        } // Para CSV

        private static String safe(String s) {
                return (s == null ? "-" : s);
        }

        private static String bar(int val, int tot, int width) {
                if (tot <= 0)
                        return "[" + "=".repeat(width) + "]";
                int fill = Math.min(width, (int) Math.round((double) val * width / Math.max(1, tot)));
                return "[" + "#".repeat(fill) + ".".repeat(width - fill) + "]";
        }

        // ===== API principal =====

        /** Imprime un resumen legible a consola. */
        public static void imprimirEnConsola(SolucionProgramacion sol) {
                System.out.println(formatearReporte(sol));
        }

        /** Guarda TXT y CSV en 'out/'. Devuelve el directorio base. */
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

        // ===== TXT bonito =====
        private static String formatearReporte(SolucionProgramacion sol) {
                StringBuilder sb = new StringBuilder(48_000);

                var planes = sol.getPlanPorPedido().values();
                int totalPedidos = planes.size();
                int completos = (int) planes.stream().filter(PlanPedido::estaCompleto).count();
                int incompletos = totalPedidos - completos;
                int demandaTotal = planes.stream().mapToInt(PlanPedido::getDemanda).sum();
                int asignadoTotal = planes.stream().mapToInt(PlanPedido::totalAsignado).sum();

                boolean capOK = sol.respetaCapacidadesVuelos();
                boolean sla46OK = sol.respetaSLAConPickupTodos(VENTANA_46H);
                boolean sla48OK = sol.respetaSLA48hTodos();

                // === RESUMEN ===
                sb.append("=== SOLUCIÓN DE PROGRAMACIÓN ===\n");
                sb.append(String.format("Pedidos: %d  |  Completos: %d  |  Incompletos: %d%n",
                                totalPedidos, completos, incompletos));
                sb.append(String.format("Demanda total: %,d  |  Asignado: %,d  (%.1f%%)%n",
                                demandaTotal, asignadoTotal,
                                (demandaTotal == 0 ? 100.0 : 100.0 * asignadoTotal / demandaTotal)));
                sb.append(String.format(
                                "Capacidades vuelos: %s  |  SLA llegada≤46h (todos): %s  |  SLA 48h (todos): %s%n",
                                capOK ? "OK" : "FALLA", sla46OK ? "OK" : "FALLA", sla48OK ? "OK" : "FALLA"));
                sb.append("================================\n\n");

                // === PEDIDOS ===
                sb.append("== PEDIDOS ==\n");
                // Cabecera principal de pedidos
                String header = String.format(
                                "%-4s %-5s %-13s %8s %8s %6s %-13s %-13s %-5s %-5s  %s%n",
                                "ID", "DST", "CREADO", "DEMANDA", "ASIGN", "%ASIG", "1a_LLEG", "ÚLT_LLEG", "≤46h",
                                "SLA48", "PROGRESO");
                sb.append(header);
                sb.append("-".repeat(header.length())).append('\n');

                var orden = planes.stream()
                                .sorted(Comparator.comparing(PlanPedido::getCreadoUtc)
                                                .thenComparing(PlanPedido::getIdPedido))
                                .toList();

                // Subtabla de vuelos por pedido (encabezado reutilizable)
                final String subHeader = String.format("      %-4s %-4s %-13s %-13s %6s%n",
                                "ORI", "DST", "SALE", "LLEGA", "ASIG");
                final String subSep = "      " + "-".repeat(subHeader.length() - 6) + "\n";

                for (PlanPedido p : orden) {
                        Instant a = p.primeraLlegada();
                        Instant b = p.ultimaLlegada();
                        boolean ok46 = p.respetaSLAConPickup(VENTANA_46H);
                        boolean ok48 = p.respetaSLA(SLA_48H);
                        int dem = p.getDemanda();
                        int asg = p.totalAsignado();
                        double pct = (dem == 0 ? 100.0 : 100.0 * asg / dem);

                        // Fila del pedido
                        sb.append(String.format(
                                        "%-4d %-5s %-13s %,8d %,8d %5.1f%% %-13s %-13s %-5s %-5s  %s%n",
                                        p.getIdPedido(), safe(p.getDestinoIcao()), ts(p.getCreadoUtc()),
                                        dem, asg, pct, ts(a), ts(b),
                                        ok46 ? "OK" : "NO", ok48 ? "OK" : "NO", bar(asg, dem, 16)));

                        // Subtabla de vuelos (si hay tramos)
                        List<TramoAsignado> tramos = p.getTramos();
                        if (tramos != null && !tramos.isEmpty()) {
                                sb.append(subHeader);
                                sb.append(subSep);
                                for (TramoAsignado t : tramos) {
                                        var v = t.getVuelo();
                                        sb.append(String.format("      %-4s %-4s %-13s %-13s %,6d%n",
                                                        v.getOrigen(), v.getDestino(),
                                                        ts(v.getSalidaUtc()), ts(v.getLlegadaUtc()),
                                                        t.getCantidad()));
                                }
                                sb.append('\n');
                        }
                }

                // === VUELOS UTILIZADOS ===
                sb.append("== VUELOS UTILIZADOS ==\n");
                String hv = String.format("%-6s %-6s %-13s %-13s %8s %8s %8s%n",
                                "ORI", "DST", "SALIDA", "LLEGADA", "CAPAC", "ASIG", "RESID");

                sb.append(hv);
                sb.append("-".repeat(hv.length())).append('\n');

                var usados = sol.getCargaPorVuelo().getAsignado().entrySet().stream()
                                .filter(e -> e.getValue() != null && e.getValue() > 0)
                                .sorted(Comparator
                                                .comparing((Map.Entry<VueloProgramadoId, Integer> e) -> e.getKey()
                                                                .getSalidaUtc())
                                                .thenComparing(e -> e.getKey().getOrigen())
                                                .thenComparing(e -> e.getKey().getDestino()))
                                .toList();

                for (var e : usados) {
                        var id = e.getKey();
                        int asig = e.getValue();
                        int cap = sol.getCargaPorVuelo().capacidad(id);
                        int resid = Math.max(0, cap - asig);
                        sb.append(String.format("%-6s %-6s %-13s %-13s %,8d %,8d %,8d%n",
                                        id.getOrigen(), id.getDestino(),
                                        ts(id.getSalidaUtc()), ts(id.getLlegadaUtc()),
                                        cap, asig, resid));
                }

                return sb.toString();
        }

        // ===== CSV =====

        private static String csvPedidos(SolucionProgramacion sol) {
                StringBuilder sb = new StringBuilder();
                sb.append("pedido_id,destino,creado_utc,demanda,asignado,completo,primera_llegada,ultima_llegada,ventana_46h_ok,sla_48h_ok\n");
                for (var p : sol.getPlanPorPedido().values().stream()
                                .sorted(Comparator.comparing(PlanPedido::getCreadoUtc)
                                                .thenComparing(PlanPedido::getIdPedido))
                                .toList()) {

                        sb.append(String.join(",",
                                        String.valueOf(p.getIdPedido()),
                                        csv(p.getDestinoIcao()),
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
                // queremos una fila por vuelo (aunque asig=0 también aporta visibilidad)
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

        // ===== helpers =====
        private static String csv(String s) {
                return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\"";
        }
}
