package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosNode;

public final class SlotGenerator {
    private SlotGenerator(){}

    /**
     * Devuelve hasta K instantes de slot para el destino:
     * - s0 >= createdAtUtc, dentro de 48h
     * - usa eventos existentes del TEG (nodos destino@t); si no hay exacto, añade createdAtUtc tal cual
     */
    public static List<Instant> candidateSlots(VuelosTEG G, String destinoIcao, Instant createdAtUtc, int k) {
        // 48h hard-limit del negocio
        Instant max = createdAtUtc.plus(Duration.ofHours(48));

        // Tiempos existentes en el TEG para ese ICAO
        var times = G.nodes().stream()
                .filter(n -> !n.isSuperSource() && destinoIcao.equals(n.getIcao()))
                .map(VuelosNode::getTimeUtc)
                .filter(Objects::nonNull)
                .filter(t -> !t.isBefore(createdAtUtc) && !t.isAfter(max))
                .distinct()
                .sorted()
                .collect(Collectors.toCollection(ArrayList::new));

        // Asegura que createdAtUtc esté como opción (JIT), por si no coincide con eventos del TEG
        if (times.isEmpty() || !times.contains(createdAtUtc)) {
            times.add(0, createdAtUtc);
        }

        // Devuelve hasta K
        return times.size() > k ? times.subList(0, k) : times;
    }
}