package pe.edu.pucp.morapack.airscheduler.bootstrap;



import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.FlightGraphBuilder;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosMap;
import pe.edu.pucp.morapack.airscheduler.orders.adapters.io.ArchivoUtils;
import pe.edu.pucp.morapack.airscheduler.orders.adapters.io.CargarPedidos;
import pe.edu.pucp.morapack.airscheduler.orders.domain.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.Solution;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators.DestructionOperator;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators.GreedyRepair;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators.RandomRemoval;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators.RegretRepair;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators.RepairOperator;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators.WorstRemoval;
public class Main {
    public static void main(String[] args) {
        //Sedes
        List<String> sedes = new ArrayList<>(Arrays.asList("SPIM", "EBCI", "UBBB"));
        //Aeropuertos
        AeropuertosMap aeropuertosMap = new AeropuertosMap();
        try (Scanner sc = ArchivoUtils.getScannerFromResource("c.1inf54.25.2.Aeropuerto.husos.v1.20250818__estudiantes.txt")) {
            if (sc != null) {
                aeropuertosMap.leerDatos(sc);
            } else return;
        }
        //Vuelos
        VuelosMap mapa = new VuelosMap(aeropuertosMap);
        try (Scanner sc = ArchivoUtils.getScannerFromResource("c.1inf54.25.2.planes_vuelo.v4.20250818.txt")) {
            if (sc != null) {
                mapa.leerDatos(sc);
            } else return;
        }
       /*Map<String, List<Vuelo>> vuelosPorOrigen = mapa.getVuelosPorOrigen();
       System.out.println("Vuelos desde SPIM:");
        List<Vuelo> skboVuelos = vuelosPorOrigen.get("SPIM");
        if (skboVuelos != null) {
            for (Vuelo v : skboVuelos) {
                System.out.println(v);
            }
        }
        */
        //Grafo
        var graph = FlightGraphBuilder.build(aeropuertosMap, mapa);

        /*
        //Sanity check
        Sanity.run(aeropuertosMap, mapa, graph);
         */
        //Pedidos
        CargarPedidos pedidos = new CargarPedidos();
        try (Scanner sc = ArchivoUtils.getScannerFromResource("pedidos.txt")) {
            if (sc != null) {
                Sanity.runPedidosTiempo(aeropuertosMap, sc);
                pedidos.leerDatos(sc);
            } else return;
        }

        // Lista de pedidos
        List<Pedido> listaPedidos = pedidos.getLista();

        // Operadores de destrucción y reparación
        List<DestructionOperator> destr = Arrays.asList(
                new RandomRemoval(15),   // quitar 15% aleatorio
                new WorstRemoval(15)
        );

        List<RepairOperator> repairs = Arrays.asList(
                new GreedyRepair(),
                new RegretRepair(2)
        );




        // Crear y ejecutar ALNS
        ALNS alns = new ALNS(mapa.getVuelosPorOrigen(), aeropuertosMap, listaPedidos, sedes, destr, repairs, 5000);
        Solution best = alns.run();

        System.out.println("Mejor solución encontrada:");
        best.imprimir();

    }
}
