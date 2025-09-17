package pe.edu.pucp.morapack.airscheduler.orders.domain.model;


import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Scanner;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Pedido {
    private int idPedido;       // Identificador único del pedido
    private int idCliente;      // Identificador del cliente
    private String destino;     // Ciudad o código de aeropuerto destino
    private String origen;
    private LocalDateTime  fecha;    // Fecha del pedido
    private Instant createdAtUtc;   // <<— NUEVO: normalizado a UTC
    private int cantidad;       // Cantidad de producto solicitada
    private String continenteDestino;

    // Constructor


    public Pedido() {
    }

    public Pedido(int idPedido, int idCliente, String destino, LocalDateTime  fecha, int cantidad) {
        this.idPedido = idPedido;
        this.idCliente = idCliente;
        this.destino = destino;
        this.fecha = fecha;
        this.cantidad = cantidad;
    }


    /** Normaliza 'fecha' (LocalDateTime local en destino) a UTC dado un GMT entero. */
    public void computeUtcFromGmt(int gmtHours) {
        this.createdAtUtc = fecha.atOffset(ZoneOffset.ofHours(gmtHours)).toInstant();
    }

    @Override
    public String toString() {
        return idPedido + " | Cliente: " + idCliente + " | Destino: " + destino +
               " | FechaLocal: " + fecha + " | UTC: " + createdAtUtc + " | Cant: " + cantidad;
    }

    public void leer(Scanner sc){
        if (!sc.hasNextLine()) return;
        String linea = sc.nextLine();
        String[] partes = linea.split(",");
        idPedido = Integer.parseInt(partes[0].trim());
        idCliente = Integer.parseInt(partes[1].trim());
        destino = partes[2].trim();
        fecha = LocalDateTime.parse(partes[3].trim());
        cantidad = Integer.parseInt(partes[4].trim());
    }

    public long getPlazoMaxMinutos(String continenteOrigen) {
        if (continenteOrigen.equals(continenteDestino)) {
            return 2 * 24 * 60; // 2 días
        } else {
            return 3 * 24 * 60; // 3 días
        }
    }

}
