package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators;

import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.*;

import java.time.Instant;
import java.util.*;

import lombok.RequiredArgsConstructor;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.ALNS;

@RequiredArgsConstructor
public class RandomRemoval implements DestructionOperator {
    private final int porcentaje;
    private final Random rnd = new Random();

    @Override
    public void destroy(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {

        //System.out.println("Destroying RandomRemoval " + presenteUTC);

        Map<Integer, PlanPedido> planes = s.asMap();  // << usar asMap()
        List<Integer> pedidos = new ArrayList<>(planes.keySet());

        if (pedidos.isEmpty()) return;

        int n = Math.max(1, pedidos.size() * porcentaje / 100); // al menos 1
        //Collections.shuffle(pedidos, rnd); // evitar repetidos

        for (int i = 0; i < n; i++) {
            int id = pedidos.get(i);
            PlanPedido plan = planes.get(id);

            if (plan == null) continue;

            boolean liberado = liberarRecursosDePlan(plan, presenteUTC, s, journal);
            if (!liberado){
                continue;
            };
            plan.limpiarTramos();
            System.out.println("Se removieron las rutas del pedido id:" + plan.getIdPedido());
            continue;
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

                journal.liberar(apEscala, arrPrev, depNext, q);
            }

            // 2) liberamos las 2h de espera en el destino final
            TramoAsignado ultimo = ruta.getTramos().get(ruta.getTramos().size() - 1);
            Instant llegadaFinal = ultimo.getVuelo().getLlegadaUtc();

            journal.liberar(plan.getAeropuertoDestino(), llegadaFinal, llegadaFinal.plus(java.time.Duration.ofHours(2)), q);

            // 3) liberamos los vuelos (tramos) de la ruta
            for (TramoAsignado t : ruta.getTramos()) {
                s.getCargaPorVuelo().asignar(t.getVuelo(), -q);
            }

        }

        return true;
    }

}
