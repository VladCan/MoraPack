package pe.edu.pucp.morapack.airscheduler.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.SolucionProgramacion;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.VerificadorSLA;
//import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.ALNS;
//import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators.*;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators.*;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.ssp.SSPGeneradorSeed;

public class Main {

    // Parámetros de simulación (ajustables)
    private static final long HORAS_VENTANA = 12;
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
        try (Scanner sc = ArchivoUtils.getScannerFromResource("pedidosProfe.txt")) {
            if (sc == null)
                return;
            // Lectura “pura”: no tocar husos aquí
            // pedidos.leerDatos(sc);
            pedidos.leerDatosProfe(sc);

        }

        /*
         * ============================================
         * 3) NORMALIZACIÓN A UTC
         * ============================================
         */
        // Lleva cada pedido a UTC usando el GMT del destino
        pedidos.normalizarUtc(aeropuertosMap);
        // el while es para simular la llegada de pedidos en el tiempo

        // revisión de datos guardados en pedidos

        // pedidos.imprimrPedidos();
        // System.exit(1);

        Instant reloj = pedidos.primerInstanteUTC();
        if (reloj == null)
            return;

        limpiarArchivosPrevios();

        SolucionProgramacion solucionAnterior = null;
        while (!pedidos.isEmpty()) {
            // reloj avanza 6 horas
            reloj = reloj.plus(Duration.ofHours(HORAS_VENTANA));
            Instant presenteUTC = reloj;
            Instant finUTC = presenteUTC.plus(HORIZONTE_TEG_H, ChronoUnit.HOURS);// para TEG

            if (solucionAnterior != null) {
                // quitamos pedidos cumplidos y actualizamos los pedidos medio cumplidos
                pedidos.eliminarYActualizarCumplidosHasta(presenteUTC, solucionAnterior);
            }

            if (solucionAnterior != null) {
                solucionAnterior.imprimirEnArchivo(presenteUTC, "reporteSimulacion.txt");
            }

            // solo copia los pedidos no desencola
            VentanaPedidos ventana = pedidos.acumuladoHasta(presenteUTC);// solo sacamos los pedidos de la
                                                                         // ventana
            // pedidos.imprimirVentanaDePedidos(ventana);
            List<Pedido> listaPedidos = ventana.pedidos();
            if (listaPedidos.isEmpty())
                break;

            Map<String, List<ArriboExogeno>> enVuelo = EstadoAnteriorExtractor.construirArribosEnVuelo(solucionAnterior,
                    presenteUTC);

            List<OcupacionAlmacen> reservas = EstadoAnteriorExtractor.reservasDesdeSolucionAnterior(solucionAnterior,
                    presenteUTC, Duration.ofHours(2));

            TEGParametros params = TEGParametros.builder()
                    .inicioUtc(presenteUTC)
                    .finUtc(finUTC)
                    .capacidadWaitPorDefecto(null) // null => usa cap. de bodega del aeropuerto
                    .sedes(sedes)
                    .arribosLibres(enVuelo) // <— vuelos ya despegados
                    .reservasWaitIniciales(reservas) // <— ocupa bodega por pickup 2h
                    // .stockInicial(si_tienes)
                    .build();

            VuelosTEG teg = new TEGEventBuilder(aeropuertosMap, mapa).construir(params);

            SSPGeneradorSeed ssp = new SSPGeneradorSeed(sedes, Map.of());
            SolucionProgramacion seed = ssp.generarSeed(teg, listaPedidos, presenteUTC);
            //ImpresorSolucion.imprimirEnArchivo(seed, "solucion.txt");
            

            // ALNS
            
            List<DestructionOperator> destructores = new ArrayList<>();
            destructores.add(new RandomRemoval(20));
            destructores.add(new WorstRemoval(20));
            List<RepairOperator> reparadores = new ArrayList<>();
            reparadores.add(new RegretRepair(2, new ArrayList<>(sedes), teg));
            reparadores.add(new SplitRepair(new ArrayList<>(sedes),teg,50));
            ALNS alns = new ALNS(teg, listaPedidos, destructores, reparadores,
            presenteUTC);
            SolucionProgramacion solucionOptima = alns.ejecutar(seed);
            // System.out.println("ALNS");
            //ImpresorSolucion.imprimirEnArchivo(solucionOptima);
            ImpresorSolucion.imprimirEnArchivo(solucionOptima, "solucion.txt");
            solucionAnterior=solucionOptima;
            VerificadorSLA.assertBasicos(solucionOptima, Duration.ofHours(46));
            // System.exit(1);
            //solucionAnterior = seed;
            System.out.println("Ventana de tiempo planificada, " + presenteUTC);
        }
        System.out.println("─────────────────────────────────────────────");
        System.out.println("📄 Reporte de simulación guardado en: reporteSimulacion.txt");
        System.out.println("📄 Detalle de la solución guardado en: solucion.txt");
        System.out.println("─────────────────────────────────────────────");
        System.out.println("👉 Revisa estos archivos en el directorio del proyecto.");
    }

    private static void limpiarArchivosPrevios() {
        borrarSiExiste("reporteSimulacion.txt");
        borrarSiExiste("solucion.txt");
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
