package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import java.time.Instant;
import java.util.*;

import lombok.RequiredArgsConstructor;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;

@RequiredArgsConstructor
public class RandomRemoval implements DestructionOperator {
    private final int porcentaje;

    @Override
    public void destroy(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {
        Map<Integer, PlanPedido> planes = s.asMap();
        List<Integer> pedidos = new ArrayList<>(planes.keySet());
        if (pedidos.isEmpty()) return;

        // Mezclar para no “siempre lo mismo”
        Collections.shuffle(pedidos, new Random());

        int n = Math.max(1, pedidos.size() * porcentaje / 100);

        for (int i = 0; i < n && i < pedidos.size(); i++) {
            int id = pedidos.get(i);
            PlanPedido plan = planes.get(id);
            if (plan == null) continue;

            List<RutaAsignada> rutas = plan.getRutas();
            if (rutas == null || rutas.isEmpty()) continue;

            List<RutaAsignada> keep = new ArrayList<>();
            boolean cambio = false;

            for (int rIdx = 0; rIdx < rutas.size(); rIdx++) {
                RutaAsignada ruta = rutas.get(rIdx);
                if (ruta.getTramos() == null || ruta.getTramos().isEmpty()) {
                    // nada que liberar -> la dejamos
                    keep.add(ruta);
                    continue;
                }

                TramoAsignado primero = ruta.getTramos().get(0);
                Instant salidaPrimero = primero.getVuelo().getSalidaUtc();

                // Si YA despegó, no toques esa ruta (déjala)
                if (!salidaPrimero.isAfter(presenteUTC)) {
                    keep.add(ruta);
                    continue;
                }

                // Esta ruta sí puede eliminarse -> liberar bodega y vuelos
                liberarRuta(plan, ruta, journal, s);
                cambio = true;
            }

            if (cambio) {
                // Construimos un nuevo plan con solo las rutas que quedan
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

    /** Libera SIMÉTRICAMENTE lo que fue reservado: origen, escalas y +2h final; y carga de vuelos. */
    private void liberarRuta(PlanPedido plan, RutaAsignada ruta, ALNS.Journal journal, SolucionProgramacion s) {
        int q = ruta.getCantidad();
        List<TramoAsignado> tr = ruta.getTramos();

        for (int i = 0; i < tr.size(); i++) {
            TramoAsignado t = tr.get(i);
            VueloProgramadoId v = t.getVuelo();

            // ORIGEN de este tramo: [creado o llegada_prev, salida)
            Instant esperaIniOri = (i == 0) ? plan.getCreadoUtc() : tr.get(i - 1).getVuelo().getLlegadaUtc();
            Instant esperaFinOri = v.getSalidaUtc();
            if (esperaIniOri != null && esperaFinOri != null && !esperaFinOri.isBefore(esperaIniOri)) {
                journal.liberar(v.getOrigen(), esperaIniOri, esperaFinOri, q);
            }

            // ESCALA / DESTINO intermedio o final
            Instant esperaIniDst = v.getLlegadaUtc();
            Instant esperaFinDst = (i + 1 < tr.size())
                    ? tr.get(i + 1).getVuelo().getSalidaUtc()
                    : (esperaIniDst == null ? null : esperaIniDst.plus(java.time.Duration.ofHours(2)));
            if (esperaIniDst != null && esperaFinDst != null && !esperaFinDst.isBefore(esperaIniDst)) {
                journal.liberar(v.getDestino(), esperaIniDst, esperaFinDst, q);
            }

            // VUELO (carga asignada)
            s.getCargaPorVuelo().asignar(v, -q);
        }
    }
}
