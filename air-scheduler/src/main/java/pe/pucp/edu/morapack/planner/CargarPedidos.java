package pe.pucp.edu.morapack.planner;

import java.util.LinkedList;
import java.util.Queue;
import java.util.Scanner;

public class CargarPedidos {
    private final Queue<Pedido> colaPedidos;
    public CargarPedidos() {
        colaPedidos = new LinkedList<>();
    }

    public Queue<Pedido> getColaPedidos() {
        return colaPedidos;
    }

    public void agregar(Pedido pe) {
        colaPedidos.add(pe);
    }

    public void leerDatos(Scanner sc) {
        while (sc.hasNextLine()) {
            Pedido pe = new Pedido();
            pe.leer(sc);
            agregar(pe);
        }
    }

    public void mostrar() {
        for (Pedido p : colaPedidos) {
            System.out.println(p);
        }
    }

}
