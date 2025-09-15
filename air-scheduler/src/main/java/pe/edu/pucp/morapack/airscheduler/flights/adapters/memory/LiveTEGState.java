package pe.edu.pucp.morapack.airscheduler.flights.adapters.memory;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosEdge;

/**
 * Estado vivo del TEG entre tandas:
 * - Mantiene vuelos disponibles y su capacidad remanente.
 * - Aplica bookings de la tanda.
 * - Avanza el reloj: remueve vuelos ya arribados y retorna entregas materializadas.
 */
public class LiveTEGState {

    /* ===== Modelos internos mínimos ===== */
    public static final class FlightKey {
        public final String origen;
        public final String destino;
        public final Instant depUtc;
        public final Instant arrUtc;

        public FlightKey(String o, String d, Instant dep, Instant arr) {
            this.origen = o; this.destino = d; this.depUtc = dep; this.arrUtc = arr;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FlightKey fk)) return false;
            return Objects.equals(origen, fk.origen)
                && Objects.equals(destino, fk.destino)
                && Objects.equals(depUtc, fk.depUtc)
                && Objects.equals(arrUtc, fk.arrUtc);
        }
        @Override public int hashCode() { return Objects.hash(origen, destino, depUtc, arrUtc); }
    }

    public static final class FlightInstance {
        public final FlightKey key;
        public final int capTotal;
        public int capUsada;

        public FlightInstance(FlightKey key, int capTotal) {
            this.key = key;
            this.capTotal = capTotal;
            this.capUsada = 0;
        }
        public int capLibre() { return capTotal - capUsada; }
    }

    /* ===== Estado ===== */
    private final Map<FlightKey, FlightInstance> flights = new HashMap<>();

    private LiveTEGState() {}

    /** Inicializa con el catálogo completo (sin reservas). */
    public static LiveTEGState init(AeropuertosMap aMap, VuelosMap catalog) {
        LiveTEGState st = new LiveTEGState();
        // Construye un TEG de catálogo para quedarse con las instancias de vuelo (capacidad total).
        // t0/t1 amplios para capturar todo el catálogo en bruto; la poda la hace populateTEG(...)
        var now = Instant.EPOCH;
        var far = Instant.ofEpochSecond(Long.MAX_VALUE / 2); // horizonte lejano
        var tegCatalog = VueloTEGBuilder.build(aMap, catalog, now, far, Set.of());
        st.mergeFromTEGCatalog(tegCatalog);
        return st;
    }

    /** Integra al estado instancias de vuelo del catálogo (sin reservas). */
    public void mergeFromTEGCatalog(VuelosTEG teg) {
        for (var e : teg.getEdges()) {
            if (e.getType() != VuelosEdge.Type.FLIGHT) continue;
            var from = e.getFrom();
            var to   = e.getTo();
            var k = new FlightKey(from.getIcao(), to.getIcao(), from.getTimeUtc(), to.getTimeUtc());
            int capTotal = e.getCapacity();
            flights.computeIfAbsent(k, kk -> new FlightInstance(kk, capTotal));
        }
    }

    /** Avanza el reloj: elimina vuelos ya arribados y devuelve "entregas" materializadas. */
    public List<DeliveryRecord> advanceClock(Instant now, int batchNo) {
        // En esta versión mínima, no sabemos qué pedidos iban en cada vuelo ⇒
        // retornamos lista vacía y sólo limpiamos vuelos ya arribados.
        var toRemove = flights.keySet().stream()
                .filter(k -> !k.arrUtc.isAfter(now)) // arrUtc <= now
                .collect(Collectors.toList());
        toRemove.forEach(flights::remove);
        return List.of(); // placeholder
    }

    /** Aplica bookings de la tanda al estado vivo. */
    public void applyBookings(List<BookingRecord> bookings) {
        for (var b : bookings) {
            var k = new FlightKey(b.origen, b.destino, b.depUtc, b.arrUtc);
            var inst = flights.get(k);
            if (inst == null) {
                // si no existe, lo ignoramos (o lanzar excepción si prefieres)
                continue;
            }
            inst.capUsada += b.cantidad;
            if (inst.capUsada > inst.capTotal) {
                throw new IllegalStateException("Overbooking en " + k.origen + "->" + k.destino + "@" + k.depUtc);
            }
        }
    }

    /**
     * Poblado del TEG recortando por [t0, t1] y usando capacidad remanente.
     * El builder lo llama para generar el grafo planificable de la tanda.
     */
    public void populateTEG(VuelosTEG teg, AeropuertosMap aMap, Instant t0, Instant t1, Set<String> sedes) {
        // Crea nodos en rebanada temporal y aristas de vuelo con capLibre.
        for (var inst : flights.values()) {
            var dep = inst.key.depUtc;
            var arr = inst.key.arrUtc;
            if (dep.isBefore(t0) || arr.isAfter(t1)) continue;  // fuera de horizonte
            int capLibre = inst.capLibre();
            if (capLibre <= 0) continue;

            var nFrom = teg.addOrGetNode(inst.key.origen, dep, /*cap almacén*/ 0, /*Ω*/ false);
            var nTo   = teg.addOrGetNode(inst.key.destino, arr, /*cap almacén*/ 0, /*Ω*/ false);
            teg.addEdge(new VuelosEdge(nFrom, nTo, VuelosEdge.Type.FLIGHT, capLibre, null));
        }

        // Agrega Ω y edges de SUPPLY y WAIT según tu lógica (aquí mínimo viable)
        var omega = teg.addOrGetNode("Ω", null, 0, true);
        for (var n : teg.nodes()) {
            if (n.isSuperSource()) continue;
            // supply desde Ω a cada nodo de tiempo inicial del aeropuerto si es sede
            if (sedes.contains(n.getIcao()) && !n.getTimeUtc().isBefore(t0)) {
                teg.addEdge(new VuelosEdge(omega, n, VuelosEdge.Type.SUPPLY, /*cap arbitraria*/ 1000000, null));
            }
            // WAIT: para simplificar, no encadenamos almacenamientos en este stub
        }
    }
}
