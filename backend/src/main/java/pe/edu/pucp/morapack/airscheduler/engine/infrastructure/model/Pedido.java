package pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Scanner;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Pedido {
    private int idPedido; // Identificador único del pedido
    private int idCliente; // Identificador del cliente
    private String destino; // Ciudad o código de aeropuerto destino
    private String origen;
    private LocalDateTime fecha; // Fecha del pedido
    private Instant createdAtUtc; // <<— NUEVO: normalizado a UTC
    private int cantidad; // Cantidad de producto solicitada
    private String continenteDestino;

    // Constructor

    public Pedido() {
    }

    public Pedido(Pedido pedido) {
        this.idPedido = pedido.idPedido;
        this.idCliente = pedido.idCliente;
        this.destino = pedido.destino;
        this.origen = pedido.origen;
        this.fecha = pedido.fecha;
        this.createdAtUtc = pedido.createdAtUtc;
        this.cantidad = pedido.cantidad;
        this.continenteDestino = pedido.continenteDestino;
    }
    public Pedido(int idPedido, int idCliente, String destino, LocalDateTime fecha, int cantidad) {
        this.idPedido = idPedido;
        this.idCliente = idCliente;
        this.destino = destino;
        this.fecha = fecha;
        this.cantidad = cantidad;
    }

    public Pedido (int idPedido, int idCliente, String destino, LocalDateTime fecha,
                   Instant fechaUTC, int cantidad){
        this.idPedido = idPedido;
        this.idCliente = idCliente;
        this.destino = destino;
        this.fecha = fecha;
        this.createdAtUtc = fechaUTC;
        this.cantidad = cantidad;
    }

    public Instant getCreatedAtUtc() {
        return createdAtUtc;
    }

    /**
     * Normaliza 'fecha' (LocalDateTime local en destino) a UTC dado un GMT entero.
     */
    public void computeUtcFromGmt(int gmtHours) {
        this.createdAtUtc = fecha.atOffset(ZoneOffset.ofHours(gmtHours)).toInstant();
    }

    @Override
    public String toString() {
        return idPedido + " | Cliente: " + idCliente + " | Destino: " + destino +
                " | FechaLocal: " + fecha + " | UTC: " + createdAtUtc + " | Cant: " + cantidad;
    }

    public void leer(Scanner sc) {
        if (!sc.hasNextLine())
            return;
        String linea = sc.nextLine();
        String[] partes = linea.split(",");
        idPedido = Integer.parseInt(partes[0].trim());
        idCliente = Integer.parseInt(partes[1].trim());
        destino = partes[2].trim();
        fecha = LocalDateTime.parse(partes[3].trim());
        cantidad = Integer.parseInt(partes[4].trim());
    }

    public void leerProfeNew(Scanner sc, int id) {
        if (!sc.hasNextLine())
            return;

        // 1. Leer la línea y limpiar. El separador ahora es el guion ('-').
        String linea = sc.nextLine().trim();
        String[] partes = linea.split("-");

        // Esperamos 7 partes: ID_ARCHIVO - YYYYMMDD - HH - MM - DESTINO - CANTIDAD -
        // ID_CLIENTE
        if (partes.length != 7) {
            throw new IllegalArgumentException("Formato inválido (esperado 7 campos): " + linea);
        }

        // Asignar el idPedido incremental (desechando el ID inicial del archivo)
        idPedido = id;

        try {
            // 2. Parsear Fecha y Hora desde los campos

            // Campo 1: YYYYMMDD (Ej: 20250102)
            String datePart = partes[1].trim();
            if (datePart.length() != 8) {
                throw new NumberFormatException("Formato YYYYMMDD incorrecto: " + datePart);
            }
            int yyyy = Integer.parseInt(datePart.substring(0, 4));
            int MM = Integer.parseInt(datePart.substring(4, 6));
            int dd = Integer.parseInt(datePart.substring(6, 8));

            // Campo 2: HH (Ej: 00 o 01)
            int hh = Integer.parseInt(partes[2].trim());

            // Campo 3: MM (Ej: 50 o 38)
            int mm = Integer.parseInt(partes[3].trim());

            // 3. Asignar campos de producto/cliente
            destino = partes[4].trim();

            // Campo 5: CANTIDAD (Ej: 002 o 001)
            cantidad = Integer.parseInt(partes[5].trim());

            // Campo 6: ID_CLIENTE (Ej: 0029563 o 0009486)
            idCliente = Integer.parseInt(partes[6].trim());

            // 4. Construir la fecha (segundos se asumen 0)
            fecha = LocalDateTime.of(yyyy, MM, dd, hh, mm, 0); // Asume segundos (ss) = 0

        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Error de parseo numérico/fecha en línea: " + linea + " | Causa: " + e.getMessage());
        }
    }

    public void leerProfe(Scanner sc, int id) {
        if (!sc.hasNextLine())
            return;

        String linea = sc.nextLine().trim();
        String[] partes = linea.split("-");

        // Esperamos: yyyy-MM-dd-HH-mm-ss-dest-###-IdClien
        if (partes.length != 9) {
            throw new IllegalArgumentException("Formato inválido: " + linea);
        }

        // 1) Parsear fecha completa
        int yyyy = Integer.parseInt(partes[0]);
        int MM = Integer.parseInt(partes[1]);
        int dd = Integer.parseInt(partes[2]);
        int hh = Integer.parseInt(partes[3]);
        int mm = Integer.parseInt(partes[4]);
        int ss = Integer.parseInt(partes[5]);

        destino = partes[6].trim();
        cantidad = Integer.parseInt(partes[7]); // ###

        // El idCliente está después de la cantidad (separado por "-")
        // pero puede que ya venga en la misma "partes[7]" si se generó mal
        // mejor lo separamos bien usando substring final
        String[] ultimos = linea.split("-");
        if (ultimos.length < 9) {
            throw new IllegalArgumentException("No se encontró IdCliente en: " + linea);
        }
        idCliente = Integer.parseInt(ultimos[8]);

        // Construir la fecha completa
        fecha = LocalDateTime.of(yyyy, MM, dd, hh, mm, ss);

        // Generar un idPedido incremental si no viene en el archivo
        idPedido = id;
    }

    public long getPlazoMaxMinutos(String continenteOrigen) {
        if (continenteOrigen.equals(continenteDestino)) {
            return 2 * 24 * 60; // 2 días
        } else {
            return 3 * 24 * 60; // 3 días
        }
    }

    public static Pedido parsearLineaGigante(String linea, int id) {
        Pedido p = new Pedido();
        p.idPedido = id;

        String[] x = linea.split("-", 7);

        String f = x[1];
        int yyyy = Integer.parseInt(f.substring(0, 4));
        int MM = Integer.parseInt(f.substring(4, 6));
        int dd = Integer.parseInt(f.substring(6, 8));

        int hh = Integer.parseInt(x[2]);
        int mm = Integer.parseInt(x[3]);

        p.destino = x[4];
        p.cantidad = Integer.parseInt(x[5]);
        p.idCliente = Integer.parseInt(x[6]);

        p.fecha = LocalDateTime.of(yyyy, MM, dd, hh, mm);

        return p;
    }

}
