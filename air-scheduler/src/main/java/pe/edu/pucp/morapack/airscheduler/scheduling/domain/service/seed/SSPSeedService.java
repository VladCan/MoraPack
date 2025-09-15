package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosEdge;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosNode;
import pe.edu.pucp.morapack.airscheduler.orders.domain.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed.model.OrderAssignment;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed.model.PathArc;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed.model.SeedResult;


/**
 * SSP (Successive Shortest Path) por pedido con ventana de entrega de 20 minutos:
 * - Probamos slots destino (instantes del TEG) en orden creciente y ≤ createdAt+48h.
 *   Cada slot probado se interpreta como FIN DE VENTANA.
 * - Si logramos empujar todo el qty a ese slot, el pedido queda cumplido.
 * - El inicio real de la ventana (slotChosen) se fija en:
 *        windowStart = max(createdAt, lastArrivalAtDest - 20m)
 *   donde lastArrivalAtDest es el instante del ÚLTIMO vuelo al destino usado por el pedido.
 * - Para el reporte, se recortan los WAIT en destino que inician en/tras lastArrivalAtDest,
 *   de modo que no aparezcan esperas "después de haber llegado todo".
 */
public final class SSPSeedService {

    private static final int BIG_M = Integer.MAX_VALUE / 4;
    private static final Duration DELIVERY_WINDOW = Duration.ofMinutes(20);

    private final VuelosTEG teg;
    private final CostPolicy costPolicy;
    private final Set<String> sedesSiempreLlenas;

    public SSPSeedService(VuelosTEG teg, CostPolicy costPolicy, Set<String> sedesSiempreLlenas) {
        this.teg = Objects.requireNonNull(teg);
        this.costPolicy = Objects.requireNonNull(costPolicy);
        this.sedesSiempreLlenas = Objects.requireNonNull(sedesSiempreLlenas);
    }

    public SeedResult build(List<Pedido> pedidos, int kSlotsPorPedido) {
        if (pedidos == null || pedidos.isEmpty()) {
            return new SeedResult(0, 0, 0, 1.0, List.of());
        }

        // Prioriza por deadline (48h), luego por createdAt y por tamaño (desc).
        List<Pedido> orden = pedidos.stream()
                .sorted(Comparator
                        .comparing((Pedido p) -> p.getCreatedAtUtc().plus(Duration.ofHours(48)))
                        .thenComparing(Pedido::getCreatedAtUtc)
                        .thenComparing((Pedido p) -> -p.getCantidad()))
                .collect(Collectors.toList());

        TegResidualGraph R = TegResidualGraph.from(teg, costPolicy);

        List<OrderAssignment> assigns = new ArrayList<>();
        int ok = 0;

        for (Pedido p : orden) {
            String dest = p.getDestino();
            int qty = p.getCantidad();
            Instant created = p.getCreatedAtUtc();
            
            // Candidatos: tiempos del nodo destino en [created, created+48h] (ordenados)
            List<Instant> candidates = SlotGenerator.candidateSlots(teg, dest, created, kSlotsPorPedido);

            boolean done = false;
            // ✅ inicializar best como pendiente por defecto
            OrderAssignment best = new OrderAssignment(
                    p.getIdPedido(), dest, qty, false, null, List.of()
            );
            for (Instant slotEnd : candidates) { // slotEnd = FIN DE LA VENTANA
                // Si el destino es sede, permitimos retiro directo en ese instante
                VuelosNode slotNode = new VuelosNode(dest, slotEnd, 0, false);
                Integer slotId = R.idOf(slotNode);
                if (slotId == null) slotId = R.addVirtualNode(slotNode);

                if (sedesSiempreLlenas.contains(dest)) {
                    R.addVirtualSupplyToNode(slotId, BIG_M);
                }

                // Empujar qty hacia slotEnd
                int source = R.omega();
                SSPMinCostFlow.Result res = SSPMinCostFlow.send(R, source, slotId, qty);

                if (res.flowSent == qty) {
                    // a) Path crudo
                    List<PathArc> rawPath = flatten(res);

                    // b) Último arribo real al destino por vuelo
                    Instant lastArrival = computeLastArrivalAtDest(dest, rawPath);
                    if (lastArrival == null) lastArrival = slotEnd; // safety

                    // c) Inicio de ventana = max(createdAt, lastArrival - 20m)
                    Instant windowStart = lastArrival.minus(DELIVERY_WINDOW);
                    if (windowStart.isBefore(created)) windowStart = created;

                    // d) Recorta WAITs en destino que empiezan en/tras lastArrival
                    List<PathArc> trimmedPath = trimTrailingWaitAtDest(dest, lastArrival, rawPath);

                    best = new OrderAssignment(
                            p.getIdPedido(),
                            dest,
                            qty,
                            true,
                            windowStart,               // <-- inicio de la ventana de 20m
                            List.copyOf(trimmedPath)
                    );
                    ok++;
                    done = true;
                    break;
                } else {
                    // revertimos este intento antes de probar otro slotEnd
                    undoPaths(res);
                }
            }

            if (!done) {
                best = new OrderAssignment(p.getIdPedido(), dest, qty, false, null, List.of());
            }
            assigns.add(best);
        }

        int total = assigns.size();
        int pending = total - ok;
        double fill = total == 0 ? 1.0 : (ok * 1.0 / (double) total);
        return new SeedResult(total, ok, pending, fill, assigns);
    }

    // -------------------- helpers de flujo --------------------

    private static List<PathArc> flatten(SSPMinCostFlow.Result r) {
        List<PathArc> list = new ArrayList<>();
        for (var path : r.usedPaths) {
            int pushed = 0;
            if (!path.isEmpty()) pushed = path.get(path.size() - 1).flow;
            for (var e : path) {
                if (e.backing != null) {
                    list.add(new PathArc(e.backing, pushed));
                }
            }
        }
        return list;
    }

    private static void undoPaths(SSPMinCostFlow.Result r) {
        for (var path : r.usedPaths) {
            int pushed = path.get(path.size() - 1).flow;
            for (var e : path) {
                e.cap += pushed;
                e.flow -= pushed;
                e.rev.cap -= pushed;
            }
        }
    }

    // -------------------- helpers ventana 20m --------------------

    /** Mayor instante de arribo al destino entre los arcos FLIGHT usados. */
    private static Instant computeLastArrivalAtDest(String destIcao, List<PathArc> path) {
        Instant last = null;
        for (PathArc pa : path) {
            VuelosEdge e = pa.edge();
            if (e == null || e.getType() != VuelosEdge.Type.FLIGHT) continue;
            VuelosNode to = e.getTo();
            if (to == null || to.getTimeUtc() == null) continue;
            if (!Objects.equals(destIcao, to.getIcao())) continue;
            Instant arr = to.getTimeUtc();
            if (last == null || arr.isAfter(last)) last = arr;
        }
        return last;
    }

    /** Elimina los WAIT en el destino que empiezan en/tras lastArrival (no aportan a la entrega). */
    private static List<PathArc> trimTrailingWaitAtDest(String destIcao, Instant lastArrival, List<PathArc> path) {
        if (lastArrival == null) return path;
        List<PathArc> out = new ArrayList<>(path.size());
        for (PathArc pa : path) {
            VuelosEdge e = pa.edge();
            if (e == null || e.getType() != VuelosEdge.Type.WAIT) {
                out.add(pa);
                continue;
            }
            VuelosNode from = e.getFrom();
            VuelosNode to   = e.getTo();
            String fIcao = from != null ? from.getIcao() : null;
            String tIcao = to != null ? to.getIcao() : null;
            Instant fTs  = from != null ? from.getTimeUtc() : null;

            boolean atDest = Objects.equals(destIcao, fIcao) && Objects.equals(destIcao, tIcao);
            boolean startsOnOrAfterLast = (fTs != null && !fTs.isBefore(lastArrival));

            if (atDest && startsOnOrAfterLast) {
                // descartamos esta espera posterior a la llegada completa
                continue;
            }
            out.add(pa);
        }
        return out;
    }
}