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
 *  - out/<prefijo>-reporte.txt
 *  - out/<prefijo>-pedidos.csv
 *  - out/<prefijo>-vuelos.csv
 */
public final class ImpresorSolucion {
    private ImpresorSolucion(){}

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    //private static final Duration VENTANA_2H = Duration.ofHours(2);
    private static final Duration VENTANA_46H = Duration.ofHours(46);
    private static final Duration SLA_48H    = Duration.ofHours(48);

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
                    : prefijo + "-" + STAMP.format(presenteUtc == null ? Instant.now() : presenteUtc);

            Path outDir = Paths.get("out");
            Files.createDirectories(outDir);

            Path txt = outDir.resolve(base + "-reporte.txt");
            Path csvPedidos = outDir.resolve(base + "-pedidos.csv");
            Path csvVuelos  = outDir.resolve(base + "-vuelos.csv");

            Files.writeString(txt, formatearReporte(sol), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(csvPedidos, csvPedidos(sol), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(csvVuelos, csvVuelos(sol), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

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
        StringBuilder sb = new StringBuilder(32_768);

        // Resumen
        var planes = sol.getPlanPorPedido().values();
        int totalPedidos = planes.size();
        int completos = (int) planes.stream().filter(PlanPedido::estaCompleto).count();
        int incompletos = totalPedidos - completos;
        int demandaTotal = planes.stream().mapToInt(PlanPedido::getDemanda).sum();
        int asignadoTotal = planes.stream().mapToInt(PlanPedido::totalAsignado).sum();

        sb.append("=== SOLUCIÓN DE PROGRAMACIÓN ===\n");
        sb.append(String.format("Pedidos: %d | Completos: %d | Incompletos: %d%n", totalPedidos, completos, incompletos));
        sb.append(String.format("Demanda total: %,d | Asignado: %,d%n", demandaTotal, asignadoTotal));
        sb.append(String.format("Capacidades OK: %s | Ventana 2h OK: %s | SLA 48h OK: %s%n",
                sol.respetaCapacidadesVuelos() ? "sí" : "NO",
                sol.respetaSLAConPickupTodos(VENTANA_46H) ? "sí" : "NO",
                sol.respetaSLA48hTodos() ? "sí" : "NO"));
        sb.append("================================\n\n");

        // Pedidos (tabla)
        sb.append("== PEDIDOS ==\n");
        String header = String.format("%-6s %-6s %-20s %10s %10s %-5s %-20s %-20s %-5s %-7s%n",
                "ID", "DST", "CREADO_UTC", "DEMANDA", "ASIGNADO", "OK?", "1a_LLEGADA", "últ_LLEGADA", "2hOK", "SLA48");
        sb.append(header);
        sb.append(repeat('-', header.length())).append('\n');

        var orden = planes.stream()
                .sorted(Comparator.comparing(PlanPedido::getCreadoUtc).thenComparing(PlanPedido::getIdPedido))
                .toList();

        for (var p : orden) {
            Instant a = p.primeraLlegada();
            Instant b = p.ultimaLlegada();
            boolean ok46h = p.respetaSLAConPickup(VENTANA_46H);
            boolean okSLA = p.respetaSLA(SLA_48H);
            String linea = String.format("%-6d %-6s %-20s %10d %10d %-5s %-20s %-20s %-5s %-7s%n",
                    p.getIdPedido(), safe(p.getDestinoIcao()),
                    fmt(p.getCreadoUtc()),
                    p.getDemanda(), p.totalAsignado(),
                    p.estaCompleto() ? "sí" : "NO",
                    fmt(a), fmt(b),
                    ok46h ? "sí" : "NO",
                    okSLA ? "sí" : "NO");
            sb.append(linea);

            // Detalle de tramos
            for (TramoAsignado t : p.getTramos()) {
                var v = t.getVuelo();
                sb.append(String.format("   • %s→%s | sale:%s llega:%s | asignado:%d%n",
                        v.getOrigen(), v.getDestino(),
                        fmt(v.getSalidaUtc()), fmt(v.getLlegadaUtc()),
                        t.getCantidad()));
            }
        }
        sb.append('\n');

        // Vuelos con carga (tabla)
        sb.append("== VUELOS UTILIZADOS ==\n");
        String hv = String.format("%-6s %-6s %-20s %-20s %10s %10s %10s%n",
                "ORI", "DST", "SALIDA_UTC", "LLEGADA_UTC", "CAPAC", "ASIG", "RESID");
        sb.append(hv);
        sb.append(repeat('-', hv.length())).append('\n');

        var usados = sol.getCargaPorVuelo().getAsignado().entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .sorted(Comparator.comparing((Map.Entry<VueloProgramadoId, Integer> e) -> e.getKey().getLlegadaUtc())
                        .thenComparing(e -> e.getKey().getOrigen())
                        .thenComparing(e -> e.getKey().getDestino()))
                .toList();

        for (var e : usados) {
            var id = e.getKey();
            int asig = e.getValue();
            int cap = sol.getCargaPorVuelo().capacidad(id);
            int res = Math.max(0, cap - asig);
            sb.append(String.format("%-6s %-6s %-20s %-20s %10d %10d %10d%n",
                    id.getOrigen(), id.getDestino(), fmt(id.getSalidaUtc()), fmt(id.getLlegadaUtc()), cap, asig, res));
        }

        return sb.toString();
    }

    // ===== CSV =====

    private static String csvPedidos(SolucionProgramacion sol) {
        StringBuilder sb = new StringBuilder();
        sb.append("pedido_id,destino,creado_utc,demanda,asignado,completo,primera_llegada,ultima_llegada,ventana_46h_ok,sla_48h_ok\n");
        for (var p : sol.getPlanPorPedido().values().stream()
                .sorted(Comparator.comparing(PlanPedido::getCreadoUtc).thenComparing(PlanPedido::getIdPedido))
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
                    String.valueOf(p.respetaSLA(SLA_48H))
            )).append('\n');

            // filas detalle por tramo (opcional: descomenta si quieres un CSV aparte de tramos)
            // for (var t : p.getTramos()) { ... }
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
                    String.valueOf(res)
            )).append('\n');
        }
        return sb.toString();
    }

    // ===== helpers =====

    private static String fmt(Instant t) { return t == null ? "" : ISO.format(t); }
    private static String safe(String s)  { return s == null ? "" : s; }
    private static String csv(String s)   { return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\""; }
    private static String repeat(char c, int n) { return String.valueOf(c).repeat(Math.max(0, n)); }
}
