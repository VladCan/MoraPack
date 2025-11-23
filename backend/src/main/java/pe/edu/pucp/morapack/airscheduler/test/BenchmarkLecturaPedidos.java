package pe.edu.pucp.morapack.airscheduler.test;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.LectorPedidoMultiArchivo;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Pedido;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class BenchmarkLecturaPedidos {

    ///NOTA: NO ESTÁN LOS ALMACENES/FÁBRICAS, SOLO AEROPUERTOS
    private static final String[] CODIGOS = {
            "EDDI",
            "EHAM",
            "EKCH",
            "LATI",
            "LBSF",
            "LDZA",
            "LKPR",
            "LOWW",
            "OAKB",
            "OERK",
            "OJAI",
            "OMDB",
            "OOMS",
            "OPKC",
            "OSDI",
            "OYSN",
            "SABE",
            "SBBR",
            "SCEL",
            "SEQM",
            "SGAS",
            "SKBO",
            "SLLP",
            "SUAA",
            "SVMI",
            "UMMS",
            "VIDP"
    };

    String archivoLog = "pedidos_por_ventana.txt";

    public static void main(String[] args) throws Exception {
        //Construimos la lista de rutas a los archivos (NO APTO PARA PRODUCCIÓN, SOLO EN LOCAL)
        List<Path> paths = construirRutasArchivos();
        System.out.println("Archivos cargados: " + paths.size());


        //Creamos el lector multiarchivo
        try (LectorPedidoMultiArchivo lector = new LectorPedidoMultiArchivo(paths)) {

            //Definimos el rango de la simulación
            LocalDateTime inicio = LocalDateTime.of(2025, 12, 23, 0, 0);
            LocalDateTime finSimulacion = inicio.plusDays(9);

            System.out.println("=== Benchmark lectura pedidos por ventanas de 4h ===");
            System.out.println("Desde: " + inicio + " hasta: " + finSimulacion);
            System.out.println("Archivos: " + paths.size());
            System.out.println("---------------------------------------------");

            //Aplicamos el fast-forward
            long t0Skip = System.nanoTime();
            lector.saltarHasta(inicio);
            long t1Skip  = System.nanoTime();
            System.out.printf("Tiempo saltar data histórica: %.3f ms%n",
                    (t1Skip - t0Skip) / 1_000_000.0);

            //Recorremos ventanas de 4h
            LocalDateTime presente = inicio;
            int idxVentana = 0;
            long totalPedidos = 0;

            while (presente.isBefore(finSimulacion) && !lector.terminado()) {
                LocalDateTime finVentana = presente.plusHours(4);

                long t0 = System.nanoTime();
                List<Pedido> pedidosVentana = lector.leerHasta(finVentana);
                long t1 = System.nanoTime();

                totalPedidos += pedidosVentana.size();

                System.out.printf(
                        "Ventana %02d [%s -> %s] - %6d pedidos - %.3f ms%n",
                        idxVentana,
                        presente,
                        finVentana,
                        pedidosVentana.size(),
                        (t1 - t0) / 1_000_000.0
                );

                presente = finVentana;
                idxVentana++;
            }

            System.out.println("---------------------------------------------");
            System.out.println("Total de pedidos leídos en la semana: " + totalPedidos);

        }

    }

    /// Construye las rutas a los archivos de pedidos
    /// POR AHORA, SOLO PARA LOCAL (NO PROD): src/main/resources/archivosPedidos/_pedidos_<CODIGO>_.txt

    private static List<Path> construirRutasArchivos() {
        List<Path> paths = new ArrayList<>();

        // Base relativa al proyecto; para ejecución desde IDE/Maven
        Path baseDir = Paths.get("src", "main", "resources", "archivosPedidos");

        for (String codigo : CODIGOS) {
            String fileName = "_pedidos_" + codigo + "_.txt";
            Path path = baseDir.resolve(fileName);
            paths.add(path);
        }

        return paths;
    }


}
