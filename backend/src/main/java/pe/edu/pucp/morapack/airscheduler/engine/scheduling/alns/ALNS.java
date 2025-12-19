package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns;

import lombok.RequiredArgsConstructor;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators.DestructionOperator;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators.RepairOperator;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators.WarehouseCrisisRemoval; // Asegúrate de importar esto si lo usas como emergencyOperator
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

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
    private final double startTemperatureRatio = 0.35;
    private final int maxStagnation = 200;

    private static final double PEN_INCOMPLETO          = 1_000_000_000_000.0;
    private static final double PEN_CAPACIDAD_VUELO     =   500_000_000_000.0;
    private static final double PEN_SLA                 =    50_000_000_000.0;
    private static final double PEN_CAPACIDAD_BODEGA_BASE =   1_000_000.0;

    // Operador de emergencia (ahora será WarehouseCrisisRemoval)
    private WarehouseCrisisRemoval emergencyOperator;

    // --- 📊 PROFILING MAPS ---
    private final Map<String, Long> destroyTotalTimeNs = new HashMap<>();
    private final Map<String, Integer> destroyCount = new HashMap<>();
    private final Map<String, Long> repairTotalTimeNs = new HashMap<>();
    private final Map<String, Integer> repairCount = new HashMap<>();
    // -------------------------

    // Colores para logs (opcional, si la consola no soporta ANSI se verán caracteres raros, se puede quitar)
    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String PURPLE = "\u001B[35m";
    private static final String CYAN = "\u001B[36m";

    public SolucionProgramacion ejecutar(SolucionProgramacion solucionInicial) {
        System.out.println(CYAN + "=================================================" + RESET);
        System.out.println(CYAN + ">>> INICIANDO ALNS (MODO: PROFILING ACTIVADO) <<<" + RESET);
        System.out.println(CYAN + "=================================================" + RESET);

        final SolucionProgramacion solucionBackup = new SolucionProgramacion(solucionInicial);
        final OcupacionPorAeropuerto ocupacionBackup = this.ocupacionPorAeropuerto.copiaProfunda();

        // Inicializamos el operador de emergencia con una cantidad fija a borrar (ej. 20)
        this.emergencyOperator = new WarehouseCrisisRemoval(3, this.aeropuertosMap);

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

        // --- LOG DEL COSTO INICIAL (SEED) ---
        System.out.println(YELLOW + "💰 Costo Inicial (Seed): " + String.format("%,.0f", costoActual) + RESET);
        System.out.println(CYAN + "-------------------------------------------------" + RESET);

        int iteracionesSinMejora = 0;
        long tInicioGlobal = System.nanoTime();
        // Límite de tiempo: 29 segundos (para respetar timeouts típicos de 30s)
        long tiempoLimiteNs = 120L * 1_000_000_000L;

        for (int iter = 0; iter < maxIter; iter++) {

            if ((System.nanoTime() - tInicioGlobal) > tiempoLimiteNs) {
                System.out.println(RED + "🛑 EARLY STOP: Tiempo límite (29s)." + RESET);
                break;
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("[Iter %04d] ", iter));

            SolucionProgramacion solucionCandidata = new SolucionProgramacion(solucionBase);
            OcupacionPorAeropuerto ocupacionCandidata = ocupacionBase.copiaProfunda();
            Journal journal = new Journal(ocupacionCandidata);

            DestructionOperator destrOp;
            boolean hayCrisisBodega = ocupacionCandidata.hayExcesoDeCapacidad();

            // Si hay crisis de bodega, alta probabilidad de usar el operador de emergencia
            if (hayCrisisBodega && rnd.nextDouble() < 0.80) {
                destrOp = this.emergencyOperator;
                sb.append(PURPLE + "[🚑 WAREHOUSE FIX] " + RESET);
            } else {
                destrOp = destructions.get(rnd.nextInt(destructions.size()));
            }

            RepairOperator repairOp = repairs.get(rnd.nextInt(repairs.size()));

            String dName = destrOp.getClass().getSimpleName();
            String rName = repairOp.getClass().getSimpleName();
            // Acortar nombres para el log
            String opTag = String.format("[%s->%s]",
                dName.length() > 6 ? dName.substring(0, 6) : dName,
                rName.length() > 6 ? rName.substring(0, 6) : rName);
            sb.append(String.format("%-16s ", opTag));

            // --- ⏱️ MEDICIÓN GRANULAR ---
            long tStart = System.nanoTime();
            destrOp.destroy(solucionCandidata, journal, presenteUTC);
            long tMid = System.nanoTime();
            repairOp.repair(solucionCandidata, journal, presenteUTC);
            long tEnd = System.nanoTime();

            registrarTiempo(destroyTotalTimeNs, destroyCount, dName, tMid - tStart);
            registrarTiempo(repairTotalTimeNs, repairCount, rName, tEnd - tMid);

            sanitizarSolucion(solucionCandidata);
            
            long dMs = (tMid - tStart) / 1_000_000;
            long rMs = (tEnd - tMid) / 1_000_000;
            
            // Colorear tiempos lentos (> 100ms) en rojo
            String dStr = dMs > 100 ? RED + "D:" + dMs + "ms" + RESET : "D:" + dMs + "ms";
            String rStr = rMs > 100 ? RED + "R:" + rMs + "ms" + RESET : "R:" + rMs + "ms";
            
            sb.append(String.format("%s %s ", dStr, rStr));

            double costoCandidato = getCostoTotal(solucionCandidata, ocupacionCandidata);

            boolean esMejorGlobal = costoCandidato < costoMejor;
            boolean aceptar = false;
            double delta = costoCandidato - costoActual;
            String estado = RED + "X " + RESET; // X en rojo por defecto

            if (delta < 0) {
                aceptar = true;
                estado = GREEN + "OK" + RESET; // Mejora local
            } else {
                if (rnd.nextDouble() < Math.exp(-delta / temperatura)) {
                    aceptar = true;
                    estado = YELLOW + "SA" + RESET; // Aceptado por Simulated Annealing (empeora pero explora)
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
                    sb.append(GREEN + " **RÉCORD**" + RESET); // Nuevo récord global
                } else {
                    iteracionesSinMejora++;
                }
            } else {
                iteracionesSinMejora++;
                sb.append(String.format("-> %s (%,.0f)", estado, costoCandidato));
            }

            // Imprimir solo si es relevante (lento o mejora) para no saturar consola
            // O imprimir cada N iteraciones para heartbeat
            if (dMs + rMs > 50 || esMejorGlobal || iter % 100 == 0) {
                //System.out.println(sb.toString());
            }

            temperatura *= 0.95;

            if (iteracionesSinMejora >= maxStagnation) {
                if (mejorOcupacion.hayExcesoDeCapacidad()) {
                    iteracionesSinMejora = 0;
                    temperatura = costoActual * 0.20; // Re-calentamiento agresivo si estamos en crisis
                    System.out.println(sb.toString() + RED + " [REHEAT-CRISIS]" + RESET);
                } else {
                    System.out.println(GREEN + "🛑 EARLY STOP: Convergencia lograda." + RESET);
                    break;
                }
            }
        }

        // =========================================================================
        // 🛡️ FASE DE LEGALIZACIÓN FORZOSA (RF3 - FINAL CHECK - ITERATIVO)
        // =========================================================================
        // Intentamos limpiar cualquier violación residual de almacén al final
        int intentos = 0;
        int maxIntentosLegalizacion = 50;

        while (mejorOcupacion.hayExcesoDeCapacidad() && intentos > maxIntentosLegalizacion) {
            intentos++;
            System.out.println(YELLOW + "⚠️ ALERTA DE CRISIS (" + intentos + "/" + maxIntentosLegalizacion + "): " +
                    "Limpiando almacenes desbordados de forma agresiva..." + RESET);

            Journal finalJournal = new Journal(mejorOcupacion);
            
            // Eliminamos carga quirúrgicamente usando el operador de emergencia
            this.emergencyOperator.destroy(mejorSolucion, finalJournal, presenteUTC);
            
            // Recalcular costo para reflejar los pedidos eliminados (penalización alta)
            costoMejor = getCostoTotal(mejorSolucion, mejorOcupacion);
        }

        if (mejorOcupacion.hayExcesoDeCapacidad()) {
             System.out.println(RED + "💀 ERROR CRÍTICO: No se pudo legalizar el almacén tras " + maxIntentosLegalizacion + " intentos. La solución será inválida." + RESET);
        } else if (intentos > 0) {
             System.out.println(GREEN + "✅ Solución legalizada exitosamente tras " + intentos + " rondas de limpieza." + RESET);
             System.out.println(YELLOW + "💰 Nuevo Costo Legal (Alto por penalizaciones): " + String.format("%,.0f", costoMejor) + RESET);
        }
        // =========================================================================

        this.ocupacionPorAeropuerto.copiarDesde(mejorOcupacion);
        sanitizarSolucion(mejorSolucion);
        limpiarMapaGlobal(mejorSolucion);

        long tTotal = (System.nanoTime() - tInicioGlobal) / 1_000_000;
        
        imprimirReporteTiempos();

        if (true){
            // Revertir solución
            mejorSolucion = new SolucionProgramacion(solucionBackup);

            // Revertir ocupación global (la que el RunManager comparte entre ventanas)
            this.ocupacionPorAeropuerto.copiarDesde(ocupacionBackup);

            //sanitizarSolucion(mejorSolucion);
            //limpiarMapaGlobal(mejorSolucion);
        }

        System.out.println(CYAN + ">>> FIN. Tiempo: " + tTotal + "ms. Mejor Costo: " + String.format("%,.0f", costoMejor) + RESET);
        return mejorSolucion;
    }

    // --- MÉTODOS DE PROFILING ---
    private void registrarTiempo(Map<String, Long> timeMap, Map<String, Integer> countMap, String key, long timeNs) {
        timeMap.merge(key, timeNs, Long::sum);
        countMap.merge(key, 1, Integer::sum);
    }

    private void imprimirReporteTiempos() {
        System.out.println(BLUE + "\n📊 REPORTE DE RENDIMIENTO (TOP LENTOS) 📊" + RESET);
        System.out.println("----------------------------------------------------------------");
        System.out.println(String.format("%-30s | %-6s | %-10s | %-10s", "Operador", "Calls", "Total(ms)", "Avg(ms)"));
        System.out.println("----------------------------------------------------------------");
        
        Map<String, Double> promedios = new HashMap<>();
        
        Set<String> allOps = new HashSet<>(destroyTotalTimeNs.keySet());
        allOps.addAll(repairTotalTimeNs.keySet());

        for(String op : allOps) {
            long totalNs = destroyTotalTimeNs.getOrDefault(op, 0L) + repairTotalTimeNs.getOrDefault(op, 0L);
            int count = destroyCount.getOrDefault(op, 0) + repairCount.getOrDefault(op, 0);
            if(count > 0) promedios.put(op, (double)totalNs / count / 1_000_000.0);
        }

        promedios.entrySet().stream()
            .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue())) // Ordenar por promedio descendente (más lentos primero)
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

    private boolean hayCargaAsignada(SolucionProgramacion sol) {
        if (sol == null) return false;
        if (sol.getCargaPorVuelo() == null) return false;
        Map<VueloProgramadoId, Integer> asignado = sol.getCargaPorVuelo().getAsignado();
        if (asignado == null || asignado.isEmpty()) return false;

        for (Integer v : asignado.values()) {
            if (v != null && v > 3000) return true;
        }
        return false;
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