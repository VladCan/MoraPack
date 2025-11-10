package pe.edu.pucp.morapack.airscheduler.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

import com.arjuna.ats.internal.jdbc.drivers.modifiers.list;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.ArchivoUtils;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.CargarPedidos;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.ImpresorSolucion;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.CargarPedidos.VentanaPedidos;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.*;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.teg.TEGEventBuilder;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.teg.helpers.TEGParametros;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.ArriboExogeno;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.OcupacionAlmacen;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Vuelo;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators.*;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.OcupacionPorAeropuerto;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.SolucionProgramacion;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.service.VerificadorSLA;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.ssp.SSPGeneradorSeed;

public class Test {// ADAPTAIVE LARGE NEIGHBORHOOD SEARCH (ALNS)
    // Parámetros de simulación (ajustables)
    private static final long HORAS_VENTANA = 8;
    private static final long HORIZONTE_TEG_H = 48; // cuánto futuro modelar

    public static void main(String[] args) {

        // contador de tiempo de ejecución
        long start = System.nanoTime();
        /*
         * =======================
         * 1) SEDES Y CATÁLOGOS (CARGAMOS MAPA DE OCUPACIÓN POR AEROPUERTO)
         * =======================
         */
        final Set<String> sedes = new HashSet<>(Arrays.asList("SPIM", "EBCI", "UBBB"));
        AeropuertosMap aeropuertosMap = new AeropuertosMap();// Aeropuertos (incluye husos horarios)
        try (Scanner sc = ArchivoUtils.getScannerFromResource(
                "aereopuertos.txt")) {
            if (sc == null)
                return;
            aeropuertosMap.leerDatos(sc);
        }
        // Vuelos (catálogo maestro)
        VuelosMap mapa = new VuelosMap(aeropuertosMap);
        try (Scanner sc = ArchivoUtils.getScannerFromResource(
                "vuelos.txt")) {
            if (sc == null)
                return;
            mapa.leerDatos(sc);
        }

        VuelosCancelados cancelados = new VuelosCancelados();
        int year = 2025;
        int mes = 10; // octubre
        // Nombre del archivo esperado: cancelaciones_2025-10.txt
        String nombre = String.format("cancelaciones_%04d-%02d.txt", year, mes);
        try (Scanner sc = ArchivoUtils.getScannerFromResource(nombre)) {
            if (sc == null)
                return;
            cancelados.leerDatos(sc);
        }

        /*List<Vuelo> hola = mapa.vuelosDesde("SUAA");

        for (Vuelo v : hola) {
            List<Integer> dias = cancelados.diasCancelado(v);
            System.out.println("Vuelo: " + v + " -> Días cancelados: " + dias);
        }*/


        // se va llenar de datos que no son necesarios
        // nos dificulta la replanificación
        /*
         * =======================
         * 2) PEDIDOS (CRUDO)
         * =======================
         */
        CargarPedidos pedidos = new CargarPedidos();
        try (Scanner sc = ArchivoUtils.getScannerFromResource("pedidos.txt")) {
            if (sc == null)
                return;
            pedidos.leerDatosProfe(sc);
        } // localtime no localdatetime
        pedidos.normalizarUtc(aeropuertosMap);
        //pedidos.sort("out/pedidos.txt");
        //System.exit(1);
        // Para asegurar que siempre estén ordenados por fecha de creación UTC
        pedidos.ordenarPorUTC();

        Instant reloj = pedidos.primerInstanteUTC();
        System.out.println("🚦 Inicio de simulación en UTC: " +
                DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm 'UTC'")
                        .withZone(ZoneOffset.UTC).format(reloj));
        System.out.println("📦 Pedidos cargados: " + pedidos.getLista().size());
        if (reloj == null)
            return; // no hay pedidos que simular
        limpiarArchivosPrevios();
        // guardaremos la solución anterior para poder replanificar
        SolucionProgramacion solucionAnterior = null;
        OcupacionPorAeropuerto ocupacionPorAeropuerto = new OcupacionPorAeropuerto(aeropuertosMap);
        
        while (!pedidos.isEmpty()) {
            // reloj avanza hacia el futuro el valro de HORAS_VENTANA
            reloj = reloj.plus(Duration.ofHours(HORAS_VENTANA));
            Instant presenteUTC = reloj;
            Instant finUTC = presenteUTC.plus(HORIZONTE_TEG_H, ChronoUnit.HOURS);// para TEG
            System.out.print(String.format("Fecha y hora de ejecución (UTC): %s%n",
                        DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss 'UTC'", 
                        Locale.forLanguageTag("es-ES"))
                        .withZone(ZoneOffset.UTC).format(presenteUTC)));
            // quitamos pedidos cumplidos y actualizamos los pedidos medio cumplidos
            //System.out.println("pedidos antes de eliminar: " + pedidos.getLista().size());
            if (solucionAnterior != null)
                pedidos.eliminarYActualizarCumplidosHasta(presenteUTC, solucionAnterior);
            //System.out.println("pedidos después de eliminar: " + pedidos.getLista().size());
            // imprimimos un reporte del estado de los pedididos en el tiempo presenteUTC
            if (solucionAnterior != null)
                solucionAnterior.imprimirEnArchivo(presenteUTC, "out/reporteSimulacion.txt");
            // solo copia los pedidos no desencola
            VentanaPedidos ventana = pedidos.acumuladoHasta(presenteUTC);// solo sacamos los pedidos de la ventana
            //System.out.println("cantidad de pedidos en la ventana: "+ventana.pedidos().size() + " pedidos para programar hasta "
            //        + DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm 'UTC'")
            //                .withZone(ZoneOffset.UTC).format(presenteUTC));
            List<Pedido> listaPedidos = ventana.pedidos();
            if (listaPedidos.isEmpty()){
                if(pedidos.isEmpty()){
                    System.out.println("No hay más pedidos por procesar. Finalizando simulación.");
                    break;
                }
                System.out.println("No hay pedidos nuevos en esta ventana. Avanzando al siguiente periodo.");
                continue;
            }
                
            // guardamos los vuelos en curso y las reservas de espacio en aereopuertos de la
            // solución anterior
            Map<String, List<ArriboExogeno>> enVuelo = EstadoAnteriorExtractor.construirArribosEnVuelo(solucionAnterior,
                    presenteUTC);

            List<OcupacionAlmacen> reservas = EstadoAnteriorExtractor.reservasDesdeSolucionAnterior(solucionAnterior,
                    presenteUTC, Duration.ofHours(2));
            // imprimimos enVuelo y reservas para debug
            DebugEstado.debugEstado(
                    enVuelo,
                    reservas,
                    presenteUTC,
                    Paths.get("out", "iteracionPrevia.txt"));
            // definimos los valores necesarios para el Time Elapse Event Graph TEEG
            TEGParametros params = TEGParametros.builder()
                    .inicioUtc(presenteUTC)
                    .finUtc(finUTC)
                    // .capacidadWaitPorDefecto(null) // null => usa cap. de bodega del aeropuerto
                    .sedes(sedes)
                    .arribosLibres(enVuelo) // <— vuelos ya despegados
                    .reservasWaitIniciales(reservas) // <— ocupa bodega por pickup 2h
                    // .stockInicial(stockInicial) //en caso sea conveniente para el modelo (en
                    // evaluacion)
                    .vuelosCancelados(cancelados.getCanceladosMap())
                    .build();

            VuelosTEG teg = new TEGEventBuilder(aeropuertosMap, mapa).construir(params);
            // TODO --> tenemos que crear una función que alimente ocupacionPorAeropuerto
            // con lo que tiene teg
            // SSPGeneradorSeed ssp = new SSPGeneradorSeed(sedes, Map.of());

            SSPGeneradorSeed ssp = new SSPGeneradorSeed(sedes, Map.of(), ocupacionPorAeropuerto);
            SolucionProgramacion seed = ssp.generarSeed(teg, listaPedidos, presenteUTC);
            //ImpresorSolucion.imprimirEnArchivo(seed, "out/solucionInicial.txt",presenteUTC);
            // ALNS
            List<DestructionOperator> destructores = new ArrayList<>();
            destructores.add(new RandomRemoval(20));
            destructores.add(new WorstRemoval(20));
            List<RepairOperator> reparadores = new ArrayList<>();
            reparadores.add(new RegretRepair(2, new ArrayList<>(sedes), teg));
            reparadores.add(new SplitRepair(new ArrayList<>(sedes), teg));
            ALNS alns = new ALNS(teg, listaPedidos, destructores, reparadores, presenteUTC, ocupacionPorAeropuerto);
            SolucionProgramacion solucionOptima = alns.ejecutar(seed);
            //SEQM    410
            //48
            //24x410=9840
            // System.out.println("ALNS");
            // ImpresorSolucion.imprimirEnArchivo(solucionOptima);
            ImpresorSolucion.imprimirEnArchivo(solucionOptima, "out/solucion.txt", presenteUTC);
            ImpresorSolucion.imprimirReporteAeropuertos(solucionOptima, aeropuertosMap, "out/reporteAereopuertos.txt");
            solucionAnterior = solucionOptima;
            // verificacionTotal(solucionAnterior)
            // System.out.println("\n📊 FITNESS DE LA SOLUCIÓN:");
            // solucionOptima.imprimirFitness(presenteUTC);
            // System.exit(1);
            // solucionAnterior = seed;
            /* 
            solucionOptima.imprimirCapacidadVuelosEnVentana(
                    presenteUTC, finUTC,
                    "out/reporteCapacidadVuelos_" + presenteUTC.toString().replace(':', '-') + ".txt");
            */
            VerificadorSLA.assertBasicos(solucionOptima, Duration.ofHours(46),mapa);
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
