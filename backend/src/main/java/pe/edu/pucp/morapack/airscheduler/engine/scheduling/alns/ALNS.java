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

@RequiredArgsConstructor
public class ALNS {

    private final VuelosTEG teg;
    private final List<Pedido> pedidos;
    private final List<DestructionOperator> destructions;
    private final List<RepairOperator> repairs;
    private final Instant presenteUTC;
    private final OcupacionPorAeropuerto ocupacionPorAeropuerto;
    
    // IMPORTANTE: Asegúrate de pasar esto desde el Test o donde llames al ALNS
    private final AeropuertosMap aeropuertosMap; 

    private final Random rnd = new Random();
    
    private final int maxIter = 2500;          
    private final double startTemperatureRatio = 0.05; 
    private final int maxStagnation = 200;     

    private static final double PEN_CAPACIDAD_VUELO = 1_000_000_000.0; 
    private static final double PEN_INCOMPLETO      =   100_000_000.0; 
    private static final double PEN_CAPACIDAD_BODEGA_BASE = 200_000_000.0; 
    private static final double PEN_SLA             =    10_000_000.0; 

    private WarehouseSmartRemoval emergencyOperator;

    public SolucionProgramacion ejecutar(SolucionProgramacion solucionInicial) {
        System.out.println("=================================================");
        System.out.println(">>> INICIANDO ALNS (MODO: ANTI-OVERFLOW) <<<");
        System.out.println("=================================================");
        
        // Inicializar operador de emergencia con el mapa corregido
        this.emergencyOperator = new WarehouseSmartRemoval(this.aeropuertosMap);

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

            // Usar operador cirujano si hay crisis
            if (hayCrisisBodega && rnd.nextDouble() < 0.70) {
                destrOp = this.emergencyOperator;
                sb.append("[🚑 EMERG] ");
            } else {
                destrOp = destructions.get(rnd.nextInt(destructions.size()));
            }

            RepairOperator repairOp = repairs.get(rnd.nextInt(repairs.size()));

            String opTag = String.format("[%s->%s]", 
                destrOp.getClass().getSimpleName().substring(0, 4), 
                repairOp.getClass().getSimpleName().substring(0, 4));
            sb.append(String.format("%-12s ", opTag));

            long t1 = System.nanoTime();
            
            destrOp.destroy(solucionCandidata, journal, presenteUTC);
            repairOp.repair(solucionCandidata, journal, presenteUTC);
            
            sanitizarSolucion(solucionCandidata);

            long tMod = (System.nanoTime() - t1) / 1_000_000;
            sb.append(String.format("T:%3dms ", tMod));

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
            
            temperatura *= 0.95;

            // Reheating para crisis de bodega persistente
            if (iteracionesSinMejora >= maxStagnation) {
                if (mejorOcupacion.hayExcesoDeCapacidad()) {
                    iteracionesSinMejora = 0;
                    temperatura = costoActual * 0.10; 
                    sb.append(" [REHEAT]");
                } else {
                    System.out.println("🛑 EARLY STOP: Convergencia.");
                    break;
                }
            }
        }

        this.ocupacionPorAeropuerto.copiarDesde(mejorOcupacion);
        
        sanitizarSolucion(mejorSolucion);
        limpiarMapaGlobal(mejorSolucion);

        long tTotal = (System.nanoTime() - tInicioGlobal) / 1_000_000;
        System.out.println(">>> FIN. Tiempo: " + tTotal + "ms. Mejor Costo: " + String.format("%,.0f", costoMejor));
        return mejorSolucion;
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
                costo += (faltante * 50_000.0); 
            }

            Instant deadline = p.getCreadoUtc().plus(Duration.ofHours(46));
            if (ultimaLlegadaGlobal.isAfter(deadline)) {
                 costo += PEN_SLA; 
                 long horasTarde = Duration.between(deadline, ultimaLlegadaGlobal).toHours();
                 costo += (horasTarde * 100_000); 
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