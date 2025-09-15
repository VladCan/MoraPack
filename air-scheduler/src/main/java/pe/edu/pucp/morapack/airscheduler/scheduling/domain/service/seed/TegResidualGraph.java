package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosEdge;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosNode;

public final class TegResidualGraph {

    // Mapeo nodo TEG -> id entero
    private final Map<VuelosNode, Integer> idByNode = new HashMap<>();
    private final List<VuelosNode> nodeById = new ArrayList<>();
    private final List<List<TegResidualEdge>> adj = new ArrayList<>();

    // índice del super-source Ω
    private final int omegaId;

    private TegResidualGraph(VuelosTEG G, CostPolicy costPolicy) {
        // 1) indexa nodos
        for (var n : G.nodes()) {
            int id = nodeById.size();
            idByNode.put(n, id);
            nodeById.add(n);
            adj.add(new ArrayList<>());
        }
        // 2) arcos base (FLIGHT/WAIT/SUPPLY)
        for (var n : G.nodes()) {
            int u = idByNode.get(n);
            for (var e : G.out(n)) {
                int v = idByNode.get(e.getTo());
                // costo: para WAIT computa duración
                long c = 0;
                if (e.getType() == VuelosEdge.Type.WAIT) {
                    Instant tU = e.getFrom().getTimeUtc();
                    Instant tV = e.getTo().getTimeUtc();
                    var dur = (tU != null && tV != null) ? Duration.between(tU, tV) : Duration.ZERO;
                    c = costPolicy.costOf(e, dur);
                } else {
                    c = costPolicy.costOf(e, null);
                }
                addEdge(u, v, e.getCapacity(), c, e);
            }
        }
        // 3) localiza Ω
        int omega = -1;
        for (int i = 0; i < nodeById.size(); i++) {
            if (nodeById.get(i).isSuperSource()) { omega = i; break; }
        }
        this.omegaId = omega;
    }

    public static TegResidualGraph from(VuelosTEG G, CostPolicy costPolicy) {
        return new TegResidualGraph(G, costPolicy);
    }

    private void addEdge(int u, int v, int cap, long cost, VuelosEdge backing) {
        var fwd = new TegResidualEdge(u, v, backing, cap, cost);
        var rev = new TegResidualEdge(v, u, null, 0, -cost);
        fwd.rev = rev; rev.rev = fwd;
        adj.get(u).add(fwd);
        adj.get(v).add(rev);
    }

    public int omega() { return omegaId; }

    public int addVirtualNode(VuelosNode n) {
        int id = nodeById.size();
        nodeById.add(n);
        adj.add(new ArrayList<>());
        idByNode.put(n, id); // ✅ agrega el mapeo, evita duplicados del mismo slot
        return id;
    }

    public void addVirtualSupplyToNode(int toId, int bigM) {
        // Ω -> toId con cap Big-M, costo 0
        addEdge(omegaId, toId, bigM, 0, null);
    }

    public void addEdgeIds(int u, int v, int cap, long cost) {
        addEdge(u, v, cap, cost, null);
    }

    public List<List<TegResidualEdge>> adj() { return adj; }
    public List<VuelosNode> nodes() { return nodeById; }
    public Integer idOf(VuelosNode n) { return idByNode.get(n); }
}