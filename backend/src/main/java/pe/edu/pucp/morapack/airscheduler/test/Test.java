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
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators.*;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.OcupacionPorAeropuerto;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.SolucionProgramacion;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.service.VerificadorSLA;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.ssp.SSPGeneradorSeed;

public class Test {// ADAPTAIVE LARGE NEIGHBORHOOD SEARCH (ALNS)
    // Parámetros de simulación (ajustables)
    private static final long HORAS_VENTANA = 4;
    private static final long HORIZONTE_TEG_H = 48; // cuánto futuro modelar

    public  static  void main(String[] args) {

        // contador de tiempo de ejecución
        long start = System.nanoTime();
        /*
         * =======================
         * 1) SEDES Y CATÁLOGOS (CARGAMOS MAPA DE OCUPACIÓN POR AEROPUERTO)
         * =======================
         */
        final Set<String> sedes = new HashSet<>(Arrays.asList("SPIM", "EBCI", "UBBB"));
        AeropuertosMap aeropuertosMap = new AeropuertosMap();// Aeropuertos (incluye husos horarios)
        try (Scanner sc = ArchivoUtils.getScannerFromFilePath(
                "aereopuertos.txt")) {
            if (sc == null)
                return;
            aeropuertosMap.leerDatos(sc);
        }
        // Vuelos (catálogo maestro)
        VuelosMap mapa = new VuelosMap(aeropuertosMap);
        try (Scanner sc = ArchivoUtils.getScannerFromFilePath(
                "vuelos.txt")) {
            if (sc == null)
                return;
            mapa.leerDatos(sc);
        }

        VuelosCancelados cancelados = new VuelosCancelados();
        try (Scanner sc = ArchivoUtils.getScannerFromFilePath(
                "vuelos_cancelados.txt")){
            if (sc != null) {
                cancelados.leerDatos(sc);
                System.out.println("[RunManager] Vuelos cancelados cargados");
            } else {
                System.err.println("[RunManager] Falló la carga del archivo de vuelos cancelados.");
            }
        }
        //int year = 2025;
        //int mes = 10; // octubre
        // Nombre del archivo esperado: cancelaciones_2025-10.txt

        //try (Scanner sc = ArchivoUtils.getScannerFromFilePath(nombre)) {
        //    if (sc == null)
        //        return;
        //    cancelados.leerDatos(sc);
        //}

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
        try (Scanner sc = ArchivoUtils.getScannerFromFilePath("pedidos.txt")) {
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
        List<VueloCancelado> vuelosCanceladosTeg = new ArrayList<>();
        // guardaremos la solución anterior para poder replanificar
        SolucionProgramacion solucionAnterior = null;
        
        
        while (!pedidos.isEmpty()) {
            long startVentanaDeTiempo = System.nanoTime();
            
            // 1. AVANCE DEL RELOJ
            reloj = reloj.plus(Duration.ofHours(HORAS_VENTANA));
            Instant presenteUTC = reloj;
            Instant finUTC = presenteUTC.plus(HORIZONTE_TEG_H, ChronoUnit.HOURS);
            
            System.out.print(String.format("Fecha y hora de ejecución (UTC): %s%n",
                    DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss 'UTC'",
                            Locale.forLanguageTag("es-ES"))
                            .withZone(ZoneOffset.UTC).format(presenteUTC)));

            // 2. LIMPIEZA DE PEDIDOS COMPLETADOS
            if (solucionAnterior != null)
                pedidos.eliminarYActualizarCumplidosHasta(presenteUTC, solucionAnterior,true);

            // 3. OBTENCIÓN DE NUEVOS PEDIDOS
            VentanaPedidos ventana = pedidos.acumuladoHasta(presenteUTC);
            List<Pedido> listaPedidos = ventana.pedidos();
            
            if (listaPedidos.isEmpty()) {
                if (pedidos.isEmpty()) {
                    System.out.println("No hay más pedidos por procesar. Finalizando simulación.");
                    break;
                }
                System.out.println("No hay pedidos nuevos en esta ventana. Avanzando al siguiente periodo.");
                continue;
            }

            // 4. EXTRACCIÓN DEL ESTADO ANTERIOR (LO QUE YA SUCEDIÓ O ESTÁ CONFIRMADO)
            // Vuelos que ya están en el aire (inmutables)
            Map<String, List<ArriboExogeno>> enVuelo = EstadoAnteriorExtractor.construirArribosEnVuelo(solucionAnterior, presenteUTC);
            
            // Carga que físicamente está en el almacén esperando (inmutable)
            List<OcupacionAlmacen> reservas = EstadoAnteriorExtractor.reservasDesdeSolucionAnterior(solucionAnterior, presenteUTC, Duration.ofHours(2));

            // =========================================================================================
            // <--- !!! CAMBIO IMPORTANTE 1: INSTANCIAR LIMPIO !!!
            // Creamos un mapa de ocupación virgen para no arrastrar errores de planificación futura de la ventana anterior.
            OcupacionPorAeropuerto ocupacionPorAeropuerto = new OcupacionPorAeropuerto(aeropuertosMap);

            // <--- !!! CAMBIO IMPORTANTE 2: REHIDRATAR CON LA REALIDAD (FIDELIDAD) !!!
            // Le decimos al mapa nuevo: "Oye, estos almacenes YA tienen esta carga física de la iteración pasada".
            if (reservas != null) {
                for (OcupacionAlmacen res : reservas) {
                    
                    // 1. Filtro de Futuro (Mantener): Si empieza después de ahora, ignorar.
                    if (res.desde().isAfter(presenteUTC)) {
                        continue; 
                    }

                    // 2. CORRECCIÓN DE DURACIÓN (NUEVO):
                    // El plan anterior decía que la carga se quedaba hasta 'res.hasta()' (ej: mañana).
                    // Pero ahora estamos replanificando. Solo sabemos con certeza que la carga 
                    // ocupa espacio HASTA AHORA (presenteUTC).
                    // A partir de 'presenteUTC', el ALNS decidirá si la carga sigue ahí o se va.
                    
                    // Definimos el fin de la reserva "fija" como el momento actual (+ un epsilon de seguridad)
                    Instant finReal = res.hasta();
                    
                    // Si la reserva original iba más allá del presente, la cortamos en el presente.
                    // Así el ALNS es libre de usar esa carga inmediatamente.
                    if (finReal.isAfter(presenteUTC)) {
                        finReal = presenteUTC.plusSeconds(60); // 1 minuto de buffer
                    }

                    // Validación extra: Que no quede fin <= inicio
                    if (!finReal.isAfter(res.desde())) {
                        finReal = res.desde().plusSeconds(60);
                    }

                    ocupacionPorAeropuerto.reservar(
                        res.aeropuerto(), 
                        res.desde(), 
                        finReal, // Usamos el fin cortado
                        res.cantidad()
                    );
                }
            }
            // =========================================================================================

            // 5. CONFIGURACIÓN DEL TEG (Time-Expanded Graph)
            List<VueloCancelado> vuelosCanceladosArch = cancelados.obtenerVuelosCancelados(presenteUTC, finUTC);
            vuelosCanceladosTeg.addAll(vuelosCanceladosArch);

            TEGParametros params = TEGParametros.builder()
                    .inicioUtc(presenteUTC)
                    .finUtc(finUTC)
                    .sedes(sedes)
                    .arribosLibres(enVuelo)          // Aviones en el aire
                    .reservasWaitIniciales(reservas) // Carga en almacén (esto conecta el grafo con el stock real)
                    .vuelosCancelados(vuelosCanceladosTeg)
                    .build();

            VuelosTEG teg = new TEGEventBuilder(aeropuertosMap, mapa).construir(params);

            // 6. GENERACIÓN DE SEED (SOLUCIÓN INICIAL)
            // Usamos el 'ocupacionPorAeropuerto' que acabamos de limpiar y rellenar
            SSPGeneradorSeed ssp = new SSPGeneradorSeed(sedes, Map.of(), ocupacionPorAeropuerto);
            SolucionProgramacion seed = ssp.generarSeed(teg, listaPedidos, presenteUTC);
            ImpresorSolucion.imprimirEnArchivo(seed, "out/solucionInicial.txt", presenteUTC);

            // 7. CONFIGURACIÓN Y EJECUCIÓN DEL ALNS
            List<DestructionOperator> destructores = new ArrayList<>();
            destructores.add(new RandomRemoval(25));
            destructores.add(new RandomRemoval(60));
            destructores.add(new WarehouseCrisisRemoval(15, aeropuertosMap));
            destructores.add(new WarehouseCrisisRemoval(40, aeropuertosMap));
            destructores.add(new SlaBreachRemoval(20));

            List<RepairOperator> reparadores = new ArrayList<>();
            reparadores.add(new Regret2RepairFast(new ArrayList<>(sedes), teg));
            reparadores.add(new GreedyUrgencyRepair(new ArrayList<>(sedes), teg));

            // Pasamos el ocupacionPorAeropuerto limpio para que ALNS trabaje sobre él
            ALNS alns = new ALNS(teg, listaPedidos, destructores, reparadores, presenteUTC, ocupacionPorAeropuerto, aeropuertosMap);
            SolucionProgramacion solucionOptima = alns.ejecutar(seed);

            if (solucionAnterior != null) {
                for (var entry : solucionAnterior.getPlanPorPedido().entrySet()) {
                    Integer idPedido = entry.getKey();
                    // Si el pedido existía antes, pero no está en la solución nueva...
                    if (!solucionOptima.getPlanPorPedido().containsKey(idPedido)) {
                        // ...significa que es un pedido "En Progreso" (irreversible).
                        // Lo copiamos tal cual a la nueva solución para mantener la memoria.
                        solucionOptima.getPlanPorPedido().put(idPedido, entry.getValue());
                    }
                }
            }

            // 8. REPORTES Y VERIFICACIÓN
            ImpresorSolucion.imprimirEnArchivo(solucionOptima, "out/solucion.txt", presenteUTC);
            ImpresorSolucion.imprimirReporteAeropuertos(solucionOptima, aeropuertosMap, "out/reporteAereopuertos.txt");
            
            solucionAnterior = solucionOptima;

            if (VerificadorSLA.assertBasicos(solucionAnterior, Duration.ofHours(46), mapa, aeropuertosMap)) {
                System.out.println("✅ Solución verificada para la ventana actual.");
            } else {
                System.err.println("❌ La solución tiene violaciones en la ventana actual.");
                System.exit(1);
            }

            long endVentanaDeTiempo = System.nanoTime();
            double durationSeconds = (endVentanaDeTiempo - startVentanaDeTiempo) / 1_000_000_000.0;
            System.out.println("⏱️ Tiempo total de ejecución: " + durationSeconds + " segundos");
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
