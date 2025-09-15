package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed;

import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosEdge;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosNode;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed.model.OrderAssignment;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed.model.PathArc;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed.model.SeedResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class SeedReporter {
    private SeedReporter(){}

    /** Imprime TODO el resultado del seed, agrupando WAITs contiguos por aeropuerto. */
    public static void printAll(SeedResult seed) {
        System.out.printf("Seed: pedidos=%d, cumplidos=%d, pendientes=%d, fill=%.2f%%%n",
                seed.totalPedidos(), seed.pedidosCumplidos(), seed.pedidosPendientes(), seed.fillRate()*100.0);

        for (OrderAssignment a : seed.assignments()) {
            System.out.printf("%nPedido #%d  destino=%s  qty=%d  status=%s  slot=%s%n",
                    a.pedidoId(), a.destino(), a.cantidad(),
                    a.fulfilled() ? "CUMPLIDO" : "PENDIENTE",
                    formatInstant(a.slotChosen()));

            if (!a.fulfilled()) continue;

            var compressed = compressWaits(a.path());
            int i = 0;
            for (var row : compressed) {
                // Construye "label@hora" sólo si hay hora
                String from = at(row.fromIcao, nz(row.fromTime));
                String to   = at(row.toIcao,   nz(row.toTime));
                System.out.printf("  [%03d] %-6s  %s  ->  %s   used=%d   cap=%d   %s%n",
                        i++,
                        row.type,
                        from,
                        to,
                        row.usedFlow, row.capacity,
                        row.extra);
            }
        }
    }

    /** Exporta TODO a CSV detallado (una fila por arco usado; sin compresión). */
    public static void exportCsv(SeedResult seed, Path out) throws IOException {
        Files.createDirectories(out.getParent() != null ? out.getParent() : out.toAbsolutePath().getParent());
        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            w.write(String.join(",",
                    "pedido_id","destino","qty","fulfilled","slot_utc",
                    "edge_type","from_icao","from_time_utc","to_icao","to_time_utc",
                    "used_flow","edge_capacity"));
            w.newLine();

            for (OrderAssignment a : seed.assignments()) {
                if (!a.fulfilled()) {
                    w.write(String.join(",",
                            String.valueOf(a.pedidoId()), a.destino(),
                            String.valueOf(a.cantidad()), "false", "",
                            "","","","","","0","0"));
                    w.newLine();
                    continue;
                }
                for (PathArc pa : a.path()) {
                    VuelosEdge e = pa.edge();
                    VuelosNode f = e.getFrom();
                    VuelosNode t = e.getTo();
                    w.write(String.join(",",
                            String.valueOf(a.pedidoId()),
                            a.destino(),
                            String.valueOf(a.cantidad()),
                            "true",
                            nz(formatInstant(a.slotChosen())),
                            e.getType().name(),
                            nz(label(f)),
                            nz(formatInstant(f.getTimeUtc())),
                            nz(label(t)),
                            nz(formatInstant(t.getTimeUtc())),
                            String.valueOf(pa.flowPushed()),
                            String.valueOf(e.getCapacity())));
                    w.newLine();
                }
            }
        }
        System.out.println("CSV exportado: " + out.toAbsolutePath());
    }

    // ----------------- Helpers de compresión de WAITs -----------------

    private static final class Row {
        String type, fromIcao, toIcao, fromTime, toTime, extra;
        int usedFlow, capacity;
    }

    /** Junta WAITs contiguos en la misma estación (mismo ICAO) y consecutivos en el tiempo. */
    private static List<Row> compressWaits(List<PathArc> path) {
        List<Row> out = new ArrayList<>();
        Row agg = null; // acumulador de WAIT

        for (var pa : path) {
            var e = pa.edge();
            var from = e.getFrom();
            var to   = e.getTo();
            String type = e.getType().name();

            if ("WAIT".equals(type)) {
                String curFromIcao = label(from); // <-- usa símbolo Ω cuando aplica
                String curToIcao   = label(to);
                String curFromTime = nz(formatInstant(from.getTimeUtc()));
                String curToTime   = nz(formatInstant(to.getTimeUtc()));

                if (agg == null) {
                    // abrir agregador
                    agg = new Row();
                    agg.type = "WAIT";
                    agg.fromIcao = curFromIcao;
                    agg.toIcao   = curToIcao;
                    agg.fromTime = curFromTime;
                    agg.toTime   = curToTime;
                    agg.usedFlow = pa.flowPushed();
                    agg.capacity = e.getCapacity();
                } else {
                    // si el WAIT sigue en la misma estación y el tiempo continúa, extendemos
                    boolean sameStation = agg.toIcao.equals(curFromIcao) && agg.fromIcao.equals(curFromIcao);
                    boolean contiguous  = true; // en TEG el siguiente WAIT empieza donde terminó el anterior
                    if (sameStation && contiguous) {
                        agg.toIcao = curToIcao;
                        agg.toTime = curToTime;
                        // capacity y usedFlow se mantienen (mismo envío/estación)
                    } else {
                        // cerramos el bloque anterior y abrimos otro
                        agg.extra = prettyWaitExtra(agg.fromTime, agg.toTime);
                        out.add(agg);
                        agg = new Row();
                        agg.type = "WAIT";
                        agg.fromIcao = curFromIcao;
                        agg.toIcao   = curToIcao;
                        agg.fromTime = curFromTime;
                        agg.toTime   = curToTime;
                        agg.usedFlow = pa.flowPushed();
                        agg.capacity = e.getCapacity();
                    }
                }
            } else {
                // cerrar WAIT abierto antes de emitir un FLIGHT/SUPPLY
                if (agg != null) {
                    agg.extra = prettyWaitExtra(agg.fromTime, agg.toTime);
                    out.add(agg);
                    agg = null;
                }
                Row r = new Row();
                r.type = type;
                r.fromIcao = label(from); // <-- usa símbolo Ω cuando aplica
                r.fromTime = nz(formatInstant(from.getTimeUtc()));
                r.toIcao   = label(to);
                r.toTime   = nz(formatInstant(to.getTimeUtc()));
                r.usedFlow = pa.flowPushed();
                r.capacity = e.getCapacity();
                r.extra    = prettyEdgeExtra(e);
                out.add(r);
            }
        }
        // cerrar último WAIT si quedó abierto
        if (agg != null) {
            agg.extra = prettyWaitExtra(agg.fromTime, agg.toTime);
            out.add(agg);
        }
        return out;
    }

    private static String prettyWaitExtra(String fromIso, String toIso) {
        if (fromIso == null || fromIso.isEmpty() || toIso == null || toIso.isEmpty()) return "WAIT";
        try {
            var a = Instant.parse(fromIso);
            var b = Instant.parse(toIso);
            long mins = Duration.between(a, b).toMinutes();
            return "WAIT total=" + mins + "m";
        } catch (Exception ignore) { return "WAIT"; }
    }

    // ----------------- Helpers genéricos -----------------

    private static String formatInstant(Instant i) { return i == null ? "" : i.toString(); }

    /** Etiqueta amigable: Ω para super-source, ICAO normal en otros casos. */
    private static String label(VuelosNode n) {
        if (n == null) return "";
        if (n.isSuperSource()) return "Ω";
        return nz(n.getIcao());
    }

    /** Devuelve "label@iso" sólo si hay iso; si no, sólo "label". */
    private static String at(String label, String iso) {
        if (label == null) label = "";
        if (iso == null || iso.isEmpty()) return label;
        return label + "@" + iso;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String prettyEdgeExtra(VuelosEdge e) {
        return switch (e.getType()) {
            case SUPPLY -> "Ω→" + label(e.getTo());
            case WAIT   -> "WAIT";
            case FLIGHT -> (e.getVuelo() != null ? ("FLIGHT#" + e.getVuelo().getId()) : "FLIGHT");
        };
    }
}