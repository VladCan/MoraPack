package pe.pucp.edu.morapack.planner;

import java.util.*;

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

    public List<Pedido> getLista() {
        return new ArrayList<>(colaPedidos);
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
