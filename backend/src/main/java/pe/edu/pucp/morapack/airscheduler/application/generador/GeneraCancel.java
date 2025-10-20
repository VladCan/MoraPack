package pe.edu.pucp.morapack.airscheduler.application.generador;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.ArchivoUtils;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosMap;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Vuelo;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.YearMonth;
import java.util.*;

public class GeneraCancel {

    public static void generarArchivo(Path baseRuta,
                                      int year,
                                      int mes,
                                      VuelosMap mapaVuelos,
                                      double probCancelDia,
                                      int maxCancelPorDia) {

        Objects.requireNonNull(mapaVuelos, "El mapa de vuelos no puede ser null");

        if (probCancelDia < 0.0 || probCancelDia > 1.0)
            throw new IllegalArgumentException("La probabilidad debe estar entre 0.0 y 1.0");
        if (maxCancelPorDia <= 0)
            throw new IllegalArgumentException("El máximo de cancelaciones debe ser positivo");

        // Crear carpeta de salida si no existe
        try {
            if (!Files.exists(baseRuta)) Files.createDirectories(baseRuta);
        } catch (IOException e) {
            throw new RuntimeException("Error al crear carpeta: " + baseRuta, e);
        }

        // Archivo de salida mensual
        String nombreArchivo = String.format("cancelaciones_%04d-%02d.txt", year, mes);
        Path salida = baseRuta.resolve(nombreArchivo);

        // Extraer todos los vuelos del mapa (ya que está agrupado por origen)
        List<Vuelo> vuelos = new ArrayList<>();
        for (List<Vuelo> lista : mapaVuelos.getVuelosPorOrigen().values()) {
            vuelos.addAll(lista);
        }

        if (vuelos.isEmpty()) {
            System.out.println("No hay vuelos disponibles. No se generarán cancelaciones.");
            return;
        }

        Random random = new Random();
        List<String> lineas = new ArrayList<>();

        // Recorremos los días del mes
        YearMonth ym = YearMonth.of(year, mes);
        for (int dia = 1; dia <= ym.lengthOfMonth(); dia++) {
            if (random.nextDouble() < probCancelDia) {
                int numCancel = random.nextInt(maxCancelPorDia) + 1;
                for (int i = 0; i < numCancel; i++) {
                    Vuelo v = vuelos.get(random.nextInt(vuelos.size()));

                    String idVuelo = v.getOrigen() + "-" +
                            v.getDestino() + "-" +
                            v.getHoraOrigen();

                    String linea = String.format("%02d.%s", dia, idVuelo);
                    lineas.add(linea);
                }
            }
        }

        // Ordenar y escribir
        Collections.sort(lineas);
        escribirArchivo(salida, lineas);

        System.out.println("Archivo generado: " + salida.toAbsolutePath() +
                " (" + lineas.size() + " cancelaciones)");
    }

    private static void escribirArchivo(Path ruta, List<String> lineas) {
        try (FileWriter writer = new FileWriter(ruta.toFile(), StandardCharsets.UTF_8)) {
            for (String linea : lineas) writer.write(linea + "\n");
        } catch (IOException e) {
            System.err.println("Error al escribir archivo: " + e.getMessage());
        }
    }

    // Método de prueba
    public static void main(String[] args) {
        Path out = Paths.get("E:\\PUCP\\2025-2\\DP1\\MoraPack\\backend\\target\\classes");
        AeropuertosMap aeropuertosMap = new AeropuertosMap();

        try (Scanner sc = ArchivoUtils.getScannerFromResource("c.1inf54.25.2.Aeropuerto.husos.v1.20250818__estudiantes.txt")) {
            if (sc == null) {
                System.err.println("No se pudo abrir el archivo de aeropuertos.");
                return;
            }
            aeropuertosMap.leerDatos(sc);
        }

        VuelosMap mapa = new VuelosMap(aeropuertosMap);

        try (Scanner sc = ArchivoUtils.getScannerFromResource("c.1inf54.25.2.planes_vuelo.v4.20250818.txt")) {
            if (sc == null) {
                System.err.println("No se pudo abrir el archivo de vuelos.");
                return;
            }
            mapa.leerDatos(sc);
        }

        // Genera cancelaciones para octubre de 2025
        generarArchivo(out, 2025, 10, mapa, 0.4, 100);
    }
}
