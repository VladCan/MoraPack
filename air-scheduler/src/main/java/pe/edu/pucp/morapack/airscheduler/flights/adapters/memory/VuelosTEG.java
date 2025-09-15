package pe.edu.pucp.morapack.airscheduler.flights.adapters.memory;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

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

    /* ======================================================
     * NUEVOS HELPERS: vistas “aplanadas” de todas las aristas
     * ====================================================== */

    /** Snapshot de TODAS las aristas del TEG (copia defensiva). */
    public List<VuelosEdge> getEdges() {
        List<VuelosEdge> all = new ArrayList<>(edgeCount());
        for (List<VuelosEdge> lst : adj.values()) {
            all.addAll(lst);
        }
        return all;
    }

    /** Snapshot de aristas filtradas por tipo. */
    public List<VuelosEdge> getEdges(VuelosEdge.Type type) {
        List<VuelosEdge> res = new ArrayList<>();
        forEachEdge(e -> {
            if (e.getType() == type) res.add(e);
        });
        return res;
    }

    /** Conveniencia: sólo aristas de vuelo (FLIGHT). */
    public List<VuelosEdge> getFlightEdges() {
        return getEdges(VuelosEdge.Type.FLIGHT);
    }

    /** Iteración sin asignaciones extra. */
    public void forEachEdge(Consumer<VuelosEdge> consumer) {
        for (List<VuelosEdge> lst : adj.values()) {
            for (VuelosEdge e : lst) consumer.accept(e);
        }
    }

    // ==== NUEVO: iterador de TODAS las aristas ====
    public Iterable<VuelosEdge> edges() {
        List<VuelosEdge> all = new ArrayList<>();
        for (List<VuelosEdge> lst : adj.values()) all.addAll(lst);
        return all;
    }
    // ==============================================
}
