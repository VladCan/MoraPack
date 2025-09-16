package pe.edu.pucp.morapack.airscheduler.scheduling.domain.model;



import java.time.LocalTime;
import java.util.*;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.Vuelo;
import pe.edu.pucp.morapack.airscheduler.orders.domain.model.Pedido;

public class Solucion {
    private final Map<Pedido, List<Vuelo>> asignaciones;
    private final List<Pedido> pedidos;
    public final Map<String, List<Vuelo>> vuelosPorOrigen;
    private final AeropuertosMap aeropuertosMap;
    public final List<String> sedes;

    public Solucion(List<Pedido> pedidos, Map<String, List<Vuelo>> vuelosPorOrigen,
                    AeropuertosMap aeropuertosMap, List<String> sedes) {
        this.pedidos = pedidos;
        this.vuelosPorOrigen = vuelosPorOrigen;
        this.aeropuertosMap = aeropuertosMap;
        this.sedes = sedes;
        this.asignaciones = new HashMap<>();
    }

    // Copiar solución
    public Solucion(Solucion other) {
        this.pedidos = other.pedidos;
        this.vuelosPorOrigen = other.vuelosPorOrigen;
        this.aeropuertosMap = other.aeropuertosMap;
        this.sedes = other.sedes;
        this.asignaciones = new HashMap<>();
        for (Map.Entry<Pedido, List<Vuelo>> e : other.asignaciones.entrySet()) {
            this.asignaciones.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
    }

    // Inicializar solución (heurística simple)
    public void init() {
        for (Pedido p : pedidos) {
            List<Vuelo> ruta = new ArrayList<>();
            // Simple: asigna primer vuelo disponible desde cualquier sede al destino
            for (String sede : sedes) {
                List<Vuelo> vuelos = vuelosPorOrigen.get(sede);
                if (vuelos != null) {
                    for (Vuelo v : vuelos) {
                        if (v.getDestino().equals(p.getDestino()) && v.getCapacidad() >= p.getCantidad()) {
                            ruta.add(v);
                            break;
                        }
                    }
                }
                if (!ruta.isEmpty()) break;
            }
            asignaciones.put(p, ruta);
        }
    }

    public double getCostoTotal() {
        double total = 0;
        for (List<Vuelo> vuelos : asignaciones.values()) {
            for (Vuelo v : vuelos) total += v.getCosto();
        }
        return total;
    }

    public Map<Pedido, List<Vuelo>> getAsignaciones() {
        return asignaciones;
    }

    public void imprimir() {
        for (Map.Entry<Pedido, List<Vuelo>> e : asignaciones.entrySet()) {
            System.out.println(e.getKey());
            for (Vuelo v : e.getValue()) System.out.println("  " + v);
        }
    }

    public boolean esValida(Pedido p, List<Vuelo> ruta) {
        if (ruta.isEmpty()) return false;

        int totalCapacidad = 0;
        for (Vuelo v : ruta) totalCapacidad += v.getCapacidad();
        if (totalCapacidad < p.getCantidad()) return false;

        // Calcular tiempo total de entrega aproximado
        LocalTime salida = ruta.get(0).getHoraOrigen();
        LocalTime llegada = ruta.get(ruta.size() - 1).getHoraDestino();

        long minutosTotales = java.time.Duration.between(salida, llegada).toMinutes();
        long plazoMax = p.getPlazoMaxMinutos(aeropuertosMap.obtener(ruta.get(0).getOrigen()).getContinente());

        return minutosTotales <= plazoMax;
    }

}