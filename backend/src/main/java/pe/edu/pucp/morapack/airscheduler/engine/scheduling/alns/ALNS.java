package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns;

import lombok.RequiredArgsConstructor;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators.DestructionOperator;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators.RepairOperator;
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

    private final Random rnd = new Random();
    
    private final int maxIter = 200;       
    private final double startTemperatureRatio = 0.05; 
    private final int maxStagnation = 35; 

    // 🛑 JERARQUÍA DE PENALIZACIONES (Soft Constraints)
    private static final double PEN_CAPACIDAD   = 1_000_000_000.0; // Físicamente imposible
    private static final double PEN_INCOMPLETO  =   100_000_000.0; // Peor escenario: Cliente sin producto
    private static final double PEN_SLA         =    10_000_000.0; // Mal escenario: Cliente enojado (tarde)

    public SolucionProgramacion ejecutar(SolucionProgramacion solucionInicial) {
        System.out.println("=================================================");
        System.out.println(">>> INICIANDO ALNS (MODO: SOFT-CONSTRAINT) <<<");
        System.out.println(">>> Prioridad 1: Entregar TODO. Prioridad 2: A tiempo. <<<");
        System.out.println("=================================================");
        
        SolucionProgramacion solucionBase = solucionInicial;
        SolucionProgramacion mejorSolucion = new SolucionProgramacion(solucionInicial);

        double costoActual = getCostoTotal(solucionBase);
        double costoMejor = costoActual;
        double temperatura = costoActual * startTemperatureRatio;

        /// Para ya no usar commit y rollback

        OcupacionPorAeropuerto ocupacionBase = this.ocupacionPorAeropuerto.copiaProfunda();
        OcupacionPorAeropuerto mejorOcupacion = this.ocupacionPorAeropuerto.copiaProfunda();

        int iteracionesSinMejora = 0;

        System.out.println(">> Costo Inicial: " + String.format("%,.0f", costoActual));

        long tInicioGlobal = System.nanoTime();
        long tiempoLimiteNs = 30L * 1_000_000_000L;

        for (int iter = 0; iter < maxIter; iter++) {

            if ((System.nanoTime() - tInicioGlobal) > tiempoLimiteNs) {
                System.out.println("🛑 EARLY STOP: Tiempo límite excedido (> 30s). Retornando mejor solución encontrada.");
                break; // Rompe el bucle y va directo al return final
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("[Iter %02d] ", iter));

            //SolucionProgramacion solucionCandidata = new SolucionProgramacion(solucionBase);
            //Journal journal = new Journal(ocupacionPorAeropuerto);

            SolucionProgramacion solucionCandidata = new SolucionProgramacion(solucionBase);

            // Copia de la ocupación base para esta iteración
            OcupacionPorAeropuerto ocupacionCandidata = ocupacionBase.copiaProfunda();

            // El Journal trabaja SOBRE la ocupación candidata,
            // no sobre la global:
            Journal journal = new Journal(ocupacionCandidata);

            DestructionOperator destrOp = destructions.get(rnd.nextInt(destructions.size()));
            RepairOperator repairOp = repairs.get(rnd.nextInt(repairs.size()));

            String opTag = String.format("[%s->%s]", 
                destrOp.getClass().getSimpleName().substring(0, 4), 
                repairOp.getClass().getSimpleName().substring(0, 4));
            sb.append(String.format("%-12s ", opTag));

            long t1 = System.nanoTime();
            destrOp.destroy(solucionCandidata, journal, presenteUTC);
            repairOp.repair(solucionCandidata, journal, presenteUTC);
            long tMod = (System.nanoTime() - t1) / 1_000_000;
            sb.append(String.format("T:%3dms ", tMod));

            double costoCandidato = getCostoTotal(solucionCandidata);
            
            boolean esMejorGlobal = costoCandidato < costoMejor;
            boolean aceptar = false;
            String estadoDecision = "X";

            double delta = costoCandidato - costoActual;

            if (delta < 0) {
                aceptar = true;
                estadoDecision = "OK";
            } else {
                double probabilidad = Math.exp(-delta / temperatura);
                if (rnd.nextDouble() < probabilidad) {
                    aceptar = true;
                    estadoDecision = "SA"; 
                }
            }

            if (aceptar) {
                solucionBase = solucionCandidata;
                ocupacionBase = ocupacionCandidata; //Esto es la clave
                costoActual = costoCandidato;
                sb.append(String.format("-> %s (%,.0f)", estadoDecision, costoActual));

                if (esMejorGlobal) {
                    mejorSolucion = new SolucionProgramacion(solucionBase);
                    // Guardamos una copia de la ocupación asociada a la mejor solución
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
            //System.out.println(sb.toString());

            if (iteracionesSinMejora >= maxStagnation) {
                System.out.println("🛑 EARLY STOP: Convergencia detectada.");
                break;
            }
        }

        this.ocupacionPorAeropuerto.copiarDesde(mejorOcupacion);
        
        long tTotal = (System.nanoTime() - tInicioGlobal) / 1_000_000;
        System.out.println(">>> FIN. Tiempo: " + tTotal + "ms. Mejor Costo: " + String.format("%,.0f", costoMejor));
        return mejorSolucion;
    }

    private double getCostoTotal(SolucionProgramacion sol) {
        double costo = 0;

        if (!sol.respetaCapacidadesVuelos()) {
            return PEN_CAPACIDAD; 
        }

        Collection<PlanPedido> planes = sol.getPlanPorPedido().values();
        for (PlanPedido p : planes) {
            if (p.getRutas() == null || p.getRutas().isEmpty()) {
                costo += PEN_INCOMPLETO;
                continue;
            }

            boolean tieneRutaMala = false;
            Instant ultimaLlegadaGlobal = Instant.MIN; 

            for (RutaAsignada r : p.getRutas()) {
                if (r.getTramos() == null || r.getTramos().isEmpty()) {
                    tieneRutaMala = true;
                    break;
                }
                Instant llegadaRuta = r.ultimaLlegada();
                if (llegadaRuta != null && llegadaRuta.isAfter(ultimaLlegadaGlobal)) {
                    ultimaLlegadaGlobal = llegadaRuta;
                }
            }

            if (tieneRutaMala || ultimaLlegadaGlobal == Instant.MIN) {
                costo += PEN_INCOMPLETO;
                continue;
            }

            // Verificación Parcial de Cantidad (Si es Split, debe sumar el total)
            int cantidadAsignada = p.getRutas().stream().mapToInt(RutaAsignada::getCantidad).sum();
            if (cantidadAsignada < p.getDemanda()) {
                // Penalizamos proporcionalmente lo que falta
                double faltante = p.getDemanda() - cantidadAsignada;
                costo += (faltante * 10_000.0); // Costo alto por unidad faltante
            }

            // SLA 46h
            Instant deadline = p.getCreadoUtc().plus(Duration.ofHours(46));
            if (ultimaLlegadaGlobal.isAfter(deadline)) {
                 costo += PEN_SLA; // Penalización fuerte pero MENOR que no entregar
            }

            long minutosTransito = Duration.between(p.getCreadoUtc(), ultimaLlegadaGlobal).toMinutes();
            costo += minutosTransito;
        }

        return costo;
    }

    public static final class Journal {
        private final OcupacionPorAeropuerto occ;
        private final Deque<Runnable> undoStack = new ArrayDeque<>(100); 

        public Journal(OcupacionPorAeropuerto occ) { this.occ = occ; }
        public OcupacionPorAeropuerto getOcc() { return this.occ; }

        public void reservar(String ap, Instant ini, Instant fin, int q) {
            occ.reservar(ap, ini, fin, q);
            undoStack.push(() -> occ.liberar(ap, ini, fin, q));
        }

        public void liberar(String ap, Instant ini, Instant fin, int q) {
            occ.liberar(ap, ini, fin, q);
            undoStack.push(() -> occ.reservar(ap, ini, fin, q));
        }

        public void rollback() {
            while (!undoStack.isEmpty()) undoStack.pop().run();
        }

        public void commit() {
            undoStack.clear();
        }
    }
}