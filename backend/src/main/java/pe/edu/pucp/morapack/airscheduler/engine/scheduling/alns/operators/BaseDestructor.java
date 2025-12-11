package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;
import java.time.Instant;
import java.time.Duration;
import java.util.List;

public abstract class BaseDestructor implements DestructionOperator {

    protected void liberarRuta(PlanPedido plan, RutaAsignada ruta, ALNS.Journal journal, SolucionProgramacion s) {
        int q = ruta.getCantidad();
        if (q <= 0) return;

        List<TramoAsignado> tr = ruta.getTramos();
        if (tr == null) return;

        for (int i = 0; i < tr.size(); i++) {
            TramoAsignado t = tr.get(i);
            VueloProgramadoId v = t.getVuelo();

            // Liberar origen (espera previa al vuelo)
            Instant esperaIniOri = (i == 0) ? plan.getCreadoUtc() : tr.get(i - 1).getVuelo().getLlegadaUtc();
            Instant esperaFinOri = v.getSalidaUtc();
            if (esperaIniOri != null && esperaFinOri != null && esperaFinOri.isAfter(esperaIniOri)) {
                journal.liberar(v.getOrigen(), esperaIniOri, esperaFinOri, q);
            }

            // Liberar destino (espera posterior o pickup final RF2)
            Instant esperaIniDst = v.getLlegadaUtc();
            Instant esperaFinDst = (i + 1 < tr.size())
                    ? tr.get(i + 1).getVuelo().getSalidaUtc()
                    : (esperaIniDst == null ? null : esperaIniDst.plus(Duration.ofHours(2))); // RF2

            if (esperaIniDst != null && esperaFinDst != null && esperaFinDst.isAfter(esperaIniDst)) {
                journal.liberar(v.getDestino(), esperaIniDst, esperaFinDst, q);
            }

            // Liberar capacidad de vuelo RF4
            s.getCargaPorVuelo().asignar(v, -q);
        }
    }
}