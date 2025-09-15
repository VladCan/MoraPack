package pe.edu.pucp.morapack.airscheduler.flights.adapters.memory;

import java.time.Instant;
import java.util.*;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosEdge;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosNode;

public final class VuelosTEG {
    private final Map<String, VuelosNode> nodes = new HashMap<>();
    private final Map<VuelosNode, List<VuelosEdge>> adj = new HashMap<>();

    private static String key(String icao, Instant t) {
        return icao + "|" + (t == null ? "NA" : t.getEpochSecond());
    }

    public VuelosNode node(String icao, Instant t) {
        return nodes.get(key(icao, t));
    }
    public VuelosNode node(String icao) { // sólo útil para Ω (t=null)
        return nodes.get(key(icao, null));
    }

    public Collection<VuelosNode> nodes() { return nodes.values(); }
    public List<VuelosEdge> out(VuelosNode n) { return adj.getOrDefault(n, List.of()); }

    public VuelosNode addOrGetNode(String icao, Instant t, int capacidad, boolean superSource) {
        String k = key(icao, t);
        return nodes.computeIfAbsent(k, kk -> new VuelosNode(icao, t, capacidad, superSource));
    }

    public void addEdge(VuelosEdge e) {
        adj.computeIfAbsent(e.getFrom(), k -> new ArrayList<>()).add(e);
    }

    public int nodeCount() { return nodes.size(); }
    public int edgeCount() { return adj.values().stream().mapToInt(List::size).sum(); }
}
