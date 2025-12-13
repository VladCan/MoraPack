package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import lombok.RequiredArgsConstructor;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;

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
        List<Integer> indicesDisponibles = new ArrayList<>(totalPedidos);
        for (int i = 0; i < totalPedidos; i++) indicesDisponibles.add(i);

        List<PlanPedido> victimas = new ArrayList<>();
        int tournamentSize = 4; 

        while (victimas.size() < target && !indicesDisponibles.isEmpty()) {
            int mejorCandidatoIdxEnLista = -1;
            double maxCosto = -1.0; 
            
            int iteraciones = Math.min(tournamentSize, indicesDisponibles.size());
            // Array temporal para guardar los indices del torneo
            int[] indicesTorneo = new int[iteraciones];

            for (int t = 0; t < iteraciones; t++) {
                int randPos = rnd.nextInt(indicesDisponibles.size());
                indicesTorneo[t] = randPos;
                int indiceReal = indicesDisponibles.get(randPos);

                PlanPedido candidato = todosLosPlanes.get(indiceReal);
                double costo = calcularCostoTiempo(candidato);

                if (costo > maxCosto) {
                    maxCosto = costo;
                    mejorCandidatoIdxEnLista = randPos;
                }
            }
            
            // Si por alguna razón no se seleccionó (ej. lista vacía), salir
            if (mejorCandidatoIdxEnLista == -1) break;

            int idRealVictima = indicesDisponibles.get(mejorCandidatoIdxEnLista);
            victimas.add(todosLosPlanes.get(idRealVictima));

            // Swap & Remove
            int lastPos = indicesDisponibles.size() - 1;
            if (mejorCandidatoIdxEnLista != lastPos) {
                indicesDisponibles.set(mejorCandidatoIdxEnLista, indicesDisponibles.get(lastPos));
            }
            indicesDisponibles.remove(lastPos);
        }

        // Ejecutar destrucción
        for (PlanPedido plan : victimas) {
            List<RutaAsignada> rutas = plan.getRutas();
            if (rutas == null || rutas.isEmpty()) continue;

            List<RutaAsignada> keep = new ArrayList<>();
            boolean cambio = false;

            for (RutaAsignada ruta : rutas) {
                if (ruta.getTramos() == null || ruta.getTramos().isEmpty()) continue;

                TramoAsignado primero = ruta.getTramos().get(0);
                Instant salidaPrimero = primero.getVuelo().getSalidaUtc();

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
                        .rutas(new ArrayList<>(keep)) // <--- LISTA MUTABLE
                        .build();
                s.getPlanPorPedido().put(nuevo.getIdPedido(), nuevo);
            }
        }
    }

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
        return (double) Duration.between(plan.getCreadoUtc(), ultimaLlegada).toMinutes();
    }

    private void liberarRuta(PlanPedido plan, RutaAsignada ruta, ALNS.Journal journal, SolucionProgramacion s) {
        int q = ruta.getCantidad();
        if (q <= 0) return;

        List<TramoAsignado> tr = ruta.getTramos();
        for (int i = 0; i < tr.size(); i++) {
            TramoAsignado t = tr.get(i);
            VueloProgramadoId v = t.getVuelo();

            Instant oriIni = (i == 0) ? plan.getCreadoUtc() : tr.get(i - 1).getVuelo().getLlegadaUtc();
            Instant oriFin = v.getSalidaUtc();
            if (oriIni != null && oriFin != null && oriFin.isAfter(oriIni)) {
                journal.liberar(v.getOrigen(), oriIni, oriFin, q);
            }

            Instant dstIni = v.getLlegadaUtc();
            Instant dstFin = (i + 1 < tr.size())
                    ? tr.get(i + 1).getVuelo().getSalidaUtc()
                    : (dstIni == null ? null : dstIni.plus(java.time.Duration.ofHours(2)));
            if (dstIni != null && dstFin != null && dstFin.isAfter(dstIni)) {
                journal.liberar(v.getDestino(), dstIni, dstFin, q);
            }

            s.getCargaPorVuelo().asignar(v, -q);
        }
    }
}