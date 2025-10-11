package pe.edu.pucp.morapack.airscheduler.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.TEGEventBuilder;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosMap;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.TEGEventBuilderHelpers.TEGParametros;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.utils.EstadoAnteriorExtractor;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.ArriboExogeno;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.OcupacionAlmacen;
import pe.edu.pucp.morapack.airscheduler.orders.adapters.io.ArchivoUtils;
import pe.edu.pucp.morapack.airscheduler.orders.adapters.io.CargarPedidos;
import pe.edu.pucp.morapack.airscheduler.orders.adapters.io.CargarPedidos.VentanaPedidos;
import pe.edu.pucp.morapack.airscheduler.orders.domain.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.adapters.io.ImpresorSolucion;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.OcupacionPorAeropuerto;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.SolucionProgramacion;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.VerificadorSLA;
//import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.ALNS;
//import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators.*;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators.*;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.ssp.SSPGeneradorSeed;

public class Test {
    // Parámetros de simulación (ajustables)
    private static final long HORAS_VENTANA = 6;
    private static final long HORIZONTE_TEG_H = 72; // cuánto futuro modelar

    public static void main(String[] args) {

        //contador de tiempo de ejecución
        long start = System.nanoTime();
        /*
         * =======================
         * 1) SEDES Y CATÁLOGOS (CARGAMOS MAPA DE OCUPACIÓN POR AEROPUERTO)
         * =======================
         */
        final Set<String> sedes = new HashSet<>(Arrays.asList("SPIM", "EBCI", "UBBB"));
        AeropuertosMap aeropuertosMap = new AeropuertosMap();// Aeropuertos (incluye husos horarios)
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

        OcupacionPorAeropuerto ocupacionPorAeropuerto = new OcupacionPorAeropuerto(aeropuertosMap);

        /*
         * =======================
         * 2) PEDIDOS (CRUDO)
         * =======================
         */
        CargarPedidos pedidos = new CargarPedidos();
        try (Scanner sc = ArchivoUtils.getScannerFromResource("pedidosProfe.txt")) {
            if (sc == null)return;
            pedidos.leerDatosProfe(sc);
        }
        /*
         * ============================================
         * 3) NORMALIZACIÓN A UTC
         * ============================================
         */
        // Lleva cada pedido a UTC usando el GMT del destino
        pedidos.normalizarUtc(aeropuertosMap);

        //Para asegurar que siempre estén ordenados por fecha de creación UTC
        pedidos.ordenarPorUTC();

        Instant reloj = pedidos.primerInstanteUTC();
        if (reloj == null) return; //no hay pedidos que simular
        limpiarArchivosPrevios();
        //guardaremos la solución anterior para poder replanificar
        SolucionProgramacion solucionAnterior = null;
        while (!pedidos.isEmpty()) {
            // reloj avanza hacia el futuro el valro de HORAS_VENTANA
            reloj = reloj.plus(Duration.ofHours(HORAS_VENTANA));
            Instant presenteUTC = reloj;
            Instant finUTC = presenteUTC.plus(HORIZONTE_TEG_H, ChronoUnit.HOURS);// para TEG
            // quitamos pedidos cumplidos y actualizamos los pedidos medio cumplidos
            if (solucionAnterior != null) pedidos.eliminarYActualizarCumplidosHasta(presenteUTC, solucionAnterior);
            //imprimimos un reporte del estado de los pedididos en el tiempo presenteUTC
            if (solucionAnterior != null) solucionAnterior.imprimirEnArchivo(presenteUTC, "out/reporteSimulacion.txt");
            // solo copia los pedidos no desencola
            VentanaPedidos ventana = pedidos.acumuladoHasta(presenteUTC);// solo sacamos los pedidos de la ventana
            List<Pedido> listaPedidos = ventana.pedidos();
            if (listaPedidos.isEmpty()) break;
            //guardamos los vuelos en curso y las reservas de espacio en aereopuertos de la solución anterior
            Map<String, List<ArriboExogeno>> enVuelo = EstadoAnteriorExtractor.
                                                construirArribosEnVuelo(solucionAnterior,presenteUTC);

            List<OcupacionAlmacen> reservas = EstadoAnteriorExtractor.
                                                reservasDesdeSolucionAnterior(solucionAnterior,presenteUTC, Duration.ofHours(2));
            //imprimimos enVuelo y reservas para debug
            DebugEstado.debugEstado(
                enVuelo,
                reservas,
                presenteUTC,
                Paths.get("out", "iteracionPrevia.txt")
            );
            //definimos los valores necesarios para el Time Elapse Event Graph TEEG
            TEGParametros params = TEGParametros.builder()
                    .inicioUtc(presenteUTC)
                    .finUtc(finUTC)
                    //.capacidadWaitPorDefecto(null) // null => usa cap. de bodega del aeropuerto
                    .sedes(sedes)
                    .arribosLibres(enVuelo) // <— vuelos ya despegados
                    .reservasWaitIniciales(reservas) // <— ocupa bodega por pickup 2h
                    // .stockInicial(stockInicial) //en caso sea conveniente para el modelo (en evaluacion)
                    .build();

            VuelosTEG teg = new TEGEventBuilder(aeropuertosMap, mapa).construir(params);

            //SSPGeneradorSeed ssp = new SSPGeneradorSeed(sedes, Map.of());

            SSPGeneradorSeed ssp = new SSPGeneradorSeed(sedes, Map.of(), ocupacionPorAeropuerto);
            SolucionProgramacion seed = ssp.generarSeed(teg, listaPedidos, presenteUTC);
            ImpresorSolucion.imprimirEnArchivo(seed, "out/solucionInicial.txt");
            //VerificadorSLA.assertBasicos(seed, Duration.ofHours(46));
            //solucionAnterior = seed;
            // ALNS
            List<DestructionOperator> destructores = new ArrayList<>();
            destructores.add(new RandomRemoval(20));
            //destructores.add(new WorstRemoval(20));
            List<RepairOperator> reparadores = new ArrayList<>();
            reparadores.add(new RegretRepair(2, new ArrayList<>(sedes), teg));
            //reparadores.add(new SplitRepair(new ArrayList<>(sedes), teg, 50));
            ALNS alns = new ALNS(teg, listaPedidos, destructores, reparadores, presenteUTC, ocupacionPorAeropuerto);
            SolucionProgramacion solucionOptima = alns.ejecutar(seed);
            // System.out.println("ALNS");
            //ImpresorSolucion.imprimirEnArchivo(solucionOptima);
            ImpresorSolucion.imprimirEnArchivo(solucionOptima, "out/solucion.txt");
            ImpresorSolucion.imprimirReporteAeropuertos(solucionOptima, aeropuertosMap, "out/reporteAereopuertos.txt");
            solucionAnterior = solucionOptima;
            //verificacionTotal(solucionAnterior)
            VerificadorSLA.assertBasicos(solucionOptima, Duration.ofHours(46));
            //System.out.println("\n📊 FITNESS DE LA SOLUCIÓN:");
            //solucionOptima.imprimirFitness(presenteUTC);
            // System.exit(1);
            //solucionAnterior = seed;

            solucionOptima.imprimirCapacidadVuelosEnVentana(
                    presenteUTC, finUTC, "out/reporteCapacidadVuelos_" + presenteUTC.toString().replace(':','-') + ".txt"
            );

            System.out.println("Ventana de tiempo planificada, " + presenteUTC);
        }
        System.out.println("─────────────────────────────────────────────");
        System.out.println("📄 Reporte de simulación guardado en: out/reporteSimulacion.txt");
        System.out.println("📄 Detalle de la solución guardado en: out/solucion.txt");
        System.out.println("📄 Movimientos por aeropuerto guardado en: out/reporteAereopuertos.txt");
        System.out.println("─────────────────────────────────────────────");
        System.out.println("👉 Revisa estos archivos en el directorio del proyecto.");
        long end = System.nanoTime();
        long durationMs = (end - start) / 1_000_000;
        System.out.println("⏱️ Tiempo total de ejecución: " + durationMs + " ms");
    }


    private static void limpiarArchivosPrevios() {
        borrarSiExiste("out/iteracionPrevia.txt");
        borrarSiExiste("out/reporteSimulacion.txt");
        borrarSiExiste("out/solucion.txt");
        borrarSiExiste("out/solucionInicial.txt");
        borrarSiExiste("out/reporteAereopuertos.txt");
    }

    private static void borrarSiExiste(String nombre) {
        try {
            Path p = Paths.get(nombre);
            if (Files.exists(p)) {
                Files.delete(p);
                System.out.println("🗑️ Eliminado: " + p.toAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("⚠️ No se pudo borrar " + nombre + ": " + e.getMessage());
        }
    }
}
