package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;

@RequiredArgsConstructor
public class WorstRemoval implements DestructionOperator {
    private final int porcentaje;

    @Override
    public void destroy(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {
        //System.out.println("Destroying WorstRemoval " + presenteUTC);

        Map<Integer, PlanPedido> planes = s.getPlanPorPedido();
        if (planes.isEmpty()) return;

        // Heurística: ordenar planes por cantidad total de tramos 
        // (más "complejos" primero)
        List<PlanPedido> listaPlanes = new ArrayList<>(planes.values());
        listaPlanes.sort(Comparator.comparingInt(
                p -> -p.getTramosAplanados().size()
        ));

        int n = listaPlanes.size() * porcentaje / 100;

        for (int i = 0; i < n && i < listaPlanes.size(); i++) {
            PlanPedido plan = listaPlanes.get(i);
            // destruir = vaciar todos los tramos de sus rutas

            if (plan == null) continue;

            boolean liberado = liberarRecursosDePlan(plan, presenteUTC, s, journal);
            if (!liberado){
                i--;
                continue;
            };
            plan.limpiarTramos(); 
        }
    }

    public boolean liberarRecursosDePlan(PlanPedido plan, Instant presenteUTC, SolucionProgramacion s, ALNS.Journal journal){
        List<RutaAsignada> rutas = plan.getRutas();

        if (rutas == null || rutas.isEmpty()) return false;

        for (RutaAsignada ruta : rutas) {
            // 1) si no hay tramos, no hay nada que liberar en esta ruta
            if (ruta.getTramos() == null || ruta.getTramos().isEmpty()) continue;

            TramoAsignado primero = ruta.getTramos().get(0);
            Instant salida = primero.getVuelo().getSalidaUtc();

            //Toda la ruta ya despegó, confirmamos que no se destruye
            if (!salida.isAfter(presenteUTC)) return false;

            int q = ruta.getCantidad();

            // 1) liberaramos las escalas
            for (int i = 0; i < ruta.getTramos().size() - 1; i++){
                TramoAsignado tPrev = ruta.getTramos().get(i);
                TramoAsignado tNext = ruta.getTramos().get(i + 1);
                String apEscala = tPrev.getVuelo().getDestino();
                Instant arrPrev = tPrev.getVuelo().getLlegadaUtc();
                Instant depNext = tNext.getVuelo().getSalidaUtc();

                ///%%%%%Failing aquí
                journal.liberar(apEscala, arrPrev, depNext, q);
            }

            // 2) liberamos las 2h de espera en el destino final
            TramoAsignado ultimo = ruta.getTramos().get(ruta.getTramos().size() - 1);
            Instant llegadaFinal = ultimo.getVuelo().getLlegadaUtc();
            journal.liberar(plan.getAeropuertoDestino(), llegadaFinal, llegadaFinal.plus(java.time.Duration.ofHours(2)), q);

            for (TramoAsignado t : ruta.getTramos()) {
                s.getCargaPorVuelo().asignar(t.getVuelo(), -q);
            }

        }

        return true;
    }

}
