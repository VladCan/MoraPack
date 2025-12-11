package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns;

import lombok.RequiredArgsConstructor;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators.DestructionOperator;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators.RepairOperator;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators.WarehouseSmartRemoval;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class ALNS {

    private final VuelosTEG teg;
    private final List<Pedido> pedidos;
    private final List<DestructionOperator> destructions;
    private final List<RepairOperator> repairs;
    private final Instant presenteUTC;
    private final OcupacionPorAeropuerto ocupacionPorAeropuerto;
    private final AeropuertosMap aeropuertosMap;

    private final Random rnd = new Random();

    private final int maxIter = 2500;
    private final double startTemperatureRatio = 0.05;
    private final int maxStagnation = 200;

    private static final double PEN_INCOMPLETO          = 1_000_000_000_000.0;
    private static final double PEN_CAPACIDAD_VUELO     =   500_000_000_000.0;
    private static final double PEN_SLA                 =    50_000_000_000.0;
    private static final double PEN_CAPACIDAD_BODEGA_BASE =   1_000_000.0;

    private WarehouseSmartRemoval emergencyOperator;

    // --- 📊 PROFILING MAPS ---
    private final Map<String, Long> destroyTotalTimeNs = new HashMap<>();
    private final Map<String, Integer> destroyCount = new HashMap<>();
    private final Map<String, Long> repairTotalTimeNs = new HashMap<>();
    private final Map<String, Integer> repairCount = new HashMap<>();
    // -------------------------

    public SolucionProgramacion ejecutar(SolucionProgramacion solucionInicial) {
        System.out.println("=================================================");
        System.out.println(">>> INICIANDO ALNS (MODO: PROFILING ACTIVADO) <<<");
        System.out.println("=================================================");

        this.emergencyOperator = new WarehouseSmartRemoval(this.aeropuertosMap);

        // Reset stats
        destroyTotalTimeNs.clear(); destroyCount.clear();
        repairTotalTimeNs.clear(); repairCount.clear();

        SolucionProgramacion solucionBase = solucionInicial;
        SolucionProgramacion mejorSolucion = new SolucionProgramacion(solucionInicial);

        OcupacionPorAeropuerto ocupacionBase = this.ocupacionPorAeropuerto.copiaProfunda();
        OcupacionPorAeropuerto mejorOcupacion = this.ocupacionPorAeropuerto.copiaProfunda();

        sanitizarSolucion(solucionBase);
        limpiarMapaGlobal(solucionBase);

        double costoActual = getCostoTotal(solucionBase, ocupacionBase);
        double costoMejor = costoActual;
        double temperatura = costoActual * startTemperatureRatio;

        int iteracionesSinMejora = 0;
        long tInicioGlobal = System.nanoTime();
        long tiempoLimiteNs = 29L * 1_000_000_000L;

        for (int iter = 0; iter < maxIter; iter++) {

            if ((System.nanoTime() - tInicioGlobal) > tiempoLimiteNs) {
                System.out.println("🛑 EARLY STOP: Tiempo límite (29s).");
                break;
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("[Iter %04d] ", iter));

            SolucionProgramacion solucionCandidata = new SolucionProgramacion(solucionBase);
            OcupacionPorAeropuerto ocupacionCandidata = ocupacionBase.copiaProfunda();
            Journal journal = new Journal(ocupacionCandidata);

            DestructionOperator destrOp;
            boolean hayCrisisBodega = ocupacionCandidata.hayExcesoDeCapacidad();

            if (hayCrisisBodega && rnd.nextDouble() < 0.80) {
                destrOp = this.emergencyOperator;
                sb.append("[🚑 WAREHOUSE FIX] ");
            } else {
                destrOp = destructions.get(rnd.nextInt(destructions.size()));
            }

            RepairOperator repairOp = repairs.get(rnd.nextInt(repairs.size()));

            String opTag = String.format("[%s->%s]",
                destrOp.getClass().getSimpleName().substring(0, 4),
                repairOp.getClass().getSimpleName().substring(0, 4));
            sb.append(String.format("%-12s ", opTag));

            // --- ⏱️ MEDICIÓN GRANULAR ---
            long tStart = System.nanoTime();
            destrOp.destroy(solucionCandidata, journal, presenteUTC);
            long tMid = System.nanoTime();
            repairOp.repair(solucionCandidata, journal, presenteUTC);
            long tEnd = System.nanoTime();

            // Guardar stats
            registrarTiempo(destroyTotalTimeNs, destroyCount, destrOp.getClass().getSimpleName(), tMid - tStart);
            registrarTiempo(repairTotalTimeNs, repairCount, repairOp.getClass().getSimpleName(), tEnd - tMid);

            sanitizarSolucion(solucionCandidata);
            
            // Tiempos para el log (en ms)
            long dMs = (tMid - tStart) / 1_000_000;
            long rMs = (tEnd - tMid) / 1_000_000;
            
            // Alertar visualmente si algo toma más de 100ms
            String dStr = dMs > 100 ? String.format("\u001B[31mD:%dms\u001B[0m", dMs) : String.format("D:%dms", dMs);
            String rStr = rMs > 100 ? String.format("\u001B[31mR:%dms\u001B[0m", rMs) : String.format("R:%dms", rMs);
            
            sb.append(String.format("%s %s ", dStr, rStr));
            // -----------------------------

            double costoCandidato = getCostoTotal(solucionCandidata, ocupacionCandidata);

            boolean esMejorGlobal = costoCandidato < costoMejor;
            boolean aceptar = false;
            double delta = costoCandidato - costoActual;
            String estado = "X";

            if (delta < 0) {
                aceptar = true;
                estado = "OK";
            } else {
                if (rnd.nextDouble() < Math.exp(-delta / temperatura)) {
                    aceptar = true;
                    estado = "SA";
                }
            }

            if (aceptar) {
                solucionBase = solucionCandidata;
                ocupacionBase = ocupacionCandidata;
                costoActual = costoCandidato;
                sb.append(String.format("-> %s (%,.0f)", estado, costoActual));

                if (esMejorGlobal) {
                    mejorSolucion = new SolucionProgramacion(solucionBase);
                    mejorOcupacion = ocupacionBase.copiaProfunda();
                    costoMejor = costoCandidato;
                    iteracionesSinMejora = 0;
                    sb.append(" **R**");
                } else {
                    iteracionesSinMejora++;
                }
            } else {
                iteracionesSinMejora++;
                sb.append(String.format("-> X  (%,.0f)", costoCandidato));
            }

            // Solo imprimimos el log si tarda mucho o es una mejora, para no saturar si va rápido
            // (Opcional: quita el 'if' si quieres ver todo)
            if (dMs + rMs > 50 || esMejorGlobal) {
                System.out.println(sb.toString());
            }

            temperatura *= 0.95;

            if (iteracionesSinMejora >= maxStagnation) {
                if (mejorOcupacion.hayExcesoDeCapacidad()) {
                    iteracionesSinMejora = 0;
                    temperatura = costoActual * 0.20;
                    System.out.println(sb.toString() + " [REHEAT-CRISIS]");
                } else {
                    System.out.println("🛑 EARLY STOP: Convergencia.");
                    break;
                }
            }
        }

        // =========================================================================
        // 🛡️ FASE DE LEGALIZACIÓN FORZOSA (RF3 - FINAL CHECK)
        // =========================================================================
        if (mejorOcupacion.hayExcesoDeCapacidad()) {
            System.out.println("⚠️ ALERTA: La mejor solución viola capacidad de bodegas. Ejecutando limpieza forzosa...");
            Journal finalJournal = new Journal(mejorOcupacion);
            this.emergencyOperator.destroy(mejorSolucion, finalJournal, presenteUTC);
            costoMejor = getCostoTotal(mejorSolucion, mejorOcupacion);
            System.out.println("✅ Solución legalizada. Nuevo costo: " + String.format("%,.0f", costoMejor));
        }

        this.ocupacionPorAeropuerto.copiarDesde(mejorOcupacion);
        sanitizarSolucion(mejorSolucion);
        limpiarMapaGlobal(mejorSolucion);

        long tTotal = (System.nanoTime() - tInicioGlobal) / 1_000_000;
        
        // IMPRIMIR REPORTE DE TIEMPOS
        imprimirReporteTiempos();

        System.out.println(">>> FIN. Tiempo: " + tTotal + "ms. Mejor Costo: " + String.format("%,.0f", costoMejor));
        return mejorSolucion;
    }

    // --- MÉTODOS DE PROFILING ---
    private void registrarTiempo(Map<String, Long> timeMap, Map<String, Integer> countMap, String key, long timeNs) {
        timeMap.merge(key, timeNs, Long::sum);
        countMap.merge(key, 1, Integer::sum);
    }

    private void imprimirReporteTiempos() {
        System.out.println("\n📊 REPORTE DE RENDIMIENTO (TOP LENTOS) 📊");
        System.out.println("----------------------------------------------------------------");
        System.out.println(String.format("%-30s | %-6s | %-10s | %-10s", "Operador", "Calls", "Total(ms)", "Avg(ms)"));
        System.out.println("----------------------------------------------------------------");
        
        Map<String, Double> promedios = new HashMap<>();
        
        // Unir ambos mapas para el reporte
        Set<String> allOps = new HashSet<>(destroyTotalTimeNs.keySet());
        allOps.addAll(repairTotalTimeNs.keySet());

        for(String op : allOps) {
            long totalNs = destroyTotalTimeNs.getOrDefault(op, 0L) + repairTotalTimeNs.getOrDefault(op, 0L);
            int count = destroyCount.getOrDefault(op, 0) + repairCount.getOrDefault(op, 0);
            if(count > 0) promedios.put(op, (double)totalNs / count / 1_000_000.0);
        }

        promedios.entrySet().stream()
            .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue())) // Ordenar descendente por promedio
            .limit(10)
            .forEach(e -> {
                String op = e.getKey();
                long totalNs = destroyTotalTimeNs.getOrDefault(op, 0L) + repairTotalTimeNs.getOrDefault(op, 0L);
                int count = destroyCount.getOrDefault(op, 0) + repairCount.getOrDefault(op, 0);
                System.out.println(String.format("%-30s | %6d | %10d | %10.2f", 
                    op, count, totalNs/1_000_000, e.getValue()));
            });
        System.out.println("----------------------------------------------------------------\n");
    }
    // ----------------------------

    private void sanitizarSolucion(SolucionProgramacion sol) {
        for (PlanPedido plan : sol.getPlanPorPedido().values()) {
            plan.removerRutasInvalidas();
        }
    }

    private void limpiarMapaGlobal(SolucionProgramacion sol) {
        if (sol.getCargaPorVuelo() != null && sol.getCargaPorVuelo().getAsignado() != null) {
            sol.getCargaPorVuelo().getAsignado().entrySet().removeIf(entry -> entry.getValue() <= 0);
        }
    }

    private double getCostoTotal(SolucionProgramacion sol, OcupacionPorAeropuerto occ) {
        double costo = 0;

        if (!sol.respetaCapacidadesVuelos()) return PEN_CAPACIDAD_VUELO;

        if (occ.hayExcesoDeCapacidad()) {
            costo += PEN_CAPACIDAD_BODEGA_BASE;
        }

        Collection<PlanPedido> planes = sol.getPlanPorPedido().values();
        for (PlanPedido p : planes) {
            
            if (p.getRutas() == null || p.getRutas().isEmpty()) {
                costo += PEN_INCOMPLETO;
                continue;
            }

            Instant ultimaLlegadaGlobal = Instant.MIN;
            for (RutaAsignada r : p.getRutas()) {
                Instant llegadaRuta = r.ultimaLlegada();
                if (llegadaRuta != null && llegadaRuta.isAfter(ultimaLlegadaGlobal)) {
                    ultimaLlegadaGlobal = llegadaRuta;
                }
            }

            if (ultimaLlegadaGlobal == Instant.MIN) {
                costo += PEN_INCOMPLETO;
                continue;
            }

            int cantidadAsignada = p.getRutas().stream().mapToInt(RutaAsignada::getCantidad).sum();
            if (cantidadAsignada < p.getDemanda()) {
                double faltante = p.getDemanda() - cantidadAsignada;
                costo += (faltante * 1_000_000.0);
            }

            Instant deadline = p.getCreadoUtc().plus(Duration.ofHours(46));
            if (ultimaLlegadaGlobal.isAfter(deadline)) {
                 costo += PEN_SLA;
                 long horasTarde = Duration.between(deadline, ultimaLlegadaGlobal).toHours();
                 costo += (horasTarde * 5_000_000);
            }

            costo += Duration.between(p.getCreadoUtc(), ultimaLlegadaGlobal).toMinutes();
        }

        return costo;
    }

    public static final class Journal {
        private final OcupacionPorAeropuerto occ;
        public Journal(OcupacionPorAeropuerto occ) { this.occ = occ; }
        public OcupacionPorAeropuerto getOcc() { return this.occ; }
        public void reservar(String ap, Instant ini, Instant fin, int q) { occ.reservar(ap, ini, fin, q); }
        public void liberar(String ap, Instant ini, Instant fin, int q) { occ.liberar(ap, ini, fin, q); }
        public void rollback() { }
        public void commit() { }
    }
}