package pe.edu.pucp.morapack.airscheduler.bootstrap;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.TEGEventBuilder;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosMap;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.TEGEventBuilderHelpers.TEGParametros;
import pe.edu.pucp.morapack.airscheduler.orders.adapters.io.ArchivoUtils;
import pe.edu.pucp.morapack.airscheduler.orders.adapters.io.CargarPedidos;
import pe.edu.pucp.morapack.airscheduler.scheduling.adapters.io.ImpresorSolucion;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.VerificadorSLA;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators.*;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.ssp.SSPGeneradorSeed;

public class Main {

    // Parámetros de simulación (ajustables)
    private static final long HORAS_VENTANA   = 6;
    private static final long HORIZONTE_TEG_H = 72;   // cuánto futuro modelar

    public static void main(String[] args) {
        /* =======================
         * 1) SEDES Y CATÁLOGOS
         * ======================= */
        final Set<String> sedes = new HashSet<>(Arrays.asList("SPIM", "EBCI", "UBBB"));

        // Aeropuertos (incluye husos horarios)
        AeropuertosMap aeropuertosMap = new AeropuertosMap();
        try (Scanner sc = ArchivoUtils.getScannerFromResource(
                "c.1inf54.25.2.Aeropuerto.husos.v1.20250818__estudiantes.txt")) {
            if (sc == null) return;
            aeropuertosMap.leerDatos(sc);
        }

        // Vuelos (catálogo maestro)
        VuelosMap mapa = new VuelosMap(aeropuertosMap);
        try (Scanner sc = ArchivoUtils.getScannerFromResource(
                "c.1inf54.25.2.planes_vuelo.v4.20250818.txt")) {
            if (sc == null) return;
            mapa.leerDatos(sc);
        }

        /* =======================
         * 2) PEDIDOS (CRUDO)
         * ======================= */
        CargarPedidos pedidos = new CargarPedidos();
        try (Scanner sc = ArchivoUtils.getScannerFromResource("pedidos.txt")) {
            if (sc == null) return;
            // Lectura “pura”: no tocar husos aquí
            pedidos.leerDatos(sc);
        }

        /* ============================================
         * 3) NORMALIZACIÓN A UTC
         * ============================================ */
        // Lleva cada pedido a UTC usando el GMT del destino
        pedidos.normalizarUtc(aeropuertosMap);
        //el while es para simular la llegada de pedidos en el tiempo
        
        Instant reloj = pedidos.primerInstanteUTC();
        if (reloj == null) return;
        //int i=0;
        while (!pedidos.isEmpty()) {
            var ventana = pedidos.ventanaDesde(reloj, HORAS_VENTANA);
            var listaPedidos = ventana.pedidos();
            if (listaPedidos.isEmpty()) break;

            var presenteUTC = ventana.presenteUTC();          // reloj + 6 h
            var inicioUTC   = presenteUTC;                    // TEG sin pasado
            var finUTC      = presenteUTC.plus(HORIZONTE_TEG_H, ChronoUnit.HOURS);


            var params = TEGParametros.builder()
                .inicioUtc(inicioUTC)
                .finUtc(finUTC)
                .capacidadWaitPorDefecto(null) // null => usa cap. de bodega del aeropuerto
                .sedes(sedes)
                // .arribosLibres(arribosDesdeSolucionAnterior) // opcional
                .build();

            var teg = new TEGEventBuilder(aeropuertosMap, mapa).construir(params);
            

            var ssp = new SSPGeneradorSeed(sedes,Map.of());
            var seed = ssp.generarSeed(teg, listaPedidos, ventana.presenteUTC());
            VerificadorSLA.assertBasicos(seed, Duration.ofHours(2));

            // Mostrar por consola
            ImpresorSolucion.imprimirEnConsola(seed);
            // Guardar TXT + CSV con un prefijo (por ejemplo "seed")
            ImpresorSolucion.guardarTodo(seed, ventana.presenteUTC(), "seed");

            // ALNS

            List<DestructionOperator> destructions = new ArrayList<>();
            destructions.add(new RandomRemoval(20));
            destructions.add(new WorstRemoval(20));

            List<RepairOperator> repairs = new ArrayList<>();
            repairs.add(new RegretRepair(2, new ArrayList<>(sedes), mapa.getVuelosPorOrigen()));

            ALNS alns = new ALNS(teg, listaPedidos, destructions, repairs);
            System.out.println("Seed");
            ImpresorSolucion.imprimirEnConsola(seed);
            var solucionOptima = alns.ejecutar(seed);
            System.out.println("ALNS");
            ImpresorSolucion.imprimirEnConsola(solucionOptima);
            break;
            //if(i==4){
            //    Sanity.todoOk(sedes, aeropuertosMap, ventana, teg);
            //    Sanity.dumpTEGSample(teg, 5);
            //    Sanity.chequearDuplicados(teg);
            //    break;
            //}
            //i++;
        }

    }
}
