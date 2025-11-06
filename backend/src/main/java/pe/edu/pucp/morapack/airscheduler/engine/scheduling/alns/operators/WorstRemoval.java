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

        if (ruta == null || ruta.getTramos() == null || ruta.getTramos().isEmpty()) return;

        final int qRuta = ruta.getCantidad();                  // lo que reservaste en occ
        final List<TramoAsignado> tr = ruta.getTramos();

        // 1) ESCALAS: liberar [llegada(tr i), salida(tr i+1)) en aeropuerto destino del tramo i
        for (int i = 0; i < tr.size() - 1; i++) {
            TramoAsignado tPrev = tr.get(i);
            TramoAsignado tNext = tr.get(i + 1);

            String apEscala = tPrev.getVuelo().getDestino();
            Instant ini = tPrev.getLlegadaUtc();
            Instant fin = tNext.getVuelo().getSalidaUtc();

            if (ini != null && fin != null && ini.isBefore(fin)) {
                journal.liberar(apEscala, ini, fin, qRuta);
            }
        }

        // 2) DESTINO FINAL: liberar +2h
        TramoAsignado last = tr.get(tr.size() - 1);
        String apFinal = last.getVuelo().getDestino();
        Instant arr = last.getLlegadaUtc();
        if (arr != null) {
            Instant fin2h = arr.plus(Duration.ofHours(2));
            journal.liberar(apFinal, arr, fin2h, qRuta);
        }

        // 3) CARGA EN VUELOS: revertir asignaciones por tramo
        for (TramoAsignado t : tr) {
            int qTramo = t.getCantidad();                      // usa la cantidad efectiva del tramo
            if (qTramo != 0) {
                s.getCargaPorVuelo().asignar(t.getVuelo(), -qTramo);
            }
        }



    }
}
