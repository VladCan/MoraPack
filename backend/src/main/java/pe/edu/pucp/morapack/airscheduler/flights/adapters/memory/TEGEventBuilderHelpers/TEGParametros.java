package pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.TEGEventBuilderHelpers;

import lombok.Builder;
import lombok.Value;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.ArriboExogeno;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.OcupacionAlmacen;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Value
@Builder
public class TEGParametros {
    Instant inicioUtc;
    Instant finUtc;
    /** null => usar capacidad del aeropuerto como WAIT */
    Integer capacidadWaitPorDefecto;
    Set<String> sedes;

    /** Arribos en vuelo ya fijados (llegan dentro del horizonte). */
    @Builder.Default
    Map<String, List<ArriboExogeno>> arribosLibres = Map.of();

    /** Reservas de bodega que siguen vivas al inicio (NO consumibles). */
    @Builder.Default
    List<OcupacionAlmacen> reservasWaitIniciales = List.of();

    /** Stock libre “en piso” disponible en el inicio (sí consumible). */
    @Builder.Default
    Map<String, Integer> stockInicial = Map.of();
}
