package pe.pucp.edu.morapack.planner;


public class ProcesadorPedidos {
   /* private ColaPedidos cola;
    private long relojSimulacion = 0; // horas simuladas

    public ProcesadorPedidos(ColaPedidos cola) {
        this.cola = cola;
    }

    public void avanzarTiempo(long horas) {
        relojSimulacion += horas;
        if (relojSimulacion % 5 == 0) {
            procesarLote();
        }
    }

    private void procesarLote() {
        List<Pedido> lote = new ArrayList<>();
        Pedido p;
        while ((p = cola.obtenerPedido()) != null) {
            lote.add(p);
        }
        if (!lote.isEmpty()) {
            System.out.println("Procesando lote de " + lote.size() + " pedidos en hora " + relojSimulacion);
            // Aquí invocas el algoritmo genético
            //AlgoritmoGenetico ga = new AlgoritmoGenetico();
            //ga.resolver(lote);
        }
    }*/
}
