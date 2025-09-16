package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators;

import pe.edu.pucp.morapack.airscheduler.flights.domain.model.Vuelo;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.PlanPedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.SolucionProgramacion;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.TramoAsignado;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.VueloProgramadoId;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class RegretRepair implements RepairOperator {

    private final int k; // número de alternativas a considerar
    private final Map<String, List<Vuelo>> vuelosPorOrigen; // necesario para generar candidatos
    private final List<String> sedes; // orígenes válidos

    public RegretRepair(int k, List<String> sedes, Map<String, List<Vuelo>> vuelosPorOrigen) {
        this.k = k;
        this.sedes = sedes;
        this.vuelosPorOrigen = vuelosPorOrigen;
    }

    @Override
    public void repair(SolucionProgramacion s) {
        for (PlanPedido plan : s.getPlanPorPedido().values()) {
            if (plan.getTramos() != null && !plan.getTramos().isEmpty()) continue;

            List<Vuelo> candidatos = new ArrayList<>();

            // Generar todos los vuelos posibles desde sedes
            for (String sede : sedes) {
                List<Vuelo> vuelos = vuelosPorOrigen.get(sede);
                if (vuelos != null) {
                    for (Vuelo v : vuelos) {
                        if (v.getDestino().equals(plan.getDestinoIcao()) &&
                                v.getCapacidad() >= plan.getDemanda()) {
                            candidatos.add(v);
                        }
                    }
                }
            }

            if (candidatos.isEmpty()) continue;

            // Ordenar por costo (ascendente)
            candidatos.sort(Comparator.comparingDouble(Vuelo::getCosto));

            // Seleccionar el vuelo con menor costo (regret opcional)
            Vuelo elegido;
            if (candidatos.size() <= k) {
                elegido = candidatos.get(0);
            } else {
                double regret = candidatos.get(k).getCosto() - candidatos.get(0).getCosto();
                elegido = candidatos.get(0); // seleccionamos el de menor costo
            }

            // Asignar el vuelo elegido al plan usando la fecha de creación del plan
            plan.getTramos().clear(); // aseguramos que esté vacío antes
            plan.getTramos().add(vueloToTramoAsignado(elegido, plan.getDemanda(), plan.getCreadoUtc()));
        }
    }

    /**
     * Convierte un Vuelo a TramoAsignado usando la fecha de referencia del plan
     */
    private TramoAsignado vueloToTramoAsignado(Vuelo v, int cantidad, Instant referencia) {
        // Convertimos LocalTime de Vuelo a Instant usando la fecha del plan
        Instant salidaUtc = v.getHoraGMTOrigen()
                .atDate(referencia.atZone(ZoneOffset.UTC).toLocalDate())
                .toInstant(ZoneOffset.UTC);
        Instant llegadaUtc = v.getHoraGMTDestino()
                .atDate(referencia.atZone(ZoneOffset.UTC).toLocalDate())
                .toInstant(ZoneOffset.UTC);

        // Creamos el ID del vuelo programado
        VueloProgramadoId id = new VueloProgramadoId(
                v.getOrigen(),
                v.getDestino(),
                salidaUtc,
                llegadaUtc
        );

        return new TramoAsignado(id, cantidad, llegadaUtc);
    }
}
