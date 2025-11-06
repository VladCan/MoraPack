package pe.edu.pucp.morapack.airscheduler.engine.scheduling.service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosMap; // NUEVO
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Vuelo;     // NUEVO
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.PlanPedido;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.RutaAsignada;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.TramoAsignado;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.SolucionProgramacion;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.VueloProgramadoId;

public final class VerificadorSLA {
    private VerificadorSLA(){}

    // =========================
    // API pública
    // =========================

    /** Compatibilidad: no valida bodegas (no hay mapa de capacidades). */
    public static void assertBasicos(SolucionProgramacion sol, Duration ventana46h) {
        SLAReport r = diagnosticar(sol, ventana46h, null, null);
        if (r.ok()) return;
        throw new IllegalStateException(renderError(sol, ventana46h, r));
    }

    /** Valida también capacidades de bodega por aeropuerto (recomendado). */
    public static void assertBasicos(SolucionProgramacion sol, Duration ventana46h, AeropuertosMap aeropuertosMap) {
        SLAReport r = diagnosticar(sol, ventana46h, aeropuertosMap, null);
        if (r.ok()) return;
        throw new IllegalStateException(renderError(sol, ventana46h, r));
    }

    /** NUEVO: valida rutas reales contra el archivo original de vuelos (VuelosMap). */
    public static void assertBasicos(SolucionProgramacion sol, Duration ventana46h, VuelosMap vuelosMap) {
        SLAReport r = diagnosticar(sol, ventana46h, null, vuelosMap);
        if (r.ok()) return;
        throw new IllegalStateException(renderError(sol, ventana46h, r));
    }

    /** Opcional: valida bodegas + rutas reales. */
    public static void assertBasicos(SolucionProgramacion sol, Duration ventana46h, AeropuertosMap aeropuertosMap, VuelosMap vuelosMap) {
        SLAReport r = diagnosticar(sol, ventana46h, aeropuertosMap, vuelosMap);
        if (r.ok()) return;
        throw new IllegalStateException(renderError(sol, ventana46h, r));
    }

    /** Compatibilidad: no valida bodegas ni rutas reales. */
    public static SLAReport diagnosticar(SolucionProgramacion sol, Duration ventana46h) {
        return diagnosticar(sol, ventana46h, null, null);
    }

    /// Hecho por Fabián
    /// Método0 que únicamente valida el SLA de 46hrs (luego refactor para 46/70hrs)
    public static List<SLAViolation> verificarSLA46hEstricto(SolucionProgramacion sol,
                                                             Duration ventana46h,
                                                             Instant presenteUtc) {
        Objects.requireNonNull(sol, "sol");
        Objects.requireNonNull(ventana46h, "ventana46h");
        Objects.requireNonNull(presenteUtc, "presenteUtc");

        List<SLAViolation> violaciones = new ArrayList<>();

        for (PlanPedido p : sol.asMap().values()) {
            if (p == null) continue;

            Instant creado = p.getCreadoUtc();
            if (creado == null) continue; // si no hay createdAt, lo omitimos

            Instant limite46 = creado.plus(ventana46h);
            boolean tieneRutas = (p.getRutas() != null && !p.getRutas().isEmpty());
            Instant ultimaLlegada = p.ultimaLlegada(); // null si no hay rutas

            boolean viola;
            long tardH;

            if (!tieneRutas || ultimaLlegada == null) {
                // No planificado aún: solo viola si ya pasaron 46h desde creación
                viola = presenteUtc.isAfter(limite46);
                tardH = viola ? Math.max(0L, Duration.between(limite46, presenteUtc).toHours()) : 0L;
            } else {
                // Planificado: verifica la última llegada
                viola = ultimaLlegada.isAfter(limite46);
                tardH = viola ? Math.max(0L, Duration.between(limite46, ultimaLlegada).toHours()) : 0L;
            }

            if (viola) {
                violaciones.add(new SLAViolation(p.getIdPedido(), creado, ultimaLlegada, tardH));
            }
        }

        return violaciones;
    }


    /** NUEVO: diagnostica todo (bodegas opcional, rutas reales opcional). */
    public static SLAReport diagnosticar(SolucionProgramacion sol,
                                         Duration ventana46h,
                                         AeropuertosMap aeropuertosMap,
                                         VuelosMap vuelosMap) {
        Objects.requireNonNull(sol, "sol");
        Objects.requireNonNull(ventana46h, "ventana46h");

        // 1) Capacidad de vuelos (igual que antes)
        List<CapViolation> capViol = new ArrayList<>();
        sol.getCargaPorVuelo().getAsignado().forEach((id, asign) -> {
            int cap = sol.getCargaPorVuelo().capacidad(id);
            int asg = (asign == null ? 0 : asign);
            if (asg > cap) capViol.add(new CapViolation(id, cap, asg));
        });
        capViol.sort(Comparator.comparingInt((CapViolation v) -> v.asignado - v.capacidad).reversed());

        // 2) SLA 46h y 48h + incompletos (igual que antes)
        List<SLAViolation> v46 = new ArrayList<>();
        List<SLAViolation> v48 = new ArrayList<>();
        List<PedidoIncompleto> incompletos = new ArrayList<>();

        for (PlanPedido p : sol.asMap().values()) {
            Instant creado = p.getCreadoUtc();
            Instant ult = p.ultimaLlegada();

            Instant limite46 = (creado == null ? null : creado.plus(ventana46h));
            if (limite46 == null || ult == null || ult.isAfter(limite46)) {
                long tardH = (limite46 == null || ult == null) ? -1L
                        : Math.max(0L, java.time.Duration.between(limite46, ult).toHours());
                v46.add(new SLAViolation(p.getIdPedido(), creado, ult, tardH));
            }

            Instant limite48 = (creado == null ? null : creado.plus(Duration.ofHours(48)));
            boolean ok48 = (limite48 != null && ult != null && !ult.isAfter(limite48));
            if (!ok48) {
                long tardH = (limite48 == null || ult == null) ? -1L
                        : Math.max(0L, java.time.Duration.between(limite48, ult).toHours());
                v48.add(new SLAViolation(p.getIdPedido(), creado, ult, tardH));
            }

            int dem = p.getDemanda();
            int asg = p.totalAsignado();
            if (asg < dem) {
                incompletos.add(new PedidoIncompleto(p.getIdPedido(), dem, asg));
            }
        }

        Comparator<SLAViolation> byTardDesc = Comparator
                .comparingLong((SLAViolation s) -> s.tardanzaHoras < 0 ? Long.MIN_VALUE : s.tardanzaHoras)
                .reversed();
        v46.sort(byTardDesc);
        v48.sort(byTardDesc);
        incompletos.sort(Comparator.comparingDouble(PedidoIncompleto::pctAvance));

        // 3) Capacidad de bodega por aeropuerto (opcional)
        List<BodegaViolation> bodegaViolations = List.of();
        if (aeropuertosMap != null) {
            bodegaViolations = detectarViolacionesBodega(sol, ventana46h, aeropuertosMap);
        }

        // 4) NUEVO: Verificación de rutas reales contra el archivo original
        List<RutaViolation> rutaViolations = List.of();
        if (vuelosMap != null) {
            rutaViolations = verificarRutasRealesOD(sol, vuelosMap);
        }

        return new SLAReport(
                capViol,
                v46,
                v48,
                incompletos,
                bodegaViolations,
                rutaViolations // NUEVO
        );
    }

    // =========================
    // Construcción de error legible
    // =========================

    private static String renderError(SolucionProgramacion sol, Duration ventana46h, SLAReport r) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("Violaciones detectadas:\n");

        // Entrega 100%
        if (!r.pedidosIncompletos().isEmpty()) {
            sb.append("• Entrega 100% incumplida: ")
              .append(r.pedidosIncompletos().size()).append(" pedidos; faltante total=")
              .append(String.format("%,d", r.faltanteTotal())).append(" unidades\n");
            for (PedidoIncompleto p : r.topIncompletos(20)) {
                sb.append(String.format("   - Pedido %d  demanda=%,d  asignado=%,d  faltante=%,d  avance=%.1f%%%n",
                        p.idPedido, p.demanda, p.asignado, p.faltante(), p.pctAvance()));
            }
            if (r.pedidosIncompletos().size() > 20) {
                sb.append("   ... ").append(r.pedidosIncompletos().size() - 20).append(" más\n");
            }
        }

        // Capacidad de vuelos
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

        // SLA 46h
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

        // SLA 48h
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

        // Bodega por aeropuerto
        if (!r.bodegaViolations().isEmpty()) {
            sb.append("• Capacidad de bodega violada (")
              .append(r.bodegaViolations().size()).append(" segmentos):\n");
            for (BodegaViolation b : r.topBodega(20)) {
                sb.append(String.format("   - %s  [%s — %s)  ocup=%s  cap=%,d  exceso=+%,d%n",
                        b.aeropuerto, iso(b.desde), iso(b.hasta),
                        String.format("%,d", b.ocupacion), b.capacidad, Math.max(0, b.ocupacion - b.capacidad)));
            }
            if (r.bodegaViolations().size() > 20) {
                sb.append("   ... ").append(r.bodegaViolations().size() - 20).append(" más\n");
            }
        }

        // NUEVO: Rutas inválidas / inexistentes
        if (!r.rutaViolations().isEmpty()) {
            sb.append("• Rutas inválidas (").append(r.rutaViolations().size()).append(" halladas):\n");
            for (RutaViolation rv : r.topRuta(30)) {
                sb.append(String.format(
                        "   - Pedido %d  ruta#%d tramo#%d  causa=%s  vuelo=%s  detalle=%s%n",
                        rv.idPedido, rv.idxRuta, rv.idxTramo, rv.causa, vueloIdStr(rv.vuelo),
                        rv.detalle == null ? "-" : rv.detalle
                ));
            }
            if (r.rutaViolations().size() > 30) {
                sb.append("   ... ").append(r.rutaViolations().size() - 30).append(" más\n");
            }
        }

        return sb.toString();
    }

    // =========================
    // Cálculo de bodegas (igual)
    // =========================

    /** Detecta segmentos [t_i, t_{i+1}) donde la ocupación supera la capacidad declarada del aeropuerto. */
    private static List<BodegaViolation> detectarViolacionesBodega(SolucionProgramacion sol,
                                                                   Duration ventana46h,
                                                                   AeropuertosMap aeropuertosMap) {
        final Duration pickup = Duration.ofHours(48).minus(ventana46h);

        Map<String, TreeMap<Instant, Integer>> deltasPorAeropuerto = new HashMap<>();

        for (PlanPedido plan : sol.asMap().values()) {
            List<RutaAsignada> rutas = plan.getRutas();
            if (rutas == null || rutas.isEmpty()) continue;

            for (RutaAsignada ruta : rutas) {
                List<TramoAsignado> tramos = ruta.getTramos();
                if (tramos == null || tramos.isEmpty()) continue;

                for (int i = 0; i < tramos.size(); i++) {
                    TramoAsignado tramo = tramos.get(i);
                    VueloProgramadoId v = tramo.getVuelo();
                    if (v == null) continue;
                    int q = tramo.getCantidad();

                    // ORIGEN: [esperaInicioOrigen, salida)
                    Instant esperaInicioOrigen = (i == 0)
                            ? plan.getCreadoUtc()
                            : (tramos.get(i - 1).getVuelo() != null ? tramos.get(i - 1).getVuelo().getLlegadaUtc() : null);
                    Instant esperaFinOrigen = v.getSalidaUtc();
                    if (esperaInicioOrigen != null && esperaFinOrigen != null && !esperaFinOrigen.isBefore(esperaInicioOrigen)) {
                        addDelta(deltasPorAeropuerto, v.getOrigen(), esperaInicioOrigen, q);
                        addDelta(deltasPorAeropuerto, v.getOrigen(), esperaFinOrigen, -q);
                    }

                    // DESTINO:
                    Instant esperaInicioDestino = v.getLlegadaUtc();
                    Instant esperaFinDestino;
                    if (i + 1 < tramos.size()) {
                        VueloProgramadoId next = tramos.get(i + 1).getVuelo();
                        esperaFinDestino = (next != null) ? next.getSalidaUtc() : null;
                    } else {
                        esperaFinDestino = (esperaInicioDestino != null) ? esperaInicioDestino.plus(pickup) : null;
                    }
                    if (esperaInicioDestino != null && esperaFinDestino != null && !esperaFinDestino.isBefore(esperaInicioDestino)) {
                        addDelta(deltasPorAeropuerto, v.getDestino(), esperaInicioDestino, q);
                        addDelta(deltasPorAeropuerto, v.getDestino(), esperaFinDestino, -q);
                    }
                }
            }
        }

        List<BodegaViolation> violaciones = new ArrayList<>();
        for (Map.Entry<String, TreeMap<Instant, Integer>> e : deltasPorAeropuerto.entrySet()) {
            String ap = e.getKey();
            TreeMap<Instant, Integer> deltas = e.getValue();
            int capacidad = aeropuertosMap.getCapBodega(ap);

            int ocup = 0;
            Instant prev = null;
            for (Map.Entry<Instant, Integer> d : deltas.entrySet()) {
                Instant t = d.getKey();
                if (prev != null && t.isAfter(prev)) {
                    if (ocup > capacidad) {
                        violaciones.add(new BodegaViolation(ap, prev, t, capacidad, ocup));
                    }
                }
                ocup += d.getValue();
                prev = t;
            }
        }

        violaciones.sort(Comparator
                .comparingInt((BodegaViolation b) -> Math.max(0, b.ocupacion - b.capacidad)).reversed()
                .thenComparing(b -> b.aeropuerto)
                .thenComparing(b -> b.desde));

        return violaciones;
    }

    // =========================
    // NUEVO: Verificación de rutas reales (O-D)
    // =========================

    private static List<RutaViolation> verificarRutasRealesOD(SolucionProgramacion sol, VuelosMap mapa) {
        List<RutaViolation> out = new ArrayList<>();

        for (PlanPedido plan : sol.asMap().values()) {
            int idPedido = plan.getIdPedido();
            List<RutaAsignada> rutas = plan.getRutas();
            if (rutas == null || rutas.isEmpty()) continue;

            for (int idxRuta = 0; idxRuta < rutas.size(); idxRuta++) {
                RutaAsignada ruta = rutas.get(idxRuta);
                List<TramoAsignado> tramos = (ruta == null ? null : ruta.getTramos());
                if (tramos == null || tramos.isEmpty()) {
                    out.add(new RutaViolation(idPedido, idxRuta, -1, "RUTA_VACIA", null, "La ruta no contiene tramos"));
                    continue;
                }

                VueloProgramadoId prev = null;
                for (int idxTramo = 0; idxTramo < tramos.size(); idxTramo++) {
                    TramoAsignado t = tramos.get(idxTramo);
                    VueloProgramadoId v = (t == null ? null : t.getVuelo());

                    if (v == null) {
                        out.add(new RutaViolation(idPedido, idxRuta, idxTramo, "SEGMENTO_SIN_VUELO", null, "Tramo sin referencia de vuelo"));
                        prev = null;
                        continue;
                    }

                    String o = v.getOrigen();
                    String d = v.getDestino();
                    if (o == null || o.isBlank() || d == null || d.isBlank()) {
                        out.add(new RutaViolation(idPedido, idxRuta, idxTramo, "VUELO_SIN_CODIGOS", v, "Origen/Destino nulos o vacíos"));
                    } else {
                        // Origen debe existir en el catálogo
                        if (!mapa.origenes().contains(o)) {
                            out.add(new RutaViolation(idPedido, idxRuta, idxTramo, "ORIGEN_NO_EN_CATALOGO", v, "No hay vuelos con origen=" + o));
                        } else {
                            // Debe existir algún vuelo O->D en el archivo
                            if (!existeOD(mapa, o, d)) {
                                out.add(new RutaViolation(idPedido, idxRuta, idxTramo, "TRAMO_SIN_OFERTA_OD", v, "No existe vuelo " + o + "→" + d + " en el archivo"));
                            }
                        }

                        // (Opcional futuro) Validar horario local exacto contra archivo:
                        // int hhmmDepLocal = hhmmLocal(v.getSalidaUtc(), o, mapa); // requiere exponer GMT o helper en VuelosMap
                        // int hhmmArrLocal = hhmmLocal(v.getLlegadaUtc(), d, mapa);
                        // if (!existeODHhmm(mapa, o, d, hhmmDepLocal, hhmmArrLocal)) {
                        //     out.add(new RutaViolation(idPedido, idxRuta, idxTramo, "HORARIO_NO_COINCIDE", v,
                        //         "No hay un vuelo " + o + "→" + d + " con HH:mm dep/arr del archivo"));
                        // }
                    }

                    // Conexiones espaciales/temporales (lo que ya hacíamos)
                    if (prev != null) {
                        String dPrev = prev.getDestino();
                        String oAct = v.getOrigen();
                        if (dPrev == null || oAct == null || !dPrev.equals(oAct)) {
                            out.add(new RutaViolation(idPedido, idxRuta, idxTramo, "CONEXION_AEROPUERTO_INVALIDA", v,
                                    "Destino previo=" + str(dPrev) + " no coincide con Origen actual=" + str(oAct)));
                        }
                        Instant arrPrev = prev.getLlegadaUtc();
                        Instant depAct  = v.getSalidaUtc();
                        if (arrPrev != null && depAct != null && depAct.isBefore(arrPrev)) {
                            out.add(new RutaViolation(idPedido, idxRuta, idxTramo, "CONEXION_TIEMPO_NEGATIVA", v,
                                    "Salida sig.=" + iso(depAct) + " < llegada prev.=" + iso(arrPrev)));
                        }
                    }

                    prev = v;
                }
            }
        }

        out.sort(Comparator
                .comparing(RutaViolation::causa)
                .thenComparingInt(RutaViolation::idPedido)
                .thenComparingInt(RutaViolation::idxRuta)
                .thenComparingInt(RutaViolation::idxTramo));
        return out;
    }

    private static boolean existeOD(VuelosMap mapa, String origen, String destino) {
        List<Vuelo> lista = mapa.vuelosDesde(origen);
        if (lista == null || lista.isEmpty()) return false;
        for (Vuelo base : lista) {
            // Ajusta aquí el getter si tu Vuelo usa otro nombre para destino.
            if (destino.equals(base.getDestino())) return true;
        }
        return false;
    }

    private static void addDelta(Map<String, TreeMap<Instant, Integer>> deltasByAp,
                                 String aeropuerto,
                                 Instant t,
                                 int delta) {
        if (aeropuerto == null || aeropuerto.isBlank() || t == null) return;
        TreeMap<Instant, Integer> deltas = deltasByAp.computeIfAbsent(aeropuerto, k -> new TreeMap<>());
        deltas.merge(t, delta, Integer::sum);
    }

    // =========================
    // Tipos de datos del reporte
    // =========================

    public record CapViolation(VueloProgramadoId id, int capacidad, int asignado) {}

    public record SLAViolation(int idPedido, Instant creadoUtc, Instant ultimaLlegadaUtc, long tardanzaHoras) {}

    public record PedidoIncompleto(int idPedido, int demanda, int asignado) {
        public double pctAvance() { return demanda == 0 ? 100.0 : (100.0 * asignado / (double) demanda); }
        public int faltante() { return Math.max(0, demanda - asignado); }
    }

    public record BodegaViolation(String aeropuerto, Instant desde, Instant hasta, int capacidad, int ocupacion) {}

    /** NUEVO: violación en la estructura/realidad de rutas. */
    public record RutaViolation(int idPedido, int idxRuta, int idxTramo, String causa, VueloProgramadoId vuelo, String detalle) {}

    public record SLAReport(
            List<CapViolation> capacityViolations,
            List<SLAViolation> slaPickupViolations,
            List<SLAViolation> sla48Violations,
            List<PedidoIncompleto> pedidosIncompletos,
            List<BodegaViolation> bodegaViolations,
            List<RutaViolation> rutaViolations // NUEVO
    ) {
        public boolean ok() {
            return capacityViolations.isEmpty()
                && slaPickupViolations.isEmpty()
                && sla48Violations.isEmpty()
                && pedidosIncompletos.isEmpty()
                && bodegaViolations.isEmpty()
                && rutaViolations.isEmpty();
        }

        public List<CapViolation> topCapacity(int n) { return capacityViolations.subList(0, Math.min(n, capacityViolations.size())); }
        public List<SLAViolation> topPickup(int n)   { return slaPickupViolations.subList(0, Math.min(n, slaPickupViolations.size())); }
        public List<SLAViolation> top48(int n)       { return sla48Violations.subList(0, Math.min(n, sla48Violations.size())); }
        public List<PedidoIncompleto> topIncompletos(int n) { return pedidosIncompletos.subList(0, Math.min(n, pedidosIncompletos.size())); }
        public List<BodegaViolation> topBodega(int n) { return bodegaViolations.subList(0, Math.min(n, bodegaViolations.size())); }
        public List<RutaViolation> topRuta(int n)     { return rutaViolations.subList(0, Math.min(n, rutaViolations.size())); }

        public int faltanteTotal() {
            int sum = 0;
            for (PedidoIncompleto p : pedidosIncompletos) sum += p.faltante();
            return sum;
        }
    }

    // =========================
    // Utils de formato
    // =========================

    private static String iso(Instant t) {
        return t == null ? "-" : java.time.format.DateTimeFormatter.ISO_INSTANT.format(t);
    }

    private static String vueloIdStr(VueloProgramadoId v) {
        if (v == null) return "-";
        String o = v.getOrigen() == null ? "" : v.getOrigen();
        String d = v.getDestino() == null ? "" : v.getDestino();
        String s = v.getSalidaUtc() == null ? "" : java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(java.time.ZoneOffset.UTC)
                .format(v.getSalidaUtc());
        return o + d + s;
    }

    private static String str(String x) { return x == null ? "-" : x; }
}
