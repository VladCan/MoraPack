package pe.edu.pucp.morapack.airscheduler.flights.adapters.memory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosEdge;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosNode;

public final class VuelosGraph {

    private final Map<String, VuelosNode> nodes = new HashMap<>();
    private final Map<VuelosNode, List<VuelosEdge>> adj = new HashMap<>();

    public VuelosNode node(String icao) {
        return nodes.get(icao);
    }

    public Collection<VuelosNode> nodes() {
        return nodes.values();
    }

    public List<VuelosEdge> out(VuelosNode n) {
        return adj.getOrDefault(n, List.of());
    }

    void addNode(VuelosNode n) {
        nodes.put(n.getIcao(), n);
    }

    void addEdge(VuelosEdge e) {
        adj.computeIfAbsent(e.getFrom(), k -> new ArrayList<>()).add(e);
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return adj.values().stream().mapToInt(List::size).sum();
    }
}
