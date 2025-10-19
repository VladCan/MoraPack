package pe.edu.pucp.morapack.airscheduler.engine.scheduling.service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.PlanPedido;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.SolucionProgramacion;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.VueloProgramadoId;

public final class VerificadorSLA {
    private VerificadorSLA(){}

    /** Mantiene la firma original, pero ahora detalla el motivo en el mensaje. */
    public static void assertBasicos(SolucionProgramacion sol, Duration ventana46h) {
        SLAReport r = diagnosticar(sol, ventana46h);
        if (r.ok()) return;

        StringBuilder sb = new StringBuilder(4096);
        sb.append("Violaciones detectadas:\n");

        // ===== Entrega 100% =====
        if (!r.pedidosIncompletos().isEmpty()) {
            sb.append("• Entrega 100% incumplida: ")
              .append(r.pedidosIncompletos().size()).append(" pedidos; ")
              .append("faltante total=+,")
              .append(String.format("%,d", r.faltanteTotal()))
              .append(" unidades\n");

            for (PedidoIncompleto p : r.topIncompletos(20)) {
                sb.append(String.format("   - Pedido %d  demanda=%,d  asignado=%,d  faltante=%,d  avance=%.1f%%%n",
                        p.idPedido, p.demanda, p.asignado, p.faltante(), p.pctAvance()));
            }
            if (r.pedidosIncompletos().size() > 20) {
                sb.append("   ... ").append(r.pedidosIncompletos().size() - 20).append(" más\n");
            }
        }

        if (!r.capacityViolations().isEmpty()) {
            sb.append("• Capacidad de vuelos violada (").append(r.capacityViolations().size()).append(" vuelos):\n");
            for (CapViolation v : r.topCapacity(20)) {
                sb.append(String.format("   - %s  cap=%,d  asignado=%,d  exceso=+%,d%n",
                        vueloIdStr(v.id), v.capacidad, v.asignado, (v.asignado - v.capacidad)));
            }
            if (r.capacityViolations().size() > 20) {
                sb.append("   ... ").append(r.capacityViolations().size() - 20).append(" más\n");
            }
        }

        if (!r.slaPickupViolations().isEmpty()) {
            sb.append("• SLA llegada≤").append(ventana46h.toHours()).append("h violado (")
              .append(r.slaPickupViolations().size()).append(" pedidos):\n");
            for (SLAViolation v : r.topPickup(20)) {
                String tard = v.tardanzaHoras < 0 ? "sin llegadas" : ("+" + v.tardanzaHoras + "h");
                sb.append(String.format("   - Pedido %d  creado=%s  última_llegada=%s  %s%n",
                        v.idPedido, iso(v.creadoUtc), iso(v.ultimaLlegadaUtc), tard));
            }
            if (r.slaPickupViolations().size() > 20) {
                sb.append("   ... ").append(r.slaPickupViolations().size() - 20).append(" más\n");
            }
        }

        if (!r.sla48Violations().isEmpty()) {
            sb.append("• SLA 48h violado (").append(r.sla48Violations().size()).append(" pedidos):\n");
            for (SLAViolation v : r.top48(20)) {
                String tard = v.tardanzaHoras < 0 ? "sin llegadas" : ("+" + v.tardanzaHoras + "h");
                sb.append(String.format("   - Pedido %d  creado=%s  última_llegada=%s  %s%n",
                        v.idPedido, iso(v.creadoUtc), iso(v.ultimaLlegadaUtc), tard));
            }
            if (r.sla48Violations().size() > 20) {
                sb.append("   ... ").append(r.sla48Violations().size() - 20).append(" más\n");
            }
        }

        throw new IllegalStateException(sb.toString());
    }

    /** Diagnóstico detallado sin lanzar excepción. */
    public static SLAReport diagnosticar(SolucionProgramacion sol, Duration ventana46h) {
        Objects.requireNonNull(sol, "sol");
        Objects.requireNonNull(ventana46h, "ventana46h");

        List<CapViolation> capViol = new ArrayList<>();
        sol.getCargaPorVuelo().getAsignado().forEach((id, asign) -> {
            int cap = sol.getCargaPorVuelo().capacidad(id);
            int asg = (asign == null ? 0 : asign);
            if (asg > cap) capViol.add(new CapViolation(id, cap, asg));
        });
        // Ordenar por exceso desc
        capViol.sort(Comparator.comparingInt((CapViolation v) -> v.asignado - v.capacidad).reversed());

        List<SLAViolation> v46 = new ArrayList<>();
        List<SLAViolation> v48 = new ArrayList<>();
        List<PedidoIncompleto> incompletos = new ArrayList<>();

        for (PlanPedido p : sol.asMap().values()) {
            Instant creado = p.getCreadoUtc();
            Instant ult = p.ultimaLlegada();

            // SLA con pickup (ventana46h)
            Instant limite46 = (creado == null ? null : creado.plus(ventana46h));
            if (limite46 == null || ult == null || ult.isAfter(limite46)) {
                long tardH = (limite46 == null || ult == null) ? -1L
                        : Math.max(0L, java.time.Duration.between(limite46, ult).toHours());
                v46.add(new SLAViolation(p.getIdPedido(), creado, ult, tardH));
            }

            // SLA 48h
            Instant limite48 = (creado == null ? null : creado.plus(Duration.ofHours(48)));
            boolean ok48 = (limite48 != null && ult != null && !ult.isAfter(limite48));
            if (!ok48) {
                long tardH = (limite48 == null || ult == null) ? -1L
                        : Math.max(0L, java.time.Duration.between(limite48, ult).toHours());
                v48.add(new SLAViolation(p.getIdPedido(), creado, ult, tardH));
            }

            // Incompletos (verificación de entrega 100%)
            int dem = p.getDemanda();
            int asg = p.totalAsignado();
            if (asg < dem) {
                incompletos.add(new PedidoIncompleto(p.getIdPedido(), dem, asg));
            }
        }

        // Ordenar SLA por tardanza desc (los peores primero; -1 = sin llegadas → al final)
        Comparator<SLAViolation> byTardDesc = Comparator
                .comparingLong((SLAViolation s) -> s.tardanzaHoras < 0 ? Long.MIN_VALUE : s.tardanzaHoras)
                .reversed();
        v46.sort(byTardDesc);
        v48.sort(byTardDesc);

        // Ordenar incompletos por avance ascendente
        incompletos.sort(Comparator.comparingDouble(PedidoIncompleto::pctAvance));

        return new SLAReport(
                capViol,
                v46,
                v48,
                incompletos
        );
    }

    // ======== Tipos de datos del reporte ========

    public record CapViolation(VueloProgramadoId id, int capacidad, int asignado) {}

    public record SLAViolation(int idPedido, Instant creadoUtc, Instant ultimaLlegadaUtc, long tardanzaHoras) {}

    public record PedidoIncompleto(int idPedido, int demanda, int asignado) {
        public double pctAvance() {
            return demanda == 0 ? 100.0 : (100.0 * asignado / (double) demanda);
        }
        public int faltante() { // <-- NUEVO
            return Math.max(0, demanda - asignado);
        }
    }

    public record SLAReport(
            List<CapViolation> capacityViolations,
            List<SLAViolation> slaPickupViolations,
            List<SLAViolation> sla48Violations,
            List<PedidoIncompleto> pedidosIncompletos
    ) {
        public boolean ok() {
            // Ahora también exige entrega completa
            return capacityViolations.isEmpty()
                && slaPickupViolations.isEmpty()
                && sla48Violations.isEmpty()
                && pedidosIncompletos.isEmpty(); // <-- NUEVO
        }

        // Helpers para mostrar top-N
        public List<CapViolation> topCapacity(int n) { return capacityViolations.subList(0, Math.min(n, capacityViolations.size())); }
        public List<SLAViolation> topPickup(int n)   { return slaPickupViolations.subList(0, Math.min(n, slaPickupViolations.size())); }
        public List<SLAViolation> top48(int n)       { return sla48Violations.subList(0, Math.min(n, sla48Violations.size())); }
        public List<PedidoIncompleto> topIncompletos(int n) { return pedidosIncompletos.subList(0, Math.min(n, pedidosIncompletos.size())); }

        public int faltanteTotal() { // <-- NUEVO
            int sum = 0;
            for (PedidoIncompleto p : pedidosIncompletos) sum += p.faltante();
            return sum;
        }

        // Render legible (por si prefieres imprimir sin excepción)
        public String toMultilineString(Duration ventana46h) {
            String ln = System.lineSeparator();
            StringBuilder sb = new StringBuilder(4096);
            if (ok()) return "SLA OK (sin violaciones)";

            if (!pedidosIncompletos.isEmpty()) { // <-- NUEVO bloque primero
                sb.append("Entrega 100% incumplida: ").append(pedidosIncompletos.size())
                  .append(" pedidos; faltante total=+,").append(String.format("%,d", faltanteTotal())).append(" unidades").append(ln);
                for (PedidoIncompleto p : pedidosIncompletos) {
                    sb.append(String.format(" - Pedido %d  demanda=%,d  asignado=%,d  faltante=%,d  avance=%.1f%%%n",
                            p.idPedido, p.demanda, p.asignado, p.faltante(), p.pctAvance()));
                }
                sb.append(ln);
            }

            if (!capacityViolations.isEmpty()) {
                sb.append("Capacidad de vuelos violada (").append(capacityViolations.size()).append(")").append(ln);
                for (CapViolation v : capacityViolations) {
                    sb.append(String.format(" - %s  cap=%,d  asignado=%,d  exceso=+%,d%n",
                            vueloIdStr(v.id), v.capacidad, v.asignado, (v.asignado - v.capacidad)));
                }
                sb.append(ln);
            }
            if (!slaPickupViolations.isEmpty()) {
                sb.append("SLA llegada≤").append(ventana46h.toHours()).append("h violado (")
                  .append(slaPickupViolations.size()).append(")").append(ln);
                for (SLAViolation v : slaPickupViolations) {
                    sb.append(String.format(" - Pedido %d  creado=%s  última_llegada=%s  tardanza=%s%n",
                            v.idPedido, iso(v.creadoUtc), iso(v.ultimaLlegadaUtc),
                            (v.tardanzaHoras < 0 ? "sin llegadas" : ("+" + v.tardanzaHoras + "h"))));
                }
                sb.append(ln);
            }
            if (!sla48Violations.isEmpty()) {
                sb.append("SLA 48h violado (").append(sla48Violations.size()).append(")").append(ln);
                for (SLAViolation v : sla48Violations) {
                    sb.append(String.format(" - Pedido %d  creado=%s  última_llegada=%s  tardanza=%s%n",
                            v.idPedido, iso(v.creadoUtc), iso(v.ultimaLlegadaUtc),
                            (v.tardanzaHoras < 0 ? "sin llegadas" : ("+" + v.tardanzaHoras + "h"))));
                }
                sb.append(ln);
            }
            return sb.toString();
        }
    }

    // ======== Utils de formato ========

    private static String iso(Instant t) { return t == null ? "-" : java.time.format.DateTimeFormatter.ISO_INSTANT.format(t); }

    private static String vueloIdStr(VueloProgramadoId v) {
        if (v == null) return "-";
        String o = v.getOrigen() == null ? "" : v.getOrigen();
        String d = v.getDestino() == null ? "" : v.getDestino();
        String s = v.getSalidaUtc() == null ? "" : java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(java.time.ZoneOffset.UTC).format(v.getSalidaUtc());
        return o + d + s;
    }
}
