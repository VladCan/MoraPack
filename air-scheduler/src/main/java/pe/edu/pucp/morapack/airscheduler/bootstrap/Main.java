package pe.edu.pucp.morapack.airscheduler.bootstrap;

import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.LiveTEGState;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VueloTEGBuilder;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosMap;
import pe.edu.pucp.morapack.airscheduler.orders.adapters.io.ArchivoUtils;
import pe.edu.pucp.morapack.airscheduler.orders.adapters.io.CargarPedidos;
import pe.edu.pucp.morapack.airscheduler.scheduling.adapters.io.CsvHistoryWriter;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed.CostPolicy;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed.PreferredCostPolicy;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed.SSPSeedService;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed.SeedReporter;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed.SeedResultAdapter;

/**
 * Flujo de una "tanda" de planificación:
 *  1) Carga catálogos (aeropuertos y vuelos) desde recursos.
 *  2) Carga cola de pedidos crudos (fechas locales).
 *  3) Normaliza fechas de pedidos a UTC usando el huso del destino.
 *  4) Selecciona la ventana de pedidos (últimas X horas) y define el "presente".
 *  5) Construye/actualiza el TEG con horizonte de H horas hacia adelante.
 *  6) Ejecuta la heurística constructiva (SSP) para la seed.
 *  7) Reporta resultados de la seed (para depurar y analizar).
 *
 * Notas:
 *  - La ventana de pedidos es la “tanda” actual.
 *  - El TEG se recorta al horizonte para mantener el problema acotado.
 *  - Este Main NO modifica catálogos; sólo arma el estado de la tanda.
 */
public class Main {

    // Parámetros de simulación (ajustables)
    private static final long HORAS_VENTANA   = 6;   // últimas X horas de pedidos (tanda)
    private static final long HORIZONTE_TEG_H = 72;  // horizonte del TEG (en horas) ~ 3 días

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






        //EMPIEZA LA SIMULACIÓN
        // Estado vivo + logger CSV
        var live = LiveTEGState.init(aeropuertosMap, mapa);
        try (var csv = new CsvHistoryWriter(Paths.get("historial"))) {
                
            int batchNo = 1;
            while (!pedidos.isEmpty()) {
                // 4) Ventana
                var ventana = pedidos.ultimasHoras(HORAS_VENTANA);
                var listaPedidos = ventana.pedidos();
                if (listaPedidos.isEmpty()) break; // nada más que planificar
                Instant presentUtc = ventana.presentUtc();

                // 4.1) Avanza reloj: retira vuelos ARRIBADOS y registra entregas
                var deliveries = live.advanceClock(presentUtc, batchNo);
                try {
                    csv.appendDeliveries(deliveries);
                } catch (java.io.IOException e) {
                    e.printStackTrace();
                    // Optionally, handle the error or break/continue as needed
                }

                // 5) TEG con estado vivo (no con el catálogo crudo)
                Instant t0 = presentUtc;
                Instant t1 = t0.plus(Duration.ofHours(HORIZONTE_TEG_H));
                var teg = VueloTEGBuilder.build(aeropuertosMap, live, t0, t1, sedes);

                // 6) Seed (misma política de minimizar vuelos)
                CostPolicy policy = new PreferredCostPolicy(1, 10, 0.2, null);
                var seedSvc = new SSPSeedService(teg, policy, sedes);
                var seed = seedSvc.build(listaPedidos, 10);

                // 6.1) Convierte seed -> bookings, aplica al estado y guarda CSV
                var bookings = SeedResultAdapter.toBookings(seed, live, batchNo);
                live.applyBookings(bookings);
                try {
                    csv.appendBookings(bookings);
                } catch (java.io.IOException e) {
                    e.printStackTrace();
                    // Optionally, handle the error or break/continue as needed
                }

                // 7) Reporte (si quieres mantenerlo)
                SeedReporter.printAll(seed);

                // 8) La tanda se consume de la cola
                pedidos.consumir(listaPedidos);

                batchNo++;
            }
        }catch (java.io.IOException e) {
            e.printStackTrace();
            // Optionally, handle the error as needed
        }
    }
}
