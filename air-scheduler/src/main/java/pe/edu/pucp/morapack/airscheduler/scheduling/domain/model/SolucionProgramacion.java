package pe.edu.pucp.morapack.airscheduler.scheduling.domain.model;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Solución completa: planes por pedido + carga agregada por vuelo. */
@Getter
@Builder
public class SolucionProgramacion {

    @Singular("plan")
    private final Map<Integer, PlanPedido> planPorPedido;

    private final CargaPorVuelo cargaPorVuelo;

    public SolucionProgramacion(Map<Integer, PlanPedido> planPorPedido, CargaPorVuelo cargaPorVuelo) {
        this.planPorPedido = new HashMap<>(planPorPedido);  // <-- mutable
        this.cargaPorVuelo = cargaPorVuelo;
    }


    public SolucionProgramacion(SolucionProgramacion otra) {
        // Copiar profundo planPorPedido
        this.planPorPedido = new HashMap<>();
        for (Map.Entry<Integer, PlanPedido> entry : otra.planPorPedido.entrySet()) {
            this.planPorPedido.put(entry.getKey(), new PlanPedido(entry.getValue()));
        }

        // Copiar profundo cargaPorVuelo
        this.cargaPorVuelo = new CargaPorVuelo();
        for (Map.Entry<String, Map<String, Integer>> entry : otra.cargaPorVuelo.entrySet()) {
            this.cargaPorVuelo.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
    }




    public PlanPedido planDe(int idPedido) { return planPorPedido.get(idPedido); }

    public boolean respetaCapacidadesVuelos() {
        return cargaPorVuelo.getAsignado().entrySet().stream()
                .allMatch(e -> e.getValue() <= cargaPorVuelo.capacidad(e.getKey()));
    }

    /**
     * Verifica SLA con tiempo de pickup: si el SLA global es 48h y el pickup es 2h,
     * entonces la última llegada debe ocurrir en <= 46h desde la creación.
     */
    public boolean respetaSLAConPickupTodos(Duration pickupTime) {
        Duration limiteLlegada = pickupTime;
        return planPorPedido.values().stream().allMatch(p -> {
            Instant ult = p.ultimaLlegada();
            if (ult == null) return false; // sin llegadas, no cumple
            Instant tope = p.getCreadoUtc().plus(limiteLlegada);
            return !ult.isAfter(tope);
        });
    }

    public boolean respetaSLA48hTodos() {
        return planPorPedido.values().stream().allMatch(p -> p.respetaSLA(Duration.ofHours(48)));
    }

    public Map<Integer, PlanPedido> asMap() { return Collections.unmodifiableMap(planPorPedido); }

    public List<Integer> pedidosIncompletos() {
        return planPorPedido.values().stream()
                .filter(p -> !p.estaCompleto())
                .map(PlanPedido::getIdPedido)
                .toList();
    }

    // ===== helpers de reporte =====
    private static final java.time.format.DateTimeFormatter TS_SHORT = java.time.format.DateTimeFormatter
            .ofPattern("dd MMM HH:mm'Z'", java.util.Locale.US)
            .withZone(java.time.ZoneOffset.UTC);

    private static String ts(java.time.Instant t) { return (t == null ? "-" : TS_SHORT.format(t)); }
    private static String safe(String s) { return (s == null ? "-" : s); }

    private static String bar(int val, int tot, int width) {
        if (tot <= 0) return "[" + "=".repeat(width) + "]";
        int fill = Math.min(width, (int) Math.round((double) val * width / tot));
        return "[" + "#".repeat(fill) + ".".repeat(width - fill) + "]";
    }

    /** Mantiene el comportamiento existente: imprime en consola. */
    public void imprimir(Instant reloj, String titulo) {
        try (PrintWriter out = new PrintWriter(System.out, true)) {
            imprimirA(reloj, titulo, out);
        }
    }

    /**
     * NUEVO: imprime en archivo en modo APPEND (no sobreescribe).
     * El segundo parámetro es el NOMBRE DEL ARCHIVO, no el título.
     */
    public void imprimirEnArchivo(Instant reloj, String nombreArchivo) {
        try (var writer = Files.newBufferedWriter(
                Path.of(nombreArchivo),
                StandardOpenOption.CREATE,   // crea si no existe
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND    // apendear al final
        );
             PrintWriter out = new PrintWriter(writer)) {
            // No uses el nombre del archivo como título del reporte
            imprimirA(reloj, null, out);
            // añade un salto extra para separar ejecuciones
            out.println();
        } catch (Exception e) {
            throw new RuntimeException("No se pudo escribir el reporte en " + nombreArchivo, e);
        }
    }

    /** Lógica común de render: escribe hacia el PrintWriter indicado. */
    private void imprimirA(Instant reloj, String titulo, PrintWriter out) {
        out.println("=".repeat(110));
        out.println("REPORTE AL CORTE".concat(titulo == null ? "" : " • " + titulo));
        out.println("Corte (UTC): " + ts(reloj));
        out.println("=".repeat(110));

        int total = planPorPedido.size();
        int sumDem = 0, sumEnt = 0;
        int nFull = 0, nPartial = 0, nZero = 0;

        record Row(int id, String dst, Instant creado, int dem, int ent, Instant ult) {}

        var pedidosOrden = planPorPedido.values().stream()
                .sorted(Comparator.comparing(PlanPedido::getCreadoUtc)
                        .thenComparing(PlanPedido::getIdPedido))
                .toList();

        List<Row> rowsFull = new ArrayList<>();
        List<Row> rowsPart = new ArrayList<>();
        List<Row> rowsZero = new ArrayList<>();

        for (PlanPedido p : pedidosOrden) {
            int dem = p.getDemanda();
            int ent = entregadoHasta(p, reloj);
            Instant ult = ultimaLlegadaHasta(p, reloj);

            sumDem += dem;
            sumEnt += ent;

            Row r = new Row(p.getIdPedido(), p.getAeropuertoDestino(), p.getCreadoUtc(), dem, ent, ult);
            if (ent >= dem) {
                nFull++; rowsFull.add(r);
            } else if (ent > 0) {
                nPartial++; rowsPart.add(r);
            } else {
                nZero++; rowsZero.add(r);
            }
        }

        double pct = (sumDem == 0 ? 0.0 : (100.0 * sumEnt / sumDem));
        String sep = "-".repeat(110);
        out.printf("Pedidos: %d  |  Entregados: %d  |  Parciales: %d  |  Pendientes: %d%n",
                total, nFull, nPartial, nZero);
        out.printf("Unidades entregadas≤T: %,d / %,d  (%.1f%%)%n", sumEnt, sumDem, pct);
        out.println(sep);

        String header = String.format(
                "%-4s %-5s %-13s %8s %8s %8s %-13s  %s%n",
                "ID", "DST", "CREADO", "DEMANDA", "ENTREG", "%AVANCE", "ÚLT≤T", "PROGRESO");

        java.util.function.Consumer<Row> render = r -> {
            double av = (r.dem == 0 ? 100.0 : (100.0 * r.ent / r.dem));
            out.printf("%-4d %-5s %-13s %,8d %,8d %7.1f%% %-13s  %s%n",
                    r.id, safe(r.dst), ts(r.creado), r.dem, r.ent, av, ts(r.ult),
                    bar(r.ent, r.dem, 22));
        };

        out.println("\n>> ENTREGADOS (100% hasta el corte)  [" + nFull + "]");
        out.println(header + sep);
        if (rowsFull.isEmpty()) out.println("(sin registros)"); else rowsFull.forEach(render);

        out.println("\n>> PARCIALES (con llegadas pero incompletos)  [" + nPartial + "]");
        out.println(header + sep);
        if (rowsPart.isEmpty()) out.println("(sin registros)");
        else {
            rowsPart.sort(Comparator.<Row>comparingDouble(r -> (r.dem == 0 ? 1.0 : (r.ent * 1.0 / r.dem))).reversed()
                    .thenComparing(r -> r.creado));
            rowsPart.forEach(render);
        }

        out.println("\n>> PENDIENTES (0 entregado hasta el corte)  [" + nZero + "]");
        out.println(header + sep);
        if (rowsZero.isEmpty()) out.println("(sin registros)"); else rowsZero.forEach(render);

        out.println("=".repeat(110));
    }

    /** Suma de cantidades de rutas cuyas llegadas (de su último tramo) son <= reloj. */
    private int entregadoHasta(PlanPedido plan, Instant reloj) {
        if (plan.getRutas() == null) return 0;
        return plan.getRutas().stream()
                .filter(r -> {
                    Instant u = r.ultimaLlegada();
                    return u != null && !u.isAfter(reloj);
                })
                .mapToInt(RutaAsignada::getCantidad)
                .sum();
    }

    /** Última llegada (máxima) <= reloj; null si no hay llegadas hasta el reloj. */
    private Instant ultimaLlegadaHasta(PlanPedido plan, Instant reloj) {
        if (plan.getRutas() == null) return null;
        return plan.getRutas().stream()
                .map(RutaAsignada::ultimaLlegada)
                .filter(l -> l != null && !l.isAfter(reloj))
                .max(Instant::compareTo)
                .orElse(null);
    }
}
