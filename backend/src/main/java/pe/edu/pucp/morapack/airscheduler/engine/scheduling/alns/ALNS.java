package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns;

import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.ImpresorSolucion;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators.DestructionOperator;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators.RepairOperator;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.OcupacionPorAeropuerto;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.SolucionProgramacion;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ALNS {

    private final VuelosTEG teg;
    private final List<Pedido> pedidos;
    private final List<DestructionOperator> destructions;
    private final List<RepairOperator> repairs;
    private final Instant presenteUTC;
    private final OcupacionPorAeropuerto ocupacionPorAeropuerto;

    private final Random rnd = new Random();        // RNG compartido para selección de operadores
    private final int maxIter = 3;                 // iteraciones máximas
    private final double tasaCambio = 0.3;          // probabilidad de aceptar peores soluciones

    public static final File logFile = new File("journal.log");

    public SolucionProgramacion ejecutar(SolucionProgramacion solucionInicial) {

        // Copias profundas desde el inicio
        SolucionProgramacion mejorSolucion = new SolucionProgramacion(solucionInicial);
        SolucionProgramacion solucionActual = new SolucionProgramacion(solucionInicial);
        OcupacionPorAeropuerto ocupacionPorAeropuerto1 = new OcupacionPorAeropuerto(ocupacionPorAeropuerto);

        for (int iter = 0; iter < maxIter; iter++) {
            System.out.println("---------Iteración ALNS " + (iter + 1) + "---------");

            // Seleccionar operadores aleatorios
            DestructionOperator destrOp = destructions.get(rnd.nextInt(destructions.size()));
            RepairOperator repairOp = repairs.get(rnd.nextInt(repairs.size()));

            // Copia profunda de la solución actual
            SolucionProgramacion nuevaSol = new SolucionProgramacion(solucionActual);
            //nuevaSol.getPlanPorPedido().get(1).limpiarTramos();
            //solucionActual.getPlanPorPedido().get(1).getRutas();

            Journal journal = new Journal(ocupacionPorAeropuerto);

            // Aplicar destrucción
            destrOp.destroy(nuevaSol, journal, presenteUTC);

            // Aplicar reparación
            repairOp.repair(nuevaSol, journal, presenteUTC);

            // Evaluar costos
            double costoNueva = getCostoTotal(nuevaSol);
            double costoActual = getCostoTotal(solucionActual);
            double costoMejor = getCostoTotal(mejorSolucion);

            // Actualizar mejor solución
            if (costoNueva < costoMejor) {
                mejorSolucion = new SolucionProgramacion(nuevaSol); // copia profunda
                //System.out.println("Cambio de mejor solución en iteración " + iter);
            }
            // Aceptar nueva solución (según criterio)
            boolean aceptar = (costoNueva < costoActual)
                || (costoNueva == costoActual && rnd.nextDouble() < 0.25)
                || (rnd.nextDouble() < tasaCambio);
            //boolean aceptar = (costoNueva < costoActual) || (rnd.nextDouble() < tasaCambio);
            if (aceptar) {
                journal.commit();
                solucionActual = new SolucionProgramacion(nuevaSol); // copia profunda
                /// Para prueba (esto no se usa):
                ocupacionPorAeropuerto1 = new OcupacionPorAeropuerto(journal.occ);
            }
            else {
                journal.rollback();
                /// Para prueba (esto no se usa en el algoritmo):
                verificarImprimirIguales(journal.occ, ocupacionPorAeropuerto1);
            }

            //ImpresorSolucion.imprimirEnArchivo(solucionActual, "out/solucionActualALNS.txt",presenteUTC);
            //ImpresorSolucion.imprimirEnArchivo(nuevaSol, "out/solucionNuevaALNS.txt",presenteUTC);
            //ImpresorSolucion.imprimirEnArchivo(mejorSolucion, "out/mejorSolucionALNS.txt",presenteUTC);

        }

        return mejorSolucion;
    }

    private double getCostoTotal(SolucionProgramacion sol) {
        double costo = 0;

        // Penalización por pedidos incompletos (10 por cada uno)
        costo += sol.pedidosIncompletos().size() * 10;

        // Penalización por incumplimiento de capacidad
        if (!sol.respetaCapacidadesVuelos()) {
            costo += 50;
        }

        // Penalización por incumplimiento SLA 48h (cada pedido fuera suma 20)
        long fueraSLA48 = sol.getPlanPorPedido().values().stream()
                .filter(p -> !p.respetaSLA(Duration.ofHours(48)))
                .count();
        costo += fueraSLA48 * 20;

        // Penalización por incumplimiento SLA con pickup de 2h (cada pedido fuera suma 15)
        long fueraPickup = sol.getPlanPorPedido().values().stream()
                .filter(p -> !p.respetaSLAConPickup(Duration.ofHours(46))) // SLA48 - 2h
                .count();
        costo += fueraPickup * 15;

        return costo;
    }

    public static void log(String message) {
        try (FileWriter fw = new FileWriter(logFile, true);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter out = new PrintWriter(bw)) {
            out.println("[" + Instant.now() + "] " + message);
        } catch (IOException e) {
            System.err.println("Error al escribir en el log: " + e.getMessage());
        }
    }

    /// Dado que al destruir/construir rutas ahora vamos a cambiar las ocupaciones de los aeropuerto/vuelos, podemos
    /// construir una ruta que finalmente no usemos. Para no "chancar" el libro global de ocupaciones, usamos Journal

    public static final class Journal {
        private final OcupacionPorAeropuerto occ;
        private final Deque<Runnable> undo = new ArrayDeque<>();

        public Journal(OcupacionPorAeropuerto occ) { this.occ = occ; }

        public OcupacionPorAeropuerto getOcc() {
            return this.occ;
        }

        // En vez de llamar occ.reservar directamente, los operadores llaman:
        public void reservar(String ap, Instant ini, Instant fin, int q) {
            log("✔\uFE0F Vamos a RESERVAR " + q + " (" + ini + " - " + fin + ") en " + ap);

            occ.reservar(ap, ini, fin, q);
            undo.push(() -> {
                log("↩️ Aplicando: LIBERAR " + q + " (" + ini + " - " + fin + ") en " + ap);
                occ.liberar(ap, ini, fin, q);
            }); // inversa
        }
        public void liberar(String ap, Instant ini, Instant fin, int q) {
            log("✖\uFE0F Vamos a LIBERAR " + q + " (" + ini + " - " + fin + ") en " + ap);

            occ.liberar(ap, ini, fin, q);
            undo.push(() -> {
                log("↩️ Aplicando: RESERVAR " + q + " (" + ini + " - " + fin + ") en " + ap);
                occ.reservar(ap, ini, fin, q);
            }); // inversa
        }

        public void rollback() {
            log("❌ Solucíón DENEGADA. Ejecutando rollback...");
            while (!undo.isEmpty()) undo.pop().run();
            log("---------------------------------------------------------------");
        }

        public void commit() {
            log("✅ Solucíón ACEPTADA. Nada que deshacer.");
            undo.clear();
            log("---------------------------------------------------------------");
        } // nada que deshacer



    }

    /// TODO0 ESTO DE ABAJO LO CREÉ PARA VERIFICAR QUE EL JOURNAL VUELVE CORRECTAMENTE
    /// AL ESTADO ANTERIOR CUANDO EJECUTAMOS UN ROLLBACK:
    ///
    /// pd: hasta ahora, ningun rollback ha fallado (vuelve correctamente al estado anterior)

    public void verificarImprimirIguales(OcupacionPorAeropuerto o1, OcupacionPorAeropuerto o2){
        if (sonIguales(o1, o2)){
            log("✅✅ Las 2 soluciones son iguales! Rollback exitoso. ✅✅");
        }
        else {
            log("❌❌ Las 2 soluciones NO son iguales! Rollback fallido. ❌❌");
        }
    }

    public static boolean sonIguales(OcupacionPorAeropuerto o1, OcupacionPorAeropuerto o2) {
        if (o1 == o2) return true;
        if (o1 == null || o2 == null) return false;

        // Compara ambos mapas: eventos y checkpoints
        return mapasIguales(o1.getEventos(), o2.getEventos()) &&
                mapasIguales(o1.getCheckpoints(), o2.getCheckpoints());
    }

    private static boolean mapasIguales(Map<String, TreeMap<Instant, Integer>> m1,
                                        Map<String, TreeMap<Instant, Integer>> m2) {
        if (m1.size() != m2.size()) return false;

        for (Map.Entry<String, TreeMap<Instant, Integer>> entry : m1.entrySet()) {
            String key = entry.getKey();
            TreeMap<Instant, Integer> v1 = entry.getValue();
            TreeMap<Instant, Integer> v2 = m2.get(key);

            if (v2 == null) return false;
            if (!Objects.equals(v1, v2)) return false; // TreeMap ya implementa equals correctamente
        }

        return true;
    }

}
