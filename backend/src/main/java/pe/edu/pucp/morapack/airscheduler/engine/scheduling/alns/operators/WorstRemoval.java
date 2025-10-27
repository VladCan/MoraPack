package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import java.time.Instant;
import java.util.*;

import lombok.RequiredArgsConstructor;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;

@RequiredArgsConstructor
public class WorstRemoval implements DestructionOperator {
    private final int porcentaje;

    @Override
    public void destroy(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {
        Map<Integer, PlanPedido> planes = s.getPlanPorPedido();
        if (planes.isEmpty()) return;

        // “Peores” = más tramos (más complejos)
        List<PlanPedido> lista = new ArrayList<>(planes.values());
        lista.sort(Comparator.comparingInt((PlanPedido p) -> p.getTramosAplanados().size()).reversed());

        int n = Math.max(1, lista.size() * porcentaje / 100);

        for (int i = 0; i < n && i < lista.size(); i++) {
            PlanPedido plan = lista.get(i);
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

                if (!salidaPrimero.isAfter(presenteUTC)) {
                    keep.add(ruta); // ya despegó, se mantiene
                    continue;
                }

                // eliminar esta ruta
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
