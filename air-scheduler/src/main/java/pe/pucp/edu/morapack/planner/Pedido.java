package pe.pucp.edu.morapack.planner;


import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Scanner;

public class Pedido {
    private int idPedido;       // Identificador único del pedido
    private int idCliente;      // Identificador del cliente
    private String destino;     // Ciudad o código de aeropuerto destino
    private LocalDateTime  fecha;    // Fecha del pedido
    private int cantidad;       // Cantidad de producto solicitada

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

    // Getters y Setters
    public int getIdPedido() {
        return idPedido;
    }

    public void setIdPedido(int idPedido) {
        this.idPedido = idPedido;
    }

    public int getIdCliente() {
        return idCliente;
    }

    public void setIdCliente(int idCliente) {
        this.idCliente = idCliente;
    }

    public String getDestino() {
        return destino;
    }

    public void setDestino(String destino) {
        this.destino = destino;
    }

    public LocalDateTime  getFecha() {
        return fecha;
    }

    public void setFecha(LocalDateTime  fecha) {
        this.fecha = fecha;
    }

    public int getCantidad() {
        return cantidad;
    }

    public void setCantidad(int cantidad) {
        this.cantidad = cantidad;
    }

    @Override
    public String toString() {
        return idPedido + " | Cliente: " + idCliente + " | Destino: " + destino +
                " | Fecha: " + fecha + " | Cant: " + cantidad;
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
}
