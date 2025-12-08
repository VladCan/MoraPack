package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import java.time.Instant;
import java.util.*;

import lombok.RequiredArgsConstructor;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;

@RequiredArgsConstructor
public class RandomRemoval implements DestructionOperator {
    private final int porcentaje;
    private final Random rnd = new Random();

    @Override
    public void destroy(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {
        Map<Integer, PlanPedido> planes = s.asMap();
        List<Integer> candidatos = new ArrayList<>(planes.keySet());
        int totalPedidos = candidatos.size();
        
        if (totalPedidos == 0) return;

        int n = Math.max(1, totalPedidos * porcentaje / 100);
        int destruidos = 0;

        while (destruidos < n && !candidatos.isEmpty()) {
            
            // Swap & Remove eficiente
            int indexAleatorio = rnd.nextInt(candidatos.size());
            int ultimoIndex = candidatos.size() - 1;
            Integer idPedido = candidatos.get(indexAleatorio);
            
            // Mover el último al lugar del borrado y reducir tamaño
            if (indexAleatorio != ultimoIndex) {
                candidatos.set(indexAleatorio, candidatos.get(ultimoIndex));
            }
            candidatos.remove(ultimoIndex);

            PlanPedido plan = planes.get(idPedido);
            if (plan == null) continue;

            // BLINDAJE: Usamos ArrayList explícito
            List<RutaAsignada> rutas = plan.getRutas();
            if (rutas == null || rutas.isEmpty()) continue;

            List<RutaAsignada> keep = new ArrayList<>();
            boolean cambio = false;

            for (RutaAsignada ruta : rutas) {
                if (ruta.getTramos() == null || ruta.getTramos().isEmpty()) {
                    continue; // Descartar rutas vacías
                }

                TramoAsignado primero = ruta.getTramos().get(0);
                Instant salidaPrimero = primero.getVuelo().getSalidaUtc();

                // Candado temporal: Si ya salió, la conservamos tal cual
                if (!salidaPrimero.isAfter(presenteUTC)) {
                    keep.add(ruta);
                    continue;
                }

                // Si se puede borrar, liberamos recursos y NO la agregamos a 'keep'
                liberarRuta(plan, ruta, journal, s);
                cambio = true;
            }

            if (cambio) {
                // Creamos el nuevo plan con la lista limpia
                PlanPedido nuevo = PlanPedido.builder()
                        .idPedido(plan.getIdPedido())
                        .aeropuertoDestino(plan.getAeropuertoDestino())
                        .creadoUtc(plan.getCreadoUtc())
                        .demanda(plan.getDemanda())
                        .rutas(new ArrayList<>(keep)) // <--- LISTA MUTABLE OBLIGATORIA
                        .build();
                s.getPlanPorPedido().put(nuevo.getIdPedido(), nuevo);
                destruidos++;
            }
        }
    }

    private void liberarRuta(PlanPedido plan, RutaAsignada ruta, ALNS.Journal journal, SolucionProgramacion s) {
        int q = ruta.getCantidad();
        if (q <= 0) return; // Validación extra

        List<TramoAsignado> tr = ruta.getTramos();
        for (int i = 0; i < tr.size(); i++) {
            TramoAsignado t = tr.get(i);
            VueloProgramadoId v = t.getVuelo();

            Instant esperaIniOri = (i == 0) ? plan.getCreadoUtc() : tr.get(i - 1).getVuelo().getLlegadaUtc();
            Instant esperaFinOri = v.getSalidaUtc();
            
            if (esperaIniOri != null && esperaFinOri != null && esperaFinOri.isAfter(esperaIniOri)) {
                journal.liberar(v.getOrigen(), esperaIniOri, esperaFinOri, q);
            }

            Instant esperaIniDst = v.getLlegadaUtc();
            Instant esperaFinDst = (i + 1 < tr.size())
                    ? tr.get(i + 1).getVuelo().getSalidaUtc()
                    : (esperaIniDst == null ? null : esperaIniDst.plus(java.time.Duration.ofHours(2)));
            
            if (esperaIniDst != null && esperaFinDst != null && esperaFinDst.isAfter(esperaIniDst)) {
                journal.liberar(v.getDestino(), esperaIniDst, esperaFinDst, q);
            }

            s.getCargaPorVuelo().asignar(v, -q);
        }
    }
}