package pe.edu.pucp.morapack.airscheduler.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.TEGEventBuilder;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosMap;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.TEGEventBuilderHelpers.TEGParametros;
import pe.edu.pucp.morapack.airscheduler.orders.adapters.io.ArchivoUtils;
import pe.edu.pucp.morapack.airscheduler.orders.adapters.io.CargarPedidos;
import pe.edu.pucp.morapack.airscheduler.orders.adapters.io.CargarPedidos.VentanaPedidos;
import pe.edu.pucp.morapack.airscheduler.orders.domain.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.adapters.io.ImpresorSolucion;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.SolucionProgramacion;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.VerificadorSLA;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators.*;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.ssp.SSPGeneradorSeed;

public class Main {

    // Parámetros de simulación (ajustables)
    private static final long HORAS_VENTANA = 6;
    private static final long HORIZONTE_TEG_H = 72; // cuánto futuro modelar

    public static void main(String[] args) {
        /*
         * =======================
         * 1) SEDES Y CATÁLOGOS
         * =======================
         */
        final Set<String> sedes = new HashSet<>(Arrays.asList("SPIM", "EBCI", "UBBB"));

        // Aeropuertos (incluye husos horarios)
        AeropuertosMap aeropuertosMap = new AeropuertosMap();
        try (Scanner sc = ArchivoUtils.getScannerFromResource(
                "c.1inf54.25.2.Aeropuerto.husos.v1.20250818__estudiantes.txt")) {
            if (sc == null)
                return;
            aeropuertosMap.leerDatos(sc);
        }

        // Vuelos (catálogo maestro)
        VuelosMap mapa = new VuelosMap(aeropuertosMap);
        try (Scanner sc = ArchivoUtils.getScannerFromResource(
                "c.1inf54.25.2.planes_vuelo.v4.20250818.txt")) {
            if (sc == null)
                return;
            mapa.leerDatos(sc);
        }

        /*
         * =======================
         * 2) PEDIDOS (CRUDO)
         * =======================
         */
        CargarPedidos pedidos = new CargarPedidos();
        try (Scanner sc = ArchivoUtils.getScannerFromResource("pedidos.txt")) {
            if (sc == null)
                return;
            // Lectura “pura”: no tocar husos aquí
            pedidos.leerDatos(sc);
        }

        /*
         * ============================================
         * 3) NORMALIZACIÓN A UTC
         * ============================================
         */
        // Lleva cada pedido a UTC usando el GMT del destino
        pedidos.normalizarUtc(aeropuertosMap);
        // el while es para simular la llegada de pedidos en el tiempo

        Instant reloj = pedidos.primerInstanteUTC();
        if (reloj == null)
            return;

        SolucionProgramacion solucionAnterior = null;
        int i=0;
        while (!pedidos.isEmpty()) {
            reloj=reloj.plus(Duration.ofHours(HORAS_VENTANA));
            Instant presenteUTC = reloj;
            Instant finUTC = presenteUTC.plus(HORIZONTE_TEG_H, ChronoUnit.HOURS);

            if (solucionAnterior != null) {
                pedidos.eliminarYActualizarCumplidosHasta(presenteUTC, solucionAnterior);
            }

            VentanaPedidos ventana = pedidos.ventanaDesde(presenteUTC, HORAS_VENTANA);// solo sacamos los pedidos de la
                                                                                      // ventana
            if (solucionAnterior != null) {
                solucionAnterior.imprimir(reloj, "reporteSimulacion.txt");
            }

            List<Pedido> listaPedidos = ventana.pedidos();
            if (listaPedidos.isEmpty())
                break;

            TEGParametros params = TEGParametros.builder()
                    .inicioUtc(presenteUTC)
                    .finUtc(finUTC)
                    .capacidadWaitPorDefecto(null) // null => usa cap. de bodega del aeropuerto
                    .sedes(sedes)
                    // TODO: URGENTE AGREGAR ESTO .estadoAnterior(solucionAnterior,presenteUTC)
                    .build();

            VuelosTEG teg = new TEGEventBuilder(aeropuertosMap, mapa).construir(params);

            SSPGeneradorSeed ssp = new SSPGeneradorSeed(sedes, Map.of());
            SolucionProgramacion seed = ssp.generarSeed(teg, listaPedidos, ventana.presenteUTC());
            VerificadorSLA.assertBasicos(seed, Duration.ofHours(46));

            // ALNS

            List<DestructionOperator> destructions = new ArrayList<>();
            destructions.add(new RandomRemoval(20));
            destructions.add(new WorstRemoval(20));

            List<RepairOperator> repairs = new ArrayList<>();
            repairs.add(new RegretRepair(2, new ArrayList<>(sedes), mapa.getVuelosPorOrigen()));

            ALNS alns = new ALNS(teg, listaPedidos, destructions, repairs);
            //System.out.println("Seed");
            //ImpresorSolucion.imprimirEnConsola(seed);
            SolucionProgramacion solucionOptima = alns.ejecutar(seed);
            System.out.println("ALNS");
            ImpresorSolucion.imprimirEnConsola(solucionOptima);

            solucionAnterior = solucionOptima;
            if(i==1) break; // para demo
            i++;
        }

    }
}
