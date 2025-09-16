package pe.edu.pucp.morapack.airscheduler.scheduling.domain.model;


import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Solución completa: planes por pedido + carga agregada por vuelo. */
@Getter
@Builder
public class SolucionProgramacion {

    @Singular("plan")
    private final Map<Integer, PlanPedido> planPorPedido;

    private final CargaPorVuelo cargaPorVuelo;

    public PlanPedido planDe(int idPedido) {
        return planPorPedido.get(idPedido);
    }

    public boolean respetaCapacidadesVuelos() {
        return cargaPorVuelo.getAsignado().entrySet().stream()
                .allMatch(e -> e.getValue() <= cargaPorVuelo.capacidad(e.getKey()));
    }

    public boolean respetaVentana2hTodos(Duration ventana2h) {
        return planPorPedido.values().stream().allMatch(p -> p.respetaVentana2h(ventana2h));
    }

    public boolean respetaSLA48hTodos() {
        return planPorPedido.values().stream().allMatch(p -> p.respetaSLA(Duration.ofHours(48)));
    }

    public Map<Integer, PlanPedido> asMap() {
        return Collections.unmodifiableMap(planPorPedido);
    }

    public List<Integer> pedidosIncompletos() {
        return planPorPedido.values().stream()
                .filter(p -> !p.estaCompleto())
                .map(PlanPedido::getIdPedido)
                .toList();
    }
}
