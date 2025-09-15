package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed.model;

import java.util.List;


public record SeedResult(
        int totalPedidos,
        int pedidosCumplidos,
        int pedidosPendientes,
        double fillRate, // 0..1
        List<OrderAssignment> assignments
) {}