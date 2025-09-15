package pe.edu.pucp.morapack.airscheduler.bootstrap;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VueloTEGBuilder;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosMap;
import pe.edu.pucp.morapack.airscheduler.orders.adapters.io.ArchivoUtils;
import pe.edu.pucp.morapack.airscheduler.orders.adapters.io.CargarPedidos;
import pe.edu.pucp.morapack.airscheduler.orders.domain.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators.DestructionOperator;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators.GreedyRepair;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators.RandomRemoval;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators.RegretRepair;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators.RepairOperator;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators.WorstRemoval;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed.CostPolicy;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed.DefaultCostPolicy;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed.PreferredCostPolicy;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed.SSPSeedService;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed.SeedReporter;

public class Main {
    public static void main(String[] args) {
        // Sedes
        var sedes = new java.util.HashSet<>(java.util.Arrays.asList("SPIM", "EBCI", "UBBB"));
        // Aeropuertos
        AeropuertosMap aeropuertosMap = new AeropuertosMap();
        try (Scanner sc = ArchivoUtils
                .getScannerFromResource("c.1inf54.25.2.Aeropuerto.husos.v1.20250818__estudiantes.txt")) {
            if (sc != null) {
                aeropuertosMap.leerDatos(sc);
            } else
                return;
        }
        // Vuelos
        VuelosMap mapa = new VuelosMap(aeropuertosMap);
        try (Scanner sc = ArchivoUtils.getScannerFromResource("c.1inf54.25.2.planes_vuelo.v4.20250818.txt")) {
            if (sc != null) {
                mapa.leerDatos(sc);
            } else
                return;
        }
        /*
         * Map<String, List<Vuelo>> vuelosPorOrigen = mapa.getVuelosPorOrigen();
         * System.out.println("Vuelos desde SPIM:");
         * List<Vuelo> skboVuelos = vuelosPorOrigen.get("SPIM");
         * if (skboVuelos != null) {
         * for (Vuelo v : skboVuelos) {
         * System.out.println(v);
         * }
         * }
         */
        /*
         * //Sanity check
         * Sanity.run(aeropuertosMap, mapa, graph);
         */
        // Pedidos
        CargarPedidos pedidos = new CargarPedidos();
        try (Scanner sc = ArchivoUtils.getScannerFromResource("pedidos.txt")) {
            if (sc != null) {
                /*
                 * //Sanity check pedidos por tiempo
                 * Sanity.runPedidosTiempo(aeropuertosMap, sc);
                 */
                pedidos.leerDatos(sc);
            } else
                return;
        }

        // Lista de pedidos
        List<Pedido> listaPedidos = pedidos.getLista();

        // vamos a convertir las horas de llegada a UTC
        for (var p : listaPedidos) {
            var a = aeropuertosMap.obtener(p.getDestino());
            int gmt = (a != null) ? a.getGMT() : 0; // fallback seguro
            p.computeUtcFromGmt(gmt);
        }

        // Grafo de vuelos (TEG)
        java.time.Instant t0 = listaPedidos.stream()
                .map(Pedido::getCreatedAtUtc)
                .min(java.util.Comparator.naturalOrder())
                .orElseGet(java.time.Instant::now);
        java.time.Instant t1 = t0.plus(java.time.Duration.ofHours(72));
        var teg = VueloTEGBuilder.build(aeropuertosMap, mapa, t0, t1, sedes);
        
        /*Sanity tests */
        //Sanity.run(aeropuertosMap, mapa, teg, sedes);
        //Sanity.runPedidosTiempo(aeropuertosMap, listaPedidos);
        //System.exit(1);

        // Parámetros de la heurística constructiva (SSP) SOLUCIÓN INICIAL
        CostPolicy policy = new PreferredCostPolicy(1, 10, 0.2, null);

        // Crea el seed service
        var seedSvc = new SSPSeedService(teg, policy, sedes);

        // Construye la seed (p.ej. 10 slots por pedido)
        var seed = seedSvc.build(listaPedidos, 10);

        // Reporta
        SeedReporter.printAll(seed);


        // Operadores de destrucción y reparación
        List<DestructionOperator> destr = Arrays.asList(
                new RandomRemoval(15), // quitar 15% aleatorio
                new WorstRemoval(15));

        List<RepairOperator> repairs = Arrays.asList(
                new GreedyRepair(),
                new RegretRepair(2));

        // Crear y ejecutar ALNS
        // ALNS alns = new ALNS(mapa.getVuelosPorOrigen(), aeropuertosMap, listaPedidos,
        // sedes, destr, repairs, 5000);
        // Solution best = alns.run();

        System.out.println("Mejor solución encontrada:");
        // best.imprimir();

    }
}
