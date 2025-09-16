package pe.edu.pucp.morapack.airscheduler.orders.adapters.io;

import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.orders.domain.model.Pedido;

@Getter
public class CargarPedidos {

    private final Queue<Pedido> colaPedidos = new LinkedList<>();
    private boolean utcNormalizada = false; // evita doble normalización

    // DTO simple de la ventana
    public record VentanaPedidos(Instant inicioUTC, Instant presenteUTC, List<Pedido> pedidos) {}

    // ========== Operaciones básicas ==========
    public void agregar(Pedido pedido) {
        colaPedidos.add(pedido);
    }

    /** Copia defensiva en lista. */
    public List<Pedido> getLista() {
        return new ArrayList<>(colaPedidos);
    }

    public void leerDatos(Scanner sc) {
        while (sc.hasNextLine()) {
            Pedido pedido = new Pedido();
            pedido.leer(sc);
            agregar(pedido);
        }
    }

    /**
     * Paso 2 (luego de leer): establece createdAtUtc en cada pedido
     * usando el GMT del aeropuerto DESTINO. No altera el orden de la cola.
     */
    public void normalizarUtc(AeropuertosMap aeropuertosMap) {
        if (utcNormalizada) return; // idempotente

        for (Pedido pedido : colaPedidos) {
            var aeropuertoDestino = aeropuertosMap.obtener(pedido.getDestino());
            int gmt = (aeropuertoDestino != null) ? aeropuertoDestino.getGMT() : 0; // fallback seguro
            pedido.computeUtcFromGmt(gmt);
        }
        utcNormalizada = true;
    }

    /** Útil para asserts/sanity checks. */
    public boolean isUtcNormalizada() {
        return utcNormalizada;
    }

    // ========== Ventanas de pedidos ==========
    /** Devuelve pedidos con createdAtUtc en (presente - horas, presente], orden ascendente por tiempo. */
    public VentanaPedidos ultimasHoras(long horas) {
        return ultimasHoras(horas, null, true);
    }

    public VentanaPedidos ultimasHoras(long horas, Instant presenteUTCOverride, boolean ordenarAscPorUTC) {
        if (!utcNormalizada)
            throw new IllegalStateException("Primero llama a normalizarUtc(aeropuertosMap).");
        if (horas <= 0) return new VentanaPedidos(null, null, List.of());

        // 1) Definir "presente": el mayor createdAtUtc (o el override si lo pasas)
        Instant presenteUTC = (presenteUTCOverride != null)
                ? presenteUTCOverride
                : colaPedidos.stream()
                        .map(Pedido::getCreatedAtUtc)
                        .filter(Objects::nonNull)
                        .max(Comparator.naturalOrder())
                        .orElse(null);

        if (presenteUTC == null) return new VentanaPedidos(null, null, List.of());

        // 2) Intervalo [inicio, presente]
        Instant inicioUTC = presenteUTC.minus(Duration.ofHours(horas));

        // 3) Filtrar y ordenar (ascendente por tiempo si se pide)
        List<Pedido> ventana = colaPedidos.stream()
                .filter(p -> {
                    Instant t = p.getCreatedAtUtc();
                    return t != null && !t.isBefore(inicioUTC) && !t.isAfter(presenteUTC);
                })
                .sorted((a, b) -> {
                    if (!ordenarAscPorUTC) return 0; // preserva orden de la cola
                    return a.getCreatedAtUtc().compareTo(b.getCreatedAtUtc());
                })
                .collect(Collectors.toList());

        return new VentanaPedidos(inicioUTC, presenteUTC, ventana);
    }

    // ========== Consumo / utilidades ==========
    /** Elimina de la cola los pedidos planificados en la tanda (por idPedido). */
    public void consumir(List<Pedido> tanda) {
        if (tanda == null || tanda.isEmpty()) return;
        Set<Integer> ids = tanda.stream().map(Pedido::getIdPedido).collect(Collectors.toSet());
        colaPedidos.removeIf(p -> ids.contains(p.getIdPedido()));
    }

    public void mostrar() {
        for (Pedido p : colaPedidos) System.out.println(p);
    }

    public boolean isEmpty() {
        return colaPedidos.isEmpty();
    }
}
