package pe.edu.pucp.morapack.airscheduler.engine.scheduling.run;


import java.util.Scanner;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.ArchivoUtils;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.CargarPedidos;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Pedido;

/**
 * Script para inspeccionar los pedidos del dataset y ver qué rangos de tiempo tienen
 */
public class InspectPedidos {

    public static void main(String[] args) throws Exception {
        System.out.println("[InspectPedidos] Cargando dataset...");
        
        // Cargar aeropuertos
        AeropuertosMap aeropuertosMap = new AeropuertosMap();
        try (Scanner sc = ArchivoUtils.getScannerFromResource(
                "c.1inf54.25.2.Aeropuerto.husos.v1.20250818__estudiantes.txt")) {
            if (sc != null) {
                aeropuertosMap.leerDatos(sc);
                System.out.println("[InspectPedidos] Aeropuertos cargados: " + aeropuertosMap.size());
            }
        }
        
        // Cargar pedidos
        CargarPedidos pedidos = new CargarPedidos();
        try (Scanner sc = ArchivoUtils.getScannerFromResource("pedidosProfe.txt")) {
            if (sc != null) {
                pedidos.leerDatosProfe(sc);
                pedidos.normalizarUtc(aeropuertosMap);
                pedidos.ordenarPorUTC();
                System.out.println("[InspectPedidos] Pedidos cargados: " + pedidos.getLista().size());
            }
        }
        
        // Mostrar los primeros 10 pedidos
        System.out.println("\n=== PRIMEROS 10 PEDIDOS ===");
        int count = 0;
        for (Pedido p : pedidos.getLista()) {
            if (count >= 10) break;
            System.out.println("ID: " + p.getIdPedido() + 
                             " | Cliente: " + p.getIdCliente() + 
                             " | Destino: " + p.getDestino() + 
                             " | Cantidad: " + p.getCantidad() + 
                             " | UTC: " + p.getCreatedAtUtc());
            count++;
        }
        
        // Mostrar los últimos 10 pedidos
        System.out.println("\n=== ÚLTIMOS 10 PEDIDOS ===");
        count = 0;
        var listaPedidos = pedidos.getLista();
        for (int i = Math.max(0, listaPedidos.size() - 10); i < listaPedidos.size(); i++) {
            Pedido p = listaPedidos.get(i);
            System.out.println("ID: " + p.getIdPedido() + 
                             " | Cliente: " + p.getIdCliente() + 
                             " | Destino: " + p.getDestino() + 
                             " | Cantidad: " + p.getCantidad() + 
                             " | UTC: " + p.getCreatedAtUtc());
        }
        
        // Rango temporal del dataset
        if (!listaPedidos.isEmpty()) {
            Pedido primero = listaPedidos.get(0);
            Pedido ultimo = listaPedidos.get(listaPedidos.size() - 1);
            System.out.println("\n=== RANGO TEMPORAL DEL DATASET ===");
            System.out.println("Primer pedido: " + primero.getCreatedAtUtc());
            System.out.println("Último pedido: " + ultimo.getCreatedAtUtc());
        }
        
        System.out.println("\n[InspectPedidos] Listo.");
    }
}
