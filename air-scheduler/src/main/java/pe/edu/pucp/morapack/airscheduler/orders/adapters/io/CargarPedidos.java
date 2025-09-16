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
    public record VentanaPedidos(Instant presenteUTC, List<Pedido> pedidos) {}

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

    public Instant primerInstanteUTC() {
        if (!utcNormalizada) 
            throw new IllegalStateException("Primero llama a normalizarUtc(aeropuertosMap).");
        for (Pedido p : colaPedidos) {
            if (p.getCreatedAtUtc() != null) return p.getCreatedAtUtc();
        }
        return null;
    }

    /********** NUEVO: ventana desde un inicio dado y duración en horas **********/
    public VentanaPedidos ventanaDesde(Instant relojUTC, long horasVentana) {
        if (!utcNormalizada)
            throw new IllegalStateException("Primero llama a normalizarUtc(aeropuertosMap).");
        if (relojUTC == null || horasVentana <= 0 || colaPedidos.isEmpty())
            return new VentanaPedidos(null, List.of());

        Instant presenteUTC = relojUTC.plus(Duration.ofHours(horasVentana));

        List<Pedido> candidatos = new ArrayList<>();
        for (Pedido p : colaPedidos) {
            Instant t = p.getCreatedAtUtc();
            if (t == null) continue;
            if (!t.isAfter(presenteUTC)) candidatos.add(p);
            else break; // la cola está ordenada temporalmente: podemos cortar
        }
        return new VentanaPedidos(presenteUTC, candidatos);
    }

    // --- NUEVO: listar sin remover ---
    /** Lista (NO remueve) los pedidos con createdAtUtc <= hastaIncl, respetando el orden de la cola. */
    public List<Pedido> listarHasta(Instant hastaIncl) {
        if (!utcNormalizada)
            throw new IllegalStateException("Primero llama a normalizarUtc(aeropuertosMap).");
        List<Pedido> res = new ArrayList<>();
        for (Pedido p : colaPedidos) {
            Instant t = p.getCreatedAtUtc();
            if (t == null || t.isAfter(hastaIncl)) break; // la cola está ordenada por tiempo
            res.add(p);
        }
        return res;
    }

    // --- NUEVO: remover solo los ya completados ---
    /** Elimina de la cola únicamente los pedidos cuyo id esté en idsCompletados. */
    public void eliminarPedidosCompletados(Set<Integer> idsCompletados) {
        if (idsCompletados == null || idsCompletados.isEmpty()) return;
        colaPedidos.removeIf(p -> idsCompletados.contains(p.getIdPedido()));
    }

    // --- NUEVO: saber si aún hay pedidos “futuros” (para saber si seguimos simulando) ---
    /** ¿Quedan pedidos con createdAtUtc > t? */
    public boolean hayPedidosDespuesDe(Instant t) {
        for (Pedido p : colaPedidos) {
            Instant c = p.getCreatedAtUtc();
            if (c != null && c.isAfter(t)) return true;
        }
        return false;
    }


    // ========== Consumo / utilidades ==========
    /** Elimina de la cola los pedidos planificados en la tanda (por id). */
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
