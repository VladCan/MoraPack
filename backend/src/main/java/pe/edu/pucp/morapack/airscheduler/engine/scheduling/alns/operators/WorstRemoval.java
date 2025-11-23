package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import lombok.RequiredArgsConstructor;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;

/**
 * WorstRemoval (Versión Time-Attack):
 * - El criterio de "Peor" ahora es la DURACIÓN TOTAL del viaje.
 * - Elimina los pedidos que tardan más en llegar para intentar encontrarles atajos.
 */
@RequiredArgsConstructor
public class WorstRemoval implements DestructionOperator {
    
    private final int porcentaje;
    private final Random rnd = new Random(); 

    @Override
    public void destroy(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {
        List<PlanPedido> todosLosPlanes = new ArrayList<>(s.getPlanPorPedido().values());
        int totalPedidos = todosLosPlanes.size();
        if (totalPedidos == 0) return;

        int target = Math.max(1, totalPedidos * porcentaje / 100);

        // Lista de índices para selección eficiente (Swap & Remove)
        List<Integer> indicesDisponibles = new ArrayList<>(totalPedidos);
        for (int i = 0; i < totalPedidos; i++) {
            indicesDisponibles.add(i);
        }

        List<PlanPedido> victimas = new ArrayList<>();
        int tournamentSize = 4; 

        while (victimas.size() < target && !indicesDisponibles.isEmpty()) {
            
            int mejorCandidatoIdxEnLista = -1;
            // Cambiamos a double para medir tiempo (minutos)
            double maxCosto = -1.0; 
            
            int iteraciones = Math.min(tournamentSize, indicesDisponibles.size());
            int[] candidatosTorneo = new int[iteraciones];

            for (int t = 0; t < iteraciones; t++) {
                int randPos = rnd.nextInt(indicesDisponibles.size());
                int indiceReal = indicesDisponibles.get(randPos);
                candidatosTorneo[t] = randPos;

                PlanPedido candidato = todosLosPlanes.get(indiceReal);
                
                // 🛑 CAMBIO CLAVE: El costo ahora es TIEMPO, no tramos.
                double costo = calcularCostoTiempo(candidato);

                if (costo > maxCosto) {
                    maxCosto = costo;
                    mejorCandidatoIdxEnLista = randPos;
                }
            }

            if (mejorCandidatoIdxEnLista == -1) {
                mejorCandidatoIdxEnLista = candidatosTorneo[0];
            }

            int idRealVictima = indicesDisponibles.get(mejorCandidatoIdxEnLista);
            victimas.add(todosLosPlanes.get(idRealVictima));

            // Swap & Remove para eficiencia O(1)
            int lastPos = indicesDisponibles.size() - 1;
            indicesDisponibles.set(mejorCandidatoIdxEnLista, indicesDisponibles.get(lastPos));
            indicesDisponibles.remove(lastPos);
        }

        // Ejecutar destrucción
        for (PlanPedido plan : victimas) {
            List<RutaAsignada> rutas = plan.getRutas();
            if (rutas == null || rutas.isEmpty()) continue;

            List<RutaAsignada> keep = new ArrayList<>();
            boolean cambio = false;

            for (RutaAsignada ruta : rutas) {
                if (ruta.getTramos() == null || ruta.getTramos().isEmpty()) {
                    keep.add(ruta);
                    continue;
                }

                TramoAsignado primero = ruta.getTramos().get(0);
                Instant salidaPrimero = primero.getVuelo().getSalidaUtc();

                // Candado temporal: Si ya salió, no se puede tocar
                if (!salidaPrimero.isAfter(presenteUTC)) {
                    keep.add(ruta); 
                    continue;
                }

                liberarRuta(plan, ruta, journal, s);
                cambio = true;
            }

            if (cambio) {
                PlanPedido nuevo = PlanPedido.builder()
                        .idPedido(plan.getIdPedido())
                        .aeropuertoDestino(plan.getAeropuertoDestino())
                        .creadoUtc(plan.getCreadoUtc())
                        .demanda(plan.getDemanda())
                        .rutas(keep)
                        .build();
                s.getPlanPorPedido().put(nuevo.getIdPedido(), nuevo);
            }
        }
    }

    /**
     * Calcula qué tan "mala" es una solución basado en TIEMPO.
     * Retorna la duración en minutos desde creación hasta entrega.
     */
    private double calcularCostoTiempo(PlanPedido plan) {
        if (plan.getRutas() == null || plan.getRutas().isEmpty()) return 0.0;
        
        Instant ultimaLlegada = null;
        for (RutaAsignada r : plan.getRutas()) {
            Instant llegada = r.ultimaLlegada();
            if (llegada != null) {
                if (ultimaLlegada == null || llegada.isAfter(ultimaLlegada)) {
                    ultimaLlegada = llegada;
                }
            }
        }
        
        if (ultimaLlegada == null) return 0.0;

        // Duración en minutos. Cuanto más tarde, mayor costo -> más probabilidad de ser eliminado.
        return (double) Duration.between(plan.getCreadoUtc(), ultimaLlegada).toMinutes();
    }

    private void liberarRuta(PlanPedido plan, RutaAsignada ruta, ALNS.Journal journal, SolucionProgramacion s) {
        int q = ruta.getCantidad();
        List<TramoAsignado> tr = ruta.getTramos();

        for (int i = 0; i < tr.size(); i++) {
            TramoAsignado t = tr.get(i);
            VueloProgramadoId v = t.getVuelo();

            Instant oriIni = (i == 0) ? plan.getCreadoUtc() : tr.get(i - 1).getVuelo().getLlegadaUtc();
            Instant oriFin = v.getSalidaUtc();
            if (oriIni != null && oriFin != null && !oriFin.isBefore(oriIni)) {
                journal.liberar(v.getOrigen(), oriIni, oriFin, q);
            }

            Instant dstIni = v.getLlegadaUtc();
            Instant dstFin = (i + 1 < tr.size())
                    ? tr.get(i + 1).getVuelo().getSalidaUtc()
                    : (dstIni == null ? null : dstIni.plus(java.time.Duration.ofHours(2)));
            if (dstIni != null && dstFin != null && !dstFin.isBefore(dstIni)) {
                journal.liberar(v.getDestino(), dstIni, dstFin, q);
            }

            s.getCargaPorVuelo().asignar(v, -q);
        }
    }
}