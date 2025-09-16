package pe.edu.pucp.morapack.airscheduler.bootstrap;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.TEGEventBuilder;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosMap;
import pe.edu.pucp.morapack.airscheduler.orders.adapters.io.ArchivoUtils;
import pe.edu.pucp.morapack.airscheduler.orders.adapters.io.CargarPedidos;

public class Main {

    // Parámetros de simulación (ajustables)
    private static final long HORAS_VENTANA   = 6;
    private static final long HORIZONTE_TEG_H = 72;   // cuánto futuro modelar
    private static final int  MIN_CONEXION_MIN = 45;  // conexión mínima en min

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
        
        while (!pedidos.isEmpty()) {
            var ventanaDePedidos = pedidos.ultimasHoras(HORAS_VENTANA);
            var listaPedidos = ventanaDePedidos.pedidos();
            if (listaPedidos.isEmpty()) break;
            var presenteUTC = ventanaDePedidos.presenteUTC();     // tu “ahora”
            var inicioUTC   = presenteUTC;                        // no creamos pasado
            var finUTC      = presenteUTC.plus(HORIZONTE_TEG_H,ChronoUnit.HOURS);


            var builder = new TEGEventBuilder(
            aeropuertosMap, mapa /* VuelosMap */)
            .inicio(inicioUTC)
            .fin(finUTC)
            .minConexionMin(MIN_CONEXION_MIN)
            .capacidadWaitPorDefecto(null)     // null => usa capacidad del aeropuerto (bodega)
            .sedes(sedes);

            var teg = builder.build();

            Sanity.todoOk(sedes, aeropuertosMap, ventanaDePedidos, teg);
            Sanity.dumpTEGSample(teg, 5);
            Sanity.chequearDuplicados(teg);
            System.exit(1);
        }

    }
}
