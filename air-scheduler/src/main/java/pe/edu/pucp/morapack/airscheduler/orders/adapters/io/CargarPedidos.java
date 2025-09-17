package pe.edu.pucp.morapack.airscheduler.orders.adapters.io;

import lombok.Getter;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.orders.domain.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.EstadoPedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.PlanPedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.SolucionProgramacion;

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
    public VentanaPedidos acumuladoHasta(Instant presenteUTC) {
        if (!utcNormalizada)
            throw new IllegalStateException("Primero llama a normalizarUtc(aeropuertosMap).");
        if (presenteUTC == null || colaPedidos.isEmpty())
            return new VentanaPedidos(null, List.of());

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

    public void eliminarYActualizarCumplidosHasta(Instant presenteUTC, SolucionProgramacion solucionAnterior) {
        if (solucionAnterior == null || presenteUTC == null) return;

        // índice rápido de planes por id
        Map<Integer, PlanPedido> planes = solucionAnterior.getPlanPorPedido();
        if (planes == null || planes.isEmpty()) return;

        // Recorremos la cola y actualizamos/removemos en el acto
        for (Iterator<Pedido> it = colaPedidos.iterator(); it.hasNext(); ) {
            Pedido pedido = it.next();
            PlanPedido plan = planes.get(pedido.getIdPedido());
            if (plan == null || plan.getTramos() == null || plan.getTramos().isEmpty()) {
                // No hubo asignaciones previas para este pedido: no tocamos su cantidad
                continue;
            }

            // 1) Cantidad efectivamente ENTREGADA (llegada ≤ corte)
            int entregado = plan.getTramos().stream()
                    .filter(t -> t.getLlegadaUtc() != null && !t.getLlegadaUtc().isAfter(presenteUTC))
                    .mapToInt(t -> t.getCantidad())
                    .sum();

            // 2) Cantidad EN VUELO (salió antes del corte y llega después del corte)
            int enVuelo = plan.getTramos().stream()
                    .filter(t -> t.getVuelo().getSalidaUtc() != null && t.getLlegadaUtc() != null)
                    .filter(t -> t.getVuelo().getSalidaUtc().isBefore(presenteUTC) && t.getLlegadaUtc().isAfter(presenteUTC))
                    .mapToInt(t -> t.getCantidad())
                    .sum();

            // 3) Futuro (salida ≥ corte) se IGNORA completamente (replanificable)

            int demandaTotal = pedido.getCantidad();
            int cubiertoIrrevocable = entregado + enVuelo;
            int remanente = Math.max(0, demandaTotal - cubiertoIrrevocable);

            if (remanente == 0) {
                // Ya está completamente cubierto por lo irrevocable: sacarlo de la cola
                it.remove();
            } else {
                // Dejar solo el remanente para replanear
                pedido.setCantidad(remanente);
            }
        }
    }


}
