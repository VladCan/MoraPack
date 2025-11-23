package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import java.time.Instant;
import java.util.*;

import lombok.RequiredArgsConstructor;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;

@RequiredArgsConstructor
public class RandomRemoval implements DestructionOperator {
    private final int porcentaje;
    private final Random rnd = new Random(); // Reutilizamos la instancia Random

    @Override
    public void destroy(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {
        Map<Integer, PlanPedido> planes = s.asMap();
        
        // 1. Optimizamos la creación de la lista.
        // En lugar de barajar, solo necesitamos una lista de acceso aleatorio.
        List<Integer> candidatos = new ArrayList<>(planes.keySet());
        int totalPedidos = candidatos.size();
        
        if (totalPedidos == 0) return;

        // Calculamos el objetivo
        int n = Math.max(1, totalPedidos * porcentaje / 100);
        int destruidos = 0;

        // 2. Bucle optimizado: "Swap & Remove" (Fisher-Yates parcial)
        // Mientras necesitemos destruir más y queden candidatos...
        while (destruidos < n && !candidatos.isEmpty()) {
            
            // A. Elegimos un índice al azar dentro del rango disponible
            int indexAleatorio = rnd.nextInt(candidatos.size());
            
            // B. Obtenemos el ID del pedido en esa posición
            Integer idPedido = candidatos.get(indexAleatorio);
            
            // C. Truco de Rendimiento: Mover el último elemento a la posición actual y borrar el último.
            // Esto elimina el elemento en O(1) en lugar de O(N) de un remove(index) normal.
            int ultimoIndex = candidatos.size() - 1;
            if (indexAleatorio != ultimoIndex) {
                candidatos.set(indexAleatorio, candidatos.get(ultimoIndex));
            }
            candidatos.remove(ultimoIndex); 

            // --- Lógica de Procesamiento ---
            PlanPedido plan = planes.get(idPedido);
            if (plan == null) continue;

            List<RutaAsignada> rutas = plan.getRutas();
            if (rutas == null || rutas.isEmpty()) continue;

            List<RutaAsignada> keep = new ArrayList<>();
            boolean cambio = false;

            for (int rIdx = 0; rIdx < rutas.size(); rIdx++) {
                RutaAsignada ruta = rutas.get(rIdx);
                if (ruta.getTramos() == null || ruta.getTramos().isEmpty()) {
                    keep.add(ruta);
                    continue;
                }

                TramoAsignado primero = ruta.getTramos().get(0);
                Instant salidaPrimero = primero.getVuelo().getSalidaUtc();

                // Candado temporal
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
                destruidos++; // Solo contamos si realmente destruimos algo
            }
        }
    }

    /** Libera SIMÉTRICAMENTE lo que fue reservado */
    private void liberarRuta(PlanPedido plan, RutaAsignada ruta, ALNS.Journal journal, SolucionProgramacion s) {
        int q = ruta.getCantidad();
        List<TramoAsignado> tr = ruta.getTramos();

        for (int i = 0; i < tr.size(); i++) {
            TramoAsignado t = tr.get(i);
            VueloProgramadoId v = t.getVuelo();

            // ORIGEN
            Instant esperaIniOri = (i == 0) ? plan.getCreadoUtc() : tr.get(i - 1).getVuelo().getLlegadaUtc();
            Instant esperaFinOri = v.getSalidaUtc();
            if (esperaIniOri != null && esperaFinOri != null && !esperaFinOri.isBefore(esperaIniOri)) {
                journal.liberar(v.getOrigen(), esperaIniOri, esperaFinOri, q);
            }

            // DESTINO / ESCALA
            Instant esperaIniDst = v.getLlegadaUtc();
            Instant esperaFinDst = (i + 1 < tr.size())
                    ? tr.get(i + 1).getVuelo().getSalidaUtc()
                    : (esperaIniDst == null ? null : esperaIniDst.plus(java.time.Duration.ofHours(2)));
            if (esperaIniDst != null && esperaFinDst != null && !esperaFinDst.isBefore(esperaIniDst)) {
                journal.liberar(v.getDestino(), esperaIniDst, esperaFinDst, q);
            }

            // VUELO
            s.getCargaPorVuelo().asignar(v, -q);
        }
    }
}