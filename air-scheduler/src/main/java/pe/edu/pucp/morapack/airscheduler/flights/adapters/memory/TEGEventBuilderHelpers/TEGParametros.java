package pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.TEGEventBuilderHelpers;

import lombok.Builder;
import lombok.Value;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.ArriboExogeno;

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
    /** Códigos ICAO de sedes (para Ω-sede). */
    Set<String> sedes;
    /** Arribos de carga ya en vuelo: destino -> lista de (instante, cantidad). */
    @Builder.Default
    Map<String, List<ArriboExogeno>> arribosLibres = Map.of();
}