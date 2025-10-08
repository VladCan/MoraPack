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

    public CargaPorVuelo getCargaPorVuelo() {
        return cargaPorVuelo;
    }


    public SolucionProgramacion(SolucionProgramacion otra) {
        // Copiar profundo planPorPedido
        this.planPorPedido = new HashMap<>();
        for (Map.Entry<Integer, PlanPedido> entry : otra.planPorPedido.entrySet()) {
            this.planPorPedido.put(entry.getKey(), new PlanPedido(entry.getValue()));
        }

        // Copiar profundo cargaPorVuelo
        this.cargaPorVuelo = new CargaPorVuelo(otra.cargaPorVuelo);

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
        PrintWriter out = new PrintWriter(System.out, true);
        imprimirA(reloj, titulo, out);
        out.flush();
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
            int ent = asignadoTotal(p);
            Instant ult = ultimaLlegadaHasta(p, reloj);

            sumDem += dem;
            sumEnt += Math.min(dem, ent);   

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
        out.printf("Unidades asignadas: %,d / %,d  (%.1f%%)%n", sumEnt, sumDem, pct);
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



    //IMPRIMIR FITNESS












    public record FitnessParams(
        double wT, double wU, double wC, double wS,   // pesos
        double bigCapPerUnit,                         // penalización por UNIDAD excedida de capacidad
        double bigSlaPerHour,                         // penalización por HORA de tardanza
        double Tref,                                  // normalizador de tramos (evita 0)
        java.time.Duration slaMax                     // “deadline” relativo: p.getCreadoUtc() + slaMax
) {
    public static FitnessParams defaults() {
        return new FitnessParams(
                /*wT*/ 0.05, /*wU*/ 0.20, /*wC*/ 0.75, /*wS*/ 0.00,
                /*bigCapPerUnit*/ 1.0e5,
                /*bigSlaPerHour*/ 1.0e5,
                /*Tref*/ 60.0,                 // ajústalo antes de correr (ver notas abajo)
                /*slaMax*/ java.time.Duration.ofHours(48)
        );
    }
}

public static final class FitnessResult {
    public final double fitness;
    public final long T; public final double Tnorm;
    public final double U; public final double C;
    public final long splitsTotales;
    public final long vuelos; public final long capTotal; public final long asignadoClamped;
    public final long capOverUnits; public final int capOverVuelos;
    public final long slaLateHours; public final int slaLatePedidos;

    // contribuciones
    public final double termT, termU, termC, termS, penCap, penSLA;

    FitnessResult(
            double fitness, long T, double Tnorm, double U, double C, long splitsTotales,
            long vuelos, long capTotal, long asignadoClamped,
            long capOverUnits, int capOverVuelos, long slaLateHours, int slaLatePedidos,
            double termT, double termU, double termC, double termS, double penCap, double penSLA
    ) {
        this.fitness = fitness;
        this.T = T; this.Tnorm = Tnorm; this.U = U; this.C = C; this.splitsTotales = splitsTotales;
        this.vuelos = vuelos; this.capTotal = capTotal; this.asignadoClamped = asignadoClamped;
        this.capOverUnits = capOverUnits; this.capOverVuelos = capOverVuelos;
        this.slaLateHours = slaLateHours; this.slaLatePedidos = slaLatePedidos;
        this.termT = termT; this.termU = termU; this.termC = termC; this.termS = termS;
        this.penCap = penCap; this.penSLA = penSLA;
    }
}

/** Calcula y MUESTRA el fitness con desglose claro. */
public void imprimirFitness(Instant corte, FitnessParams P, PrintWriter out) {
    out.print(formatearFitness(corte, P));
    out.flush();
}

/** Muestra el fitness usando parámetros personalizados en la consola. */
public void imprimirFitness(Instant corte, FitnessParams P) {
    System.out.print(formatearFitness(corte, P));
    System.out.flush();
}

/** Atajo: imprime a consola con los defaults. */
public void imprimirFitness(Instant corte) {
    imprimirFitness(corte, FitnessParams.defaults());
}

private String formatearFitness(Instant corte, FitnessParams P) {
    FitnessResult r = evaluarFitness(corte, P);
    String ln = System.lineSeparator();
    StringBuilder sb = new StringBuilder(512);
    java.util.Formatter fmt = new java.util.Formatter(sb, java.util.Locale.US);
    try {
        sb.append("╔══════════════════════════════════════════════════════════════════════╗").append(ln);
        sb.append("║                         REPORTE DE FITNESS (ALNS)                    ║").append(ln);
        sb.append("╚══════════════════════════════════════════════════════════════════════╝").append(ln);
        sb.append("Corte (UTC): ").append(ts(corte)).append(ln);
        sb.append("-".repeat(74)).append(ln);

        fmt.format("Pedidos: %,d | Cumplimiento C = %.4f (%.1f%% por unidades)%n",
                planPorPedido.size(), r.C, 100.0 * r.C);
        fmt.format("Vuelos:  %,d | Capacidad total = %,d | Carga usada (clamped) = %,d | U = %.4f (%.1f%%)%n",
                r.vuelos, r.capTotal, r.asignadoClamped, r.U, 100.0 * r.U);
        fmt.format("Tramos usados T = %,d | Tnorm = %.4f (Tref = %.2f)%n", r.T, r.Tnorm, P.Tref);
        fmt.format("Splits totales = %,d%n", r.splitsTotales);

        sb.append("-".repeat(74)).append(ln);
        sb.append("Violaciones:").append(ln);
        fmt.format("  Capacidad: %,d unidades en exceso en %,d vuelos%n",
                r.capOverUnits, r.capOverVuelos);
        fmt.format("  SLA: %,d pedidos con tardanza acumulada de %,d horas%n",
                r.slaLatePedidos, r.slaLateHours);

        sb.append("-".repeat(74)).append(ln);
        sb.append("Contribuciones al Fitness (minimización):").append(ln);
        fmt.format("  wC*(1-C) = %.6f%n", r.termC);
        fmt.format("  wU*(1-U) = %.6f%n", r.termU);
        fmt.format("  wT*Tnorm = %.6f%n", r.termT);
        fmt.format("  wS*Splits = %.6f%n", r.termS);
        fmt.format("  PenCap    = %.6f%n", r.penCap);
        fmt.format("  PenSLA    = %.6f%n", r.penSLA);
        sb.append("-".repeat(74)).append(ln);
        fmt.format("=> FITNESS FINAL = %.6f%n", r.fitness);
        sb.append("=".repeat(74)).append(ln);
    } finally {
        fmt.close();
    }
    return sb.toString();
}

// ======= Núcleo del cálculo =======

public FitnessResult evaluarFitness(Instant corte, FitnessParams P) {
    Objects.requireNonNull(corte, "corte");
    Objects.requireNonNull(P, "params");

    // 1) Métricas de tramos (T) y splits
    long T = 0L;
    long splitsTotales = 0L;

    // Para C (cumplimiento por unidades)
    long demandTotal = 0L, entregadoTotal = 0L;

    // Para SLA tardanza
    long slaLateHours = 0L;
    int  slaLatePedidos = 0;

    for (PlanPedido plan : planPorPedido.values()) {
        var rutas = plan.getRutas();
        if (rutas != null && !rutas.isEmpty()) {
            T += rutas.stream().mapToInt(r -> r.getTramos() == null ? 0 : r.getTramos().size()).sum();
            // “partes” = cantidad de rutas con al menos 1 tramo
            long partes = rutas.stream().filter(r -> r.getTramos() != null && !r.getTramos().isEmpty()).count();
            if (partes > 1) splitsTotales += (partes - 1);
        }

        int dem = plan.getDemanda();
        int ent = asignadoTotal(plan);      // ya implementado en tu clase
        demandTotal += dem;
        entregadoTotal += Math.min(dem, ent);

        // tardanza respecto a (creado + slaMax)
        Instant ult = ultimaLlegadaHasta(plan, corte);
        if (ult != null) {
            Instant ddl = plan.getCreadoUtc().plus(P.slaMax());
            if (ult.isAfter(ddl)) {
                long h = java.time.Duration.between(ddl, ult).toHours();
                slaLateHours += Math.max(0L, h);
                slaLatePedidos++;
            }
        } else if (dem > 0) {
            // sin llegadas hasta corte → cuenta como tarde hasta el corte (conservador)
            Instant ddl = plan.getCreadoUtc().plus(P.slaMax());
            if (corte.isAfter(ddl)) {
                long h = java.time.Duration.between(ddl, corte).toHours();
                slaLateHours += Math.max(0L, h);
                slaLatePedidos++;
            }
        }
    }

    double C = (demandTotal == 0) ? 1.0 : (double) entregadoTotal / (double) demandTotal;
    double Tnorm = (P.Tref() <= 0.0) ? T : (T / P.Tref());

    // 2) Métricas de utilización (U) y capacidad excedida usando CargaPorVuelo
    long capTotal = 0L, asignadoClamped = 0L, capOverUnits = 0L;
    int capOverVuelos = 0;

    for (var e : cargaPorVuelo.getAsignado().entrySet()) {
        VueloProgramadoId vueloId = e.getKey();
        int asign = e.getValue();
        int cap = cargaPorVuelo.capacidad(vueloId);
        capTotal += cap;
        asignadoClamped += Math.min(asign, cap);
        if (asign > cap) {
            capOverVuelos++;
            capOverUnits += (asign - cap);
        }
    }
    long vuelos = cargaPorVuelo.getAsignado().size();
    double U = (capTotal == 0) ? 0.0 : (double) asignadoClamped / (double) capTotal;

    // 3) Armar contribuciones y fitness
    double termC = P.wC() * (1.0 - C);
    double termU = P.wU() * (1.0 - U);
    double termT = P.wT() * Tnorm;
    double termS = P.wS() * ((double) splitsTotales / Math.max(1, planPorPedido.size()));

    double penCap = P.bigCapPerUnit() * capOverUnits;      // por UNIDAD excedida
    double penSLA = P.bigSlaPerHour() * slaLateHours;      // por HORA de tardanza

    double fitness = termC + termU + termT + termS + penCap + penSLA;

    return new FitnessResult(
            fitness, T, Tnorm, U, C, splitsTotales,
            vuelos, capTotal, asignadoClamped,
            capOverUnits, capOverVuelos, slaLateHours, slaLatePedidos,
            termT, termU, termC, termS, penCap, penSLA
    );
}
/** Suma de cantidades asignadas en el plan (sin filtrar por el corte). */
private static int asignadoTotal(PlanPedido plan) {
    if (plan.getRutas() == null) return 0;
    return plan.getRutas().stream()
            .mapToInt(RutaAsignada::getCantidad)
            .sum();
}











    // ============================================== //
    // ============================================== //
    // ============================================== //
    // ============================================== //
    // ============================================== //
    // ============================================== //
    // ============================================== //
    // ============================================== //
    // ============================================== //
    // ====== REPORTE: capacidad por vuelo en ventana ======
    public void imprimirCapacidadVuelosEnVentana(Instant inicio, Instant fin, String nombreArchivo) {
        final var TS = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
                .withZone(java.time.ZoneOffset.UTC);

        record Row(String org, Instant sal, String dst, Instant lla, int cap, int asg) {}

        List<Row> filas = new ArrayList<>();
        for (var e : cargaPorVuelo.getAsignado().entrySet()) {
            VueloProgramadoId id = e.getKey();
            int asignado = e.getValue() == null ? 0 : e.getValue();
            if (id == null) continue;

            Instant sal = id.getSalidaUtc();
            if (sal == null || sal.isBefore(inicio) || !sal.isBefore(fin)) continue; // filtro por ventana

            int cap = cargaPorVuelo.capacidad(id);
            filas.add(new Row(id.getOrigen(), sal, id.getDestino(), id.getLlegadaUtc(), cap, asignado));
        }

        filas.sort(Comparator.comparing(Row::sal).thenComparing(Row::org).thenComparing(Row::dst));

        int violaciones = 0;
        long sumCap = 0, sumAsg = 0;

        java.nio.file.Path path = java.nio.file.Path.of(
                (nombreArchivo == null || nombreArchivo.isBlank())
                        ? "out/reporteCapacidadVuelos.txt" : nombreArchivo);

        try {
            java.nio.file.Files.createDirectories(path.getParent());
            try (var bw = java.nio.file.Files.newBufferedWriter(
                    path, java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                 var out = new java.io.PrintWriter(bw)) {

                out.println("=".repeat(110));
                out.println("REPORTE DE CAPACIDAD POR VUELO");
                out.println("Ventana: [" + TS.format(inicio) + " — " + TS.format(fin) + ")");
                out.println("=".repeat(110));

                String header = String.format("%-22s  %-9s  %-22s  %-14s  %-12s  %s%n",
                        "SALIDA (UTC)", "ORIG→DEST", "LLEGADA (UTC)", "CAPACIDAD", "OCUPACIÓN", "ESTADO");
                out.print(header);
                out.println("-".repeat(header.length() - 1));

                for (Row r : filas) {
                    sumCap += Math.max(0, r.cap);
                    sumAsg += Math.max(0, r.asg);
                    boolean over = r.asg > r.cap;
                    if (over) violaciones++;

                    String estado = obtenerSaturacion(r.cap, r.asg);
                    if (over) estado = estado + "  **EXCESO +" + (r.asg - r.cap) + "**";

                    // construir el string de ocupación con separador de miles
                    String occStr = String.format("%,d/%,d", r.asg, r.cap);

                    //        SALIDA                 ORIG→DEST        LLEGADA            CAPACIDAD     OCUPACIÓN (string)  ESTADO
                    out.printf("%-22s  %-9s  %-22s  %,14d  %-12s  %s%n",
                            TS.format(r.sal),
                            r.org + "→" + r.dst,
                            TS.format(r.lla == null ? r.sal : r.lla),
                            r.cap,
                            occStr,     // <- %s sin coma
                            estado);
                }

                double pctGlobal = (sumCap <= 0) ? 0.0 : (100.0 * sumAsg / sumCap);
                out.println("-".repeat(header.length() - 1));
                out.printf("Vuelos en ventana: %,d | Violaciones: %,d%n", filas.size(), violaciones);
                out.printf("Ocupación global: %,d / %,d  (%.1f%%)%n", sumAsg, sumCap, pctGlobal);
                out.println("=".repeat(110));
            }
        } catch (Exception ex) {
            throw new RuntimeException("No se pudo escribir el reporte en " + path.toAbsolutePath(), ex);
        }
    }


    /** Semáforo de saturación (ocupación/capacidad) como el de bodegas. */
    private static String obtenerSaturacion(int capacidad, int ocupacion) {
        if (capacidad <= 0) return "NA";
        double pct = (ocupacion * 100.0) / capacidad;
        long pctRed = Math.round(pct);
        if (pct < 33.33)       return "Disponible ✅ " + pctRed + "%";
        else if (pct < 66.66)  return "Limitado ⚠️ " + pctRed + "%";
        else if (pct < 99.99)  return "Saturado ❌ " + pctRed + "%";
        else if (pctRed == 100) return "Capacidad máxima 🚫🚫🚫";
        else                   return "Exceso total ☠️☠️☠️";
    }





}
