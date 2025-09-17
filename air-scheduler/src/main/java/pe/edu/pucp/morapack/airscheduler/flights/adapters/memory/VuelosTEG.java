package pe.edu.pucp.morapack.airscheduler.flights.adapters.memory;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosEdge;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.AereopuertoNode;

@RequiredArgsConstructor
public final class VuelosTEG {

    private final Map<String, AereopuertoNode> nodos = new HashMap<>();
    private final Map<AereopuertoNode, List<VuelosEdge>> ady = new HashMap<>();

    private static String key(String codigoAP, Instant t) {
        return codigoAP + "|" + (t == null ? "NA" : t.getEpochSecond());
    }

    public AereopuertoNode nodo(String codigoAP, Instant t) {
        return nodos.get(key(codigoAP, t));
    }

    public AereopuertoNode nodoOmega(String codigoAPOmega) {
        return nodos.get(key(codigoAPOmega, null));
    }

    public Collection<AereopuertoNode> nodos() {
        return nodos.values();
    }

    public List<VuelosEdge> out(AereopuertoNode n) {
        return ady.getOrDefault(n, List.of());
    }

    public int cantidadNodos() {
        return nodos.size();
    }

    public int cantidadArcos() {
        return ady.values().stream().mapToInt(List::size).sum();
    }

    public AereopuertoNode agregarONodo(String codigoAP, Instant t, int capacidadAP, boolean esSede) {
        String k = key(codigoAP, t);
        return nodos.computeIfAbsent(k, kk -> new AereopuertoNode(codigoAP, t, capacidadAP, esSede));
    }

    public void agregarArco(VuelosEdge e) {
        ady.computeIfAbsent(e.salida(), k -> new ArrayList<>()).add(e);
    }

    /** Snapshot de todas las aristas. */
    public List<VuelosEdge> arcos() {
        List<VuelosEdge> all = new ArrayList<>(cantidadArcos());
        for (List<VuelosEdge> lst : ady.values())
            all.addAll(lst);
        return all;
    }

    /** Iteración eficiente sobre todas las aristas. */
    public void paraCadaArco(Consumer<VuelosEdge> f) {
        for (List<VuelosEdge> lst : ady.values())
            for (VuelosEdge e : lst)
                f.accept(e);
    }

    // === Helpers para localizar/reducir capacidad en arcos WAIT ===

    /** Devuelve el WAIT (a -> b) si existe, o null. */
    public VuelosEdge buscarWait(AereopuertoNode a, AereopuertoNode b) {
        List<VuelosEdge> lst = ady.get(a);
        if (lst == null)
            return null;
        for (VuelosEdge e : lst) {
            if (e.tipo() == VuelosEdge.Type.WAIT && Objects.equals(e.destino(), b)) {
                return e;
            }
        }
        return null;
    }

    /**
     * Reduce capacidad del WAIT (a -> b) en 'delta'. Si es inmutable, reemplaza la
     * arista.
     */
    public void reducirCapacidadWaitEntre(AereopuertoNode a, AereopuertoNode b, int delta) {
        if (delta <= 0)
            return;
        List<VuelosEdge> lst = ady.get(a);
        if (lst == null)
            return;

        for (int i = 0; i < lst.size(); i++) {
            VuelosEdge e = lst.get(i);
            if (e.tipo() == VuelosEdge.Type.WAIT && Objects.equals(e.destino(), b)) {
                int capActual = e.capacidad(); // asume getter; ajusta si tu nombre difiere
                int nuevaCap = Math.max(0, capActual - delta);

                // Opción segura: recrear la arista WAIT con la nueva capacidad
                VuelosEdge reemplazo = new VuelosEdge(a, b, VuelosEdge.Type.WAIT, nuevaCap, null);
                lst.set(i, reemplazo);
                return;
            }
        }
    }
}