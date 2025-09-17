package pe.edu.pucp.morapack.airscheduler.scheduling.domain.model;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Solución completa: planes por pedido + carga agregada por vuelo. */
@Getter
@Builder
public class SolucionProgramacion {

    @Singular("plan")
    private final Map<Integer, PlanPedido> planPorPedido;

    private final CargaPorVuelo cargaPorVuelo;

    public SolucionProgramacion(Map<Integer, PlanPedido> planPorPedido, CargaPorVuelo cargaPorVuelo) {
        this.planPorPedido = Map.copyOf(planPorPedido); // hacemos copia inmutable
        this.cargaPorVuelo = cargaPorVuelo; // si es mutable, considerar copia defensiva
    }

    public SolucionProgramacion(SolucionProgramacion otra) {
        this.planPorPedido = Map.copyOf(otra.planPorPedido); // inmutable
        this.cargaPorVuelo = otra.cargaPorVuelo; // si es mutable, deberías copiar también
    }

    public PlanPedido planDe(int idPedido) {
        return planPorPedido.get(idPedido);
    }

    public boolean respetaCapacidadesVuelos() {
        return cargaPorVuelo.getAsignado().entrySet().stream()
                .allMatch(e -> e.getValue() <= cargaPorVuelo.capacidad(e.getKey()));
    }

    // @Deprecated
    // public boolean respetaVentana2hTodos(Duration ventana2h) {
    // return planPorPedido.values().stream().allMatch(p ->
    // p.respetaVentana2h(ventana2h));
    // }
    public boolean respetaSLAConPickupTodos(Duration pickupTime) {
        // 46h de llegada para dar 2h de recogida y cumplir SLA 48h
        Duration maxLlegada = Duration.ofHours(46);
        return planPorPedido.values().stream().allMatch(p -> p.respetaSLAConPickup(maxLlegada));
    }

    public boolean respetaSLA48hTodos() {
        return planPorPedido.values().stream().allMatch(p -> p.respetaSLA(Duration.ofHours(48)));
    }

    public Map<Integer, PlanPedido> asMap() {
        return Collections.unmodifiableMap(planPorPedido);
    }

    public List<Integer> pedidosIncompletos() {
        return planPorPedido.values().stream()
                .filter(p -> !p.estaCompleto())
                .map(PlanPedido::getIdPedido)
                .toList();
    }

    // helpers para reporte
    private static final java.time.format.DateTimeFormatter TS_SHORT = java.time.format.DateTimeFormatter
            .ofPattern("dd MMM HH:mm'Z'", java.util.Locale.US)
            .withZone(java.time.ZoneOffset.UTC);

    private static String ts(java.time.Instant t) {
        return (t == null ? "-" : TS_SHORT.format(t));
    }

    private static String safe(String s) {
        return (s == null ? "-" : s);
    }

    private static String bar(int val, int tot, int width) {
        if (tot <= 0)
            return "[" + "=".repeat(width) + "]";
        int fill = Math.min(width, (int) Math.round((double) val * width / tot));
        return "[" + "#".repeat(fill) + ".".repeat(width - fill) + "]";
    }

    public void imprimir(Instant reloj, String titulo) {
        System.out.println("=".repeat(110));
        System.out.println("REPORTE AL CORTE".concat(titulo == null ? "" : " • " + titulo));
        System.out.println("Corte (UTC): " + ts(reloj));
        System.out.println("=".repeat(110));

        int total = planPorPedido.size();
        int sumDem = 0, sumEnt = 0;
        int nFull = 0, nPartial = 0, nZero = 0;

        record Row(int id, String dst, Instant creado, int dem, int ent, Instant ult) {
        }

        var pedidosOrden = planPorPedido.values().stream()
                .sorted(Comparator.comparing(PlanPedido::getCreadoUtc).thenComparing(PlanPedido::getIdPedido))
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

            Row r = new Row(p.getIdPedido(), p.getDestinoIcao(), p.getCreadoUtc(), dem, ent, ult);
            if (ent >= dem) {
                nFull++;
                rowsFull.add(r);
            } else if (ent > 0) {
                nPartial++;
                rowsPart.add(r);
            } else {
                nZero++;
                rowsZero.add(r);
            }
        }

        double pct = (sumDem == 0 ? 0.0 : (100.0 * sumEnt / sumDem));
        String sep = "-".repeat(110);
        System.out.printf("Pedidos: %d  |  Entregados: %d  |  Parciales: %d  |  Pendientes: %d%n",
                total, nFull, nPartial, nZero);
        System.out.printf("Unidades entregadas≤T: %,d / %,d  (%.1f%%)%n", sumEnt, sumDem, pct);
        System.out.println(sep);

        String header = String.format(
                "%-4s %-5s %-13s %8s %8s %8s %-13s  %s%n",
                "ID", "DST", "CREADO", "DEMANDA", "ENTREG", "%AVANCE", "ÚLT≤T", "PROGRESO");

        java.util.function.Consumer<Row> render = r -> {
            double av = (r.dem == 0 ? 100.0 : (100.0 * r.ent / r.dem));
            System.out.printf("%-4d %-5s %-13s %,8d %,8d %7.1f%% %-13s  %s%n",
                    r.id, safe(r.dst), ts(r.creado), r.dem, r.ent, av, ts(r.ult),
                    bar(r.ent, r.dem, 22));
        };

        System.out.println("\n>> ENTREGADOS (100% hasta el corte)  [" + nFull + "]");
        System.out.println(header + sep);
        if (rowsFull.isEmpty())
            System.out.println("(sin registros)");
        else
            rowsFull.forEach(render);

        System.out.println("\n>> PARCIALES (con llegadas pero incompletos)  [" + nPartial + "]");
        System.out.println(header + sep);
        if (rowsPart.isEmpty())
            System.out.println("(sin registros)");
        else {
            rowsPart.sort(Comparator.<Row>comparingDouble(r -> (r.dem == 0 ? 1.0 : (r.ent * 1.0 / r.dem))).reversed()
                    .thenComparing(r -> r.creado));
            rowsPart.forEach(render);
        }

        System.out.println("\n>> PENDIENTES (0 entregado hasta el corte)  [" + nZero + "]");
        System.out.println(header + sep);
        if (rowsZero.isEmpty())
            System.out.println("(sin registros)");
        else
            rowsZero.forEach(render);

        System.out.println("=".repeat(110));
    }

    /** Suma de cantidades de tramos cuya llegada es <= reloj. */
    private int entregadoHasta(PlanPedido plan, Instant reloj) {
        if (plan.getTramos() == null)
            return 0;
        return plan.getTramos().stream()
                .filter(t -> t.getLlegadaUtc() != null && !t.getLlegadaUtc().isAfter(reloj))
                .mapToInt(TramoAsignado::getCantidad)
                .sum();
    }

    /** Última llegada (máxima) <= reloj; null si no hay llegadas hasta el reloj. */
    private Instant ultimaLlegadaHasta(PlanPedido plan, Instant reloj) {
        if (plan.getTramos() == null)
            return null;
        return plan.getTramos().stream()
                .map(TramoAsignado::getLlegadaUtc)
                .filter(l -> l != null && !l.isAfter(reloj))
                .max(Instant::compareTo)
                .orElse(null);
    }
}
