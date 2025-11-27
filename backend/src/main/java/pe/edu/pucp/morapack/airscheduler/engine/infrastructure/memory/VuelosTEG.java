package pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.AereopuertoNode;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Vuelo;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.VuelosEdge;

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

    public Map<String, List<Vuelo>> getVuelosPorOrigen() {
        Map<String, List<Vuelo>> map = new HashMap<>();

        paraCadaArco(e -> {
            if (e.isFlight() && e.vuelo() != null) {
                String origen = e.salida().getCodigoAP();
                map.computeIfAbsent(origen, k -> new ArrayList<>())
                        .add(e.vuelo());
            }
        });

        return map;
    }

    public void cancelarVuelos(List<String> vuelosCancelados) {
        if (vuelosCancelados == null || vuelosCancelados.isEmpty()) {
            return;
        }

        System.out.println("[VuelosTEG] Cancelando vuelos: " + vuelosCancelados);

        // --- Eliminación optimizada por key directa ---
        for (String vc : vuelosCancelados) {

            String key = parsearKey(vc);  // clave del nodo salida
            AereopuertoNode node = nodos.get(key);

            if (node == null) {
                System.out.println("[WARN] Nodo no encontrado para vuelo " + vc + " (key=" + key + ")");
                continue;
            }

            List<VuelosEdge> vuelos = ady.get(node);
            if (vuelos == null) continue;

            //vuelos.removeIf(e -> vc.equals(e.idInstancia()) && e.isFlight());

            // Si el nodo quedó sin aristas → eliminar del mapa
            if (vuelos.isEmpty()) {
                ady.remove(node);
            }
        }

        // --- Limpieza general (opcional si quieres mantener tu lógica original) ---
        ady.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }
    private String parsearKey(String idVuelo) {
        // Ej: "EBCI-OYSN-20250107-0638"
        String[] p = idVuelo.split("-");
        String origen = p[0];
        String fecha = p[2];
        String hora = p[3];

        // Convertir fecha y hora a Instant
        LocalDate ld = LocalDate.parse(fecha, DateTimeFormatter.ofPattern("yyyyMMdd"));
        LocalTime lt = LocalTime.parse(hora, DateTimeFormatter.ofPattern("HHmm"));

        Instant instant = LocalDateTime.of(ld, lt)
                .atZone(ZoneOffset.UTC)
                .toInstant();

        // La key debe coincidir con tu método key(codigoAP, Instant t)
        return origen + "|" + instant.getEpochSecond();
    }


}