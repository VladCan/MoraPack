package pe.edu.pucp.morapack.airscheduler.engine.scheduling.service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosMap;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Vuelo;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.PlanPedido;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.RutaAsignada;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.TramoAsignado;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.SolucionProgramacion;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.VueloProgramadoId;

public final class VerificadorSLA {
    private VerificadorSLA(){}

    // ==========================================
    // CONFIGURACIÓN DE SEDES (Stock Infinito)
    // ==========================================
    // Estas sedes nunca deben reportar error de capacidad ni de stock negativo.
    private static final Set<String> SEDES_INFINITAS = new HashSet<>(Arrays.asList("SPIM", "EBCI", "UBBB"));

    // =========================
    // API pública
    // =========================

    /**
     * Valida la solución buscando errores críticos de física (cargas negativas, stock negativo)
     * y violaciones de reglas de negocio (SLA, capacidad).
     * @return true si la solución es válida y segura; false si hay violaciones.
     */
    public static boolean assertBasicos(SolucionProgramacion sol, Duration ventana46h, VuelosMap vuelosMap, AeropuertosMap aeropuertosMap) {
        SLAReport r = diagnosticar(sol, ventana46h, aeropuertosMap, vuelosMap);
        if (r.ok()) {
            System.out.println("[VerificadorSLA] ✅ Solución válida. Física y Reglas de Negocio respetadas.");
            return true;
        }
        System.err.println("[VerificadorSLA] ❌ SE ENCONTRARON ERRORES CRÍTICOS O VIOLACIONES DE SLA.");
        System.err.println(renderError(sol, ventana46h, r));
        return false;
    }
    
    public static void assertBasicos(SolucionProgramacion sol, Duration ventana46h) {
        if (!assertBasicos(sol, ventana46h, null, null)) {
            throw new IllegalStateException("Solución inválida (ver logs anteriores)");
        }
    }

    public static SLAReport diagnosticar(SolucionProgramacion sol,
                                         Duration ventana46h,
                                         AeropuertosMap aeropuertosMap,
                                         VuelosMap vuelosMap) {
        Objects.requireNonNull(sol, "sol");
        Objects.requireNonNull(ventana46h, "ventana46h");

        // -------------------------------------------------
        // GRUPO 1: SANIDAD DE DATOS (Física básica)
        // -------------------------------------------------
        
        List<CargaNegativaViolation> cargaNegativa = new ArrayList<>();
        List<CargaZeroViolation> cargaZero = new ArrayList<>();
        List<CapViolation> capViol = new ArrayList<>();

        sol.getCargaPorVuelo().getAsignado().forEach((id, asign) -> {
            int cap = sol.getCargaPorVuelo().capacidad(id);
            int asg = (asign == null ? 0 : asign);

            if (asg < 0) {
                cargaNegativa.add(new CargaNegativaViolation(id, asg));
            }
            else if (asg == 0) {
                // Un vuelo está en el mapa con 0kg. 
                // Esto pasa si alguien hizo map.put(id, 0) o map.merge(id, -val, sum) resultando en 0.
                cargaZero.add(new CargaZeroViolation(id));
            }
            else if (asg > cap) {
                capViol.add(new CapViolation(id, cap, asg));
            }
        });
        
        capViol.sort(Comparator.comparingInt((CapViolation v) -> v.asignado - v.capacidad).reversed());

        // -------------------------------------------------
        // GRUPO 2: INTEGRIDAD DE ALMACÉN (Stock)
        // -------------------------------------------------
        
        List<BodegaViolation> bodegaCapViolations = new ArrayList<>(); 
        List<BodegaStockViolation> bodegaStockViolations = new ArrayList<>(); 

        if (aeropuertosMap != null) {
            ReporteBodega reporteBodega = detectarViolacionesBodega(sol, ventana46h, aeropuertosMap);
            bodegaCapViolations = reporteBodega.capacidadExcedida;
            bodegaStockViolations = reporteBodega.stockNegativo;
        }

        // -------------------------------------------------
        // GRUPO 3: SLA Y COMPLETITUD (Reglas de Negocio)
        // -------------------------------------------------

        List<SLAViolation> v46 = new ArrayList<>();
        List<SLAViolation> v48 = new ArrayList<>();
        List<PedidoIncompleto> incompletos = new ArrayList<>();

        for (PlanPedido p : sol.asMap().values()) {
            Instant creado = p.getCreadoUtc();
            Instant ult = p.ultimaLlegada();

            // Validación SLA 46h
            Instant limite46 = (creado == null ? null : creado.plus(ventana46h));
            if (limite46 == null || ult == null || ult.isAfter(limite46)) {
                long tardH = (limite46 == null || ult == null) ? -1L
                        : Math.max(0L, java.time.Duration.between(limite46, ult).toHours());
                v46.add(new SLAViolation(p.getIdPedido(), creado, ult, tardH));
            }

            // Validación SLA 48h (Hard Constraint)
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

        // -------------------------------------------------
        // GRUPO 4: RUTAS ESTRUCTURALES
        // -------------------------------------------------
        
        List<RutaViolation> rutaViolations = new ArrayList<>();
        if (vuelosMap != null) {
            rutaViolations = verificarRutasRealesOD(sol, vuelosMap);
        }

        return new SLAReport(
                cargaNegativa,      
                cargaZero,          
                bodegaStockViolations, 
                capViol,
                v46,
                v48,
                incompletos,
                bodegaCapViolations,
                rutaViolations
        );
    }

    // =========================
    // Construcción de error legible
    // =========================

    private static String renderError(SolucionProgramacion sol, Duration ventana46h, SLAReport r) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("\n======================================================\n");
        sb.append("   REPORTE DE VIOLACIONES DE INTEGRIDAD Y SLA\n");
        sb.append("======================================================\n");

        // 1. ERRORES DE FÍSICA
        if (!r.cargaNegativa.isEmpty()) {
            sb.append("\n🛑 [CRÍTICO] CARGA NEGATIVA EN VUELOS (").append(r.cargaNegativa.size()).append("):\n");
            for (CargaNegativaViolation v : r.cargaNegativa.subList(0, Math.min(10, r.cargaNegativa.size()))) {
                sb.append(String.format("   - %s  carga=%,d (IMPOSIBLE)\n", vueloIdStr(v.id), v.asignado));
            }
        }
        
        if (!r.bodegaStockNegativo.isEmpty()) {
            sb.append("\n🛑 [CRÍTICO] STOCK NEGATIVO EN ALMACÉN (").append(r.bodegaStockNegativo.size()).append("):\n");
            for (BodegaStockViolation v : r.bodegaStockNegativo.subList(0, Math.min(10, r.bodegaStockNegativo.size()))) {
                sb.append(String.format("   - %s  en %s  Stock=%,d\n", v.aeropuerto, iso(v.momento), v.stockReal));
            }
        }

        // 2. ERRORES ESTRUCTURALES / FANTASMAS
        if (!r.rutaViolations.isEmpty()) {
            sb.append("\n🚫 RUTAS INVÁLIDAS / INEXISTENTES (").append(r.rutaViolations.size()).append("):\n");
            for (RutaViolation rv : r.topRuta(20)) {
                sb.append(String.format("   - Pedido %d Ruta#%d Tramo#%d: [%s] -> %s (Vuelo: %s)\n", 
                        rv.idPedido, rv.idxRuta, rv.idxTramo, rv.causa, rv.detalle, vueloIdStr(rv.vuelo)));
            }
        }
        
        if (!r.cargaZero.isEmpty()) {
            sb.append("\n⚠ VUELOS FANTASMA (Asignados con 0 Kg) (").append(r.cargaZero.size()).append("):\n");
            sb.append("   (Estos registros existen en 'CargaPorVuelo' con valor 0. Revise operadores que hagan map.put(id,0))\n");
             for (CargaZeroViolation v : r.cargaZero.subList(0, Math.min(10, r.cargaZero.size()))) {
                // Info extra del vuelo para debugging
                sb.append(String.format("   - %s -> %s | %s (Salida: %s)\n", 
                    v.id.getOrigen(), v.id.getDestino(), v.id.toString(), v.id.getSalidaUtc()));
            }
             if (r.cargaZero.size() > 10) sb.append("     ... y " + (r.cargaZero.size()-10) + " más.");
        }

        // 3. CAPACIDAD
        if (!r.capacityViolations.isEmpty()) {
            sb.append("\n📦 SOBRECAPACIDAD DE AVIONES (").append(r.capacityViolations.size()).append("):\n");
            for (CapViolation v : r.topCapacity(10)) {
                sb.append(String.format("   - %s  cap=%,d  asig=%,d  exceso=+%,d\n",
                        vueloIdStr(v.id), v.capacidad, v.asignado, (v.asignado - v.capacidad)));
            }
        }
        
        if (!r.bodegaCapViolations.isEmpty()) {
             sb.append("\n🏭 SOBRECAPACIDAD DE ALMACÉN (").append(r.bodegaCapViolations.size()).append("):\n");
             sb.append("   (Sedes Infinitas " + SEDES_INFINITAS + " fueron excluidas)\n");
             for (BodegaViolation b : r.topBodega(10)) {
                sb.append(String.format("   - %s  [%s]  ocup=%,d  cap=%,d  exceso=+%,d\n",
                        b.aeropuerto, iso(b.desde), b.ocupacion, b.capacidad, Math.max(0, b.ocupacion - b.capacidad)));
            }
        }

        // 4. SLA
        if (!r.pedidosIncompletos.isEmpty()) {
            sb.append("\n📉 PEDIDOS INCOMPLETOS (").append(r.pedidosIncompletos.size()).append("):\n");
             for (PedidoIncompleto p : r.topIncompletos(10)) {
                sb.append(String.format("   - Pedido %d  Avance=%.1f%%\n", p.idPedido, p.pctAvance()));
            }
        }
        
        if (!r.sla48Violations.isEmpty()) {
             sb.append("\n⏰ SLA 48H VIOLADO (").append(r.sla48Violations.size()).append("):\n");
             for (SLAViolation v : r.top48(10)) {
                sb.append(String.format("   - Pedido %d Tarde +%dh\n", v.idPedido, v.tardanzaHoras));
             }
        }

        return sb.toString();
    }

    // =========================
    // Lógica de Bodegas (CORREGIDA)
    // =========================

    private static class ReporteBodega {
        List<BodegaViolation> capacidadExcedida = new ArrayList<>();
        List<BodegaStockViolation> stockNegativo = new ArrayList<>();
    }

    private static ReporteBodega detectarViolacionesBodega(SolucionProgramacion sol,
                                                          Duration ventana46h,
                                                          AeropuertosMap aeropuertosMap) {
        ReporteBodega reporte = new ReporteBodega();
        final Duration pickup = Duration.ofHours(48).minus(ventana46h);
        Map<String, TreeMap<Instant, Integer>> deltasPorAeropuerto = new HashMap<>();

        // 1. Construir Deltas (Entradas y Salidas)
        for (PlanPedido plan : sol.asMap().values()) {
            if (plan.getRutas() == null) continue;
            for (RutaAsignada ruta : plan.getRutas()) {
                if (ruta.getTramos() == null) continue;
                int q = ruta.getCantidad();
                
                // Si la ruta tiene 0kg, no afecta bodega (es un fantasma lógico)
                if (q <= 0) continue; 

                List<TramoAsignado> tramos = ruta.getTramos();
                for (int i = 0; i < tramos.size(); i++) {
                    TramoAsignado tramo = tramos.get(i);
                    VueloProgramadoId v = tramo.getVuelo();
                    if (v == null) continue;
                    
                    // ORIGEN
                    Instant llegadaAnterior = (i == 0) ? plan.getCreadoUtc() 
                            : tramos.get(i - 1).getVuelo().getLlegadaUtc();
                    
                    if (llegadaAnterior != null && v.getSalidaUtc() != null && !v.getSalidaUtc().isBefore(llegadaAnterior)) {
                        addDelta(deltasPorAeropuerto, v.getOrigen(), llegadaAnterior, q);   
                        addDelta(deltasPorAeropuerto, v.getOrigen(), v.getSalidaUtc(), -q); 
                    }

                    // DESTINO
                    Instant salidaSiguiente;
                    if (i + 1 < tramos.size()) {
                        salidaSiguiente = tramos.get(i + 1).getVuelo().getSalidaUtc();
                    } else {
                        salidaSiguiente = (v.getLlegadaUtc() != null) ? v.getLlegadaUtc().plus(pickup) : null;
                    }
                    
                    if (v.getLlegadaUtc() != null && salidaSiguiente != null && !salidaSiguiente.isBefore(v.getLlegadaUtc())) {
                         addDelta(deltasPorAeropuerto, v.getDestino(), v.getLlegadaUtc(), q); 
                         addDelta(deltasPorAeropuerto, v.getDestino(), salidaSiguiente, -q);  
                    }
                }
            }
        }

        // 2. Simular línea de tiempo
        for (Map.Entry<String, TreeMap<Instant, Integer>> e : deltasPorAeropuerto.entrySet()) {
            String ap = e.getKey();
            
            // CORRECCIÓN SOLICITADA: 
            // Si el aeropuerto es una SEDE INFINITA, no verificamos stock ni capacidad.
            if (SEDES_INFINITAS.contains(ap)) continue;

            TreeMap<Instant, Integer> deltas = e.getValue();
            int capacidad = aeropuertosMap.getCapBodega(ap);

            int currentStock = 0;
            Instant prev = null;
            
            for (Map.Entry<Instant, Integer> d : deltas.entrySet()) {
                Instant t = d.getKey();
                int cambio = d.getValue();

                if (prev != null && t.isAfter(prev)) {
                    if (currentStock > capacidad) {
                        reporte.capacidadExcedida.add(new BodegaViolation(ap, prev, t, capacidad, currentStock));
                    }
                    if (currentStock < 0) {
                        reporte.stockNegativo.add(new BodegaStockViolation(ap, prev, currentStock));
                    }
                }
                currentStock += cambio;
                prev = t;
            }
        }
        return reporte;
    }

    // =========================
    // Lógica de Rutas
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
                        if (!mapa.origenes().contains(o)) {
                            out.add(new RutaViolation(idPedido, idxRuta, idxTramo, "ORIGEN_NO_EN_CATALOGO", v, "No hay vuelos con origen=" + o));
                        } else {
                            if (!existeOD(mapa, o, d)) {
                                out.add(new RutaViolation(idPedido, idxRuta, idxTramo, "TRAMO_SIN_OFERTA_OD", v, "No existe vuelo " + o + " -> " + d));
                            }
                        }
                    }

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
                .thenComparingInt(RutaViolation::idxRuta));
        return out;
    }

    private static boolean existeOD(VuelosMap mapa, String origen, String destino) {
        List<Vuelo> lista = mapa.vuelosDesde(origen);
        if (lista == null || lista.isEmpty()) return false;
        for (Vuelo base : lista) {
            if (destino.equals(base.getDestino())) return true;
        }
        return false;
    }

    // =========================
    // Métodos Auxiliares
    // =========================

    private static void addDelta(Map<String, TreeMap<Instant, Integer>> map, String ap, Instant t, int d) {
        if (ap == null || t == null) return;
        map.computeIfAbsent(ap, k -> new TreeMap<>()).merge(t, d, Integer::sum);
    }

    private static String str(String x) { return x == null ? "-" : x; }
    private static String iso(Instant t) { return t == null ? "-" : t.toString(); }
    private static String vueloIdStr(VueloProgramadoId v) { return v == null ? "null" : v.toString(); }

    // =========================
    // Records
    // =========================

    public record CargaNegativaViolation(VueloProgramadoId id, int asignado) {}
    public record CargaZeroViolation(VueloProgramadoId id) {}
    public record BodegaStockViolation(String aeropuerto, Instant momento, int stockReal) {}
    
    public record CapViolation(VueloProgramadoId id, int capacidad, int asignado) {}
    public record SLAViolation(int idPedido, Instant creadoUtc, Instant ultimaLlegadaUtc, long tardanzaHoras) {}
    public record PedidoIncompleto(int idPedido, int demanda, int asignado) {
        public double pctAvance() { return demanda == 0 ? 100.0 : (100.0 * asignado / (double) demanda); }
        public int faltante() { return Math.max(0, demanda - asignado); }
    }
    public record BodegaViolation(String aeropuerto, Instant desde, Instant hasta, int capacidad, int ocupacion) {}
    public record RutaViolation(int idPedido, int idxRuta, int idxTramo, String causa, VueloProgramadoId vuelo, String detalle) {}

    public record SLAReport(
            List<CargaNegativaViolation> cargaNegativa,
            List<CargaZeroViolation> cargaZero,
            List<BodegaStockViolation> bodegaStockNegativo,
            List<CapViolation> capacityViolations,
            List<SLAViolation> slaPickupViolations,
            List<SLAViolation> sla48Violations,
            List<PedidoIncompleto> pedidosIncompletos,
            List<BodegaViolation> bodegaCapViolations,
            List<RutaViolation> rutaViolations
    ) {
        public boolean ok() {
            return cargaNegativa.isEmpty() && 
                   bodegaStockNegativo.isEmpty() && 
                   capacityViolations.isEmpty() && 
                   bodegaCapViolations.isEmpty() &&
                   rutaViolations.isEmpty() &&
                   sla48Violations.isEmpty();
        }
        
        public List<CapViolation> topCapacity(int n) { return sub(capacityViolations, n); }
        public List<SLAViolation> top48(int n) { return sub(sla48Violations, n); }
        public List<SLAViolation> topPickup(int n) { return sub(slaPickupViolations, n); }
        public List<PedidoIncompleto> topIncompletos(int n) { return sub(pedidosIncompletos, n); }
        public List<BodegaViolation> topBodega(int n) { return sub(bodegaCapViolations, n); }
        public List<RutaViolation> topRuta(int n) { return sub(rutaViolations, n); }
        
        private <T> List<T> sub(List<T> list, int n) {
            return list.subList(0, Math.min(n, list.size()));
        }
    }
}