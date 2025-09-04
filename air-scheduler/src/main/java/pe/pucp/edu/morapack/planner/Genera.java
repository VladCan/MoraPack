package pe.pucp.edu.morapack.planner;

import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

public class Genera {
    private static final String[] DESTINOS = {
            "SKBO","SEQM","SVMI","SBBR","SPIM","SLLP","SCEL","SABE","SGAS","SUAA","LATI","EDDI","LOWW","EBCI","UMMS","LBSF","LKPR","LDZA","EKCH","EHAM","VIDP","OSDI","OERK","OMDB","OAKB","OOMS","OYSN","OPKC","UBBB","OJAI"
    };

    public static void generarArchivo(String ruta, int cantidadPedidos) {
        Random random = new Random();
        DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

        try (FileWriter writer = new FileWriter(ruta)) {
            for (int i = 1; i <= cantidadPedidos; i++) {
                int idPedido = i;
                int idCliente = 100 + random.nextInt(50); // clientes entre 100 y 150
                String destino = DESTINOS[random.nextInt(DESTINOS.length)];
                LocalDateTime fechaPedido = LocalDateTime.now()
                        .plusHours(random.nextInt(72)); // hasta 3 días después
                int cantidad = 10 + random.nextInt(150); // entre 50 y 250

                writer.write(idPedido + "," + idCliente + "," + destino + "," +
                        fechaPedido.format(formatter) + "," + cantidad + "\n");
            }

            System.out.println("Archivo generado en: " + ruta);

        } catch (IOException e) {
            System.out.println("Error al generar archivo: " + e.getMessage());
        }
    }

    // para probarlo directamente
    public static void main(String[] args) {
        generarArchivo("src/main/resources/pedidos.txt", 100); // genera 10 pedidos
    }
}