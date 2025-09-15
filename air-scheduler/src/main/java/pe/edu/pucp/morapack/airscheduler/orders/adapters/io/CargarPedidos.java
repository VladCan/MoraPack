package pe.edu.pucp.morapack.airscheduler.orders.adapters.io;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.orders.domain.model.Pedido;

public class CargarPedidos {
    private final Queue<Pedido> colaPedidos;
    private boolean utcNormalizada = false; // flag para evitar doble normalización accidental
    // DTO sencillo con la info de la ventana:
    public record VentanaPedidos(Instant startUtc, Instant presentUtc, List<Pedido> pedidos) {}

    
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

    /** 
     * Paso 2 (se llama luego de leer): coloca createdAtUtc en cada pedido
     * usando el GMT del aeropuerto destino. No cambia el orden de la cola.
     */
    public void normalizarUtc(AeropuertosMap aeropuertosMap) {
        if (utcNormalizada) return; // idempotente
        for (Pedido p : colaPedidos) {
            var a = aeropuertosMap.obtener(p.getDestino());
            int gmt = (a != null) ? a.getGMT() : 0; // fallback seguro
            p.computeUtcFromGmt(gmt);
        }
        utcNormalizada = true;
    }

    /** Útil para asserts o sanity checks opcionales. */
    public boolean isUtcNormalizada() {
        return utcNormalizada;
    }

    /**
     * Devuelve los pedidos cuya createdAtUtc ∈ (presentUtc - horas, presentUtc], donde presentUtc
     * es el máximo createdAtUtc disponible (o el que pases por parámetro).
     * Por defecto ordena por tiempo ascendente (antiguo→reciente) para facilitar planificación.
     */
    public VentanaPedidos ultimasHoras(long horas) {
        return ultimasHoras(horas, null, true);
    }

    public VentanaPedidos ultimasHoras(long horas, Instant presentUtcOverride, boolean ordenarAscPorUtc) {
        if (!utcNormalizada)
            throw new IllegalStateException("Primero llama a normalizarUtc(aeropuertosMap).");
        if (horas <= 0)
            return new VentanaPedidos(null, null, List.of());

        // 1) Definir "presente": el más reciente createdAtUtc, o el override si lo pasan
        Instant present = (presentUtcOverride != null) ? presentUtcOverride :
                colaPedidos.stream()
                           .map(Pedido::getCreatedAtUtc)
                           .filter(Objects::nonNull)
                           .max(Comparator.naturalOrder())
                           .orElse(null);
        if (present == null) return new VentanaPedidos(null, null, List.of());

        // 2) Intervalo [present - horas, present]
        Instant start = present.minus(Duration.ofHours(horas));

        // 3) Filtrar y ordenar (ascendente por tiempo para planificar en orden cronológico)
        List<Pedido> ventana = colaPedidos.stream()
            .filter(p -> {
                Instant t = p.getCreatedAtUtc();
                return t != null && !t.isBefore(start) && !t.isAfter(present);
            })
            .sorted((a, b) -> {
                if (!ordenarAscPorUtc) return 0; // preserva orden de la cola
                return a.getCreatedAtUtc().compareTo(b.getCreatedAtUtc());
            })
            .collect(Collectors.toList());

        return new VentanaPedidos(start, present, ventana);
    }
    /** Elimina de la cola los pedidos planificados en la tanda. */
    public void consumir(List<Pedido> tanda) {
        if (tanda == null || tanda.isEmpty()) return;
        // Usamos idPedido para robustez (por si cambian referencias)
        Set<Integer> ids = tanda.stream().map(Pedido::getIdPedido).collect(Collectors.toSet());
        colaPedidos.removeIf(p -> ids.contains(p.getIdPedido()));
    }

    public void mostrar() {
        for (Pedido p : colaPedidos) {
            System.out.println(p);
        }
    }
    public boolean isEmpty() {
        return colaPedidos.isEmpty();
    }

}
