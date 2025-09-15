package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed.model;

import java.time.Instant;
import java.util.List;

public record OrderAssignment(
        int pedidoId,
        String destino,
        int cantidad,
        boolean fulfilled,
        Instant slotChosen,
        List<PathArc> path // arcos por los que realmente fluyó
) {}