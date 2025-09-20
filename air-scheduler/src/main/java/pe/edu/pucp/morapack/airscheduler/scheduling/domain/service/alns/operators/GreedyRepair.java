package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators;

import pe.edu.pucp.morapack.airscheduler.flights.domain.model.Vuelo;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.PlanPedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.RutaAsignada;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.SolucionProgramacion;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.TramoAsignado;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.VueloProgramadoId;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class GreedyRepair implements RepairOperator {

    private final List<String> sedes; // sedes válidas
    private final Map<String, List<Vuelo>> vuelosPorOrigen; // vuelos disponibles

    @Override
    public void repair(SolucionProgramacion s) {
        for (PlanPedido plan : s.getPlanPorPedido().values()) {
            if (plan.getTramosAplanados() != null && !plan.getTramosAplanados().isEmpty()) continue;

            // Heurística simple: primer vuelo disponible desde alguna sede
            for (String sede : sedes) {
                List<Vuelo> vuelos = vuelosPorOrigen.get(sede);
                if (vuelos == null) continue;

                for (Vuelo v : vuelos) {
                    if (v.getDestino().equals(plan.getAeropuertoDestino()) &&
                            v.getCapacidad() >= plan.getDemanda()) {

                        // Convertir LocalTime a Instant usando la fecha de creación del plan
                        LocalDate fecha = plan.getCreadoUtc().atZone(ZoneOffset.UTC).toLocalDate();
                        Instant salidaUtc = v.getHoraGMTOrigen().atDate(fecha).toInstant(ZoneOffset.UTC);
                        Instant llegadaUtc = v.getHoraGMTDestino().atDate(fecha).toInstant(ZoneOffset.UTC);

                        // Crear TramoAsignado
                        VueloProgramadoId id = new VueloProgramadoId(
                                v.getOrigen(),
                                v.getDestino(),
                                salidaUtc,
                                llegadaUtc
                        );

                        TramoAsignado tramo = new TramoAsignado(id, plan.getDemanda(), llegadaUtc);

                        // Crear una nueva ruta con ese único tramo
                        RutaAsignada nuevaRuta = new RutaAsignada(plan.getDemanda(),List.of(tramo));

                        // Limpiar y asignar
                        plan.limpiarTramos();
                        plan.getRutas().clear(); // ⚠️ getRutas() es unmodifiable → aquí quizá debas exponer addRuta()
                        plan.getRutas().add(nuevaRuta); // ← si permites modificar rutas
                        break;
                    }
                }
                if (!plan.getTramosAplanados().isEmpty()) break; // ya se asignó
            }
        }
    }
}
