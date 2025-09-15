package pe.edu.pucp.morapack.airscheduler.flights.adapters.memory;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosEdge;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosNode;

/**
 * Estado vivo del TEG entre tandas:
 * - Mantiene vuelos disponibles y su capacidad remanente (reservas acumuladas).
 * - Aplica bookings de la tanda (carga que “se va en vuelo”).
 * - Avanza el reloj materializando entregas (arribos) al inventario de destino.
 * - Respeta capacidad de bodega por aeropuerto.
 */
public class LiveTEGState {

    /* ================== Tipos auxiliares ================== */

    /** Identifica un vuelo específico en el tiempo expandido. */
    public static final class FlightKey {
        public final String origen;
        public final String destino;
        public final Instant depUtc;
        public final Instant arrUtc;

        public FlightKey(String origen, String destino, Instant depUtc, Instant arrUtc) {
            this.origen = origen;
            this.destino = destino;
            this.depUtc = depUtc;
            this.arrUtc = arrUtc;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FlightKey fk)) return false;
            return Objects.equals(origen, fk.origen) &&
                   Objects.equals(destino, fk.destino) &&
                   Objects.equals(depUtc, fk.depUtc) &&
                   Objects.equals(arrUtc, fk.arrUtc);
        }

        @Override public int hashCode() {
            return Objects.hash(origen, destino, depUtc, arrUtc);
        }

        @Override public String toString() {
            return origen + "@" + depUtc + " -> " + destino + "@" + arrUtc;
        }
    }

    /** Instancia viva de vuelo con su consumo de capacidad. */
    public static final class FlightInstance {
        public final FlightKey key;
        public final int capTotal;
        public int capUsada;

        public FlightInstance(FlightKey key, int capTotal) {
            this.key = key;
            this.capTotal = capTotal;
            this.capUsada = 0;
        }
        public int capLibre() { return Math.max(0, capTotal - capUsada); }
    }



    /* ================== Estado ================== */

    // bodega por aeropuerto
    private final Map<String, Integer> maxStorageByAirport = new HashMap<>();
    private final Map<String, Integer> inventoryByAirport  = new HashMap<>();

    // vuelos vivos y reservas
    private final Map<FlightKey, FlightInstance> flights = new HashMap<>();
    private final List<BookingRecord> inflight = new ArrayList<>();

    // último “now” procesado (para evitar retrocesos de reloj)
    private Instant lastAdvancedUtc = null;

    private LiveTEGState() {}

    /* ================== Inicialización ================== */

    /** Crea el estado con bodegas iniciales y sin vuelos (se registran on-demand). */
    public static LiveTEGState init(AeropuertosMap aeropuertos, VuelosMap catalogo) {
        LiveTEGState st = new LiveTEGState();
        for (String icao : aeropuertos.allIcaos()) {
            int cap = Math.max(0, aeropuertos.getCapBodega(icao));
            st.maxStorageByAirport.put(icao, cap);
            st.inventoryByAirport.put(icao, 0); // inventario inicial = 0
        }
        return st;
    }

    /* ================== Consultas ================== */

    public int getMaxStorage(String icao) {
        return maxStorageByAirport.getOrDefault(icao, 0);
    }

    public int getInventory(String icao) {
        return inventoryByAirport.getOrDefault(icao, 0);
    }

    public Map<String, Integer> snapshotInventory() {
        return new HashMap<>(inventoryByAirport);
    }

    /* ============ Registro/consulta de vuelos ============ */

    /**
     * Garantiza que la instancia de vuelo exista en el estado y devuelve la capacidad libre.
     * Útil desde el builder para “consultar” la cap remanente de una arista FLIGHT.
     */
    public int ensureFlightAndGetRemaining(FlightKey key, int capTotal) {
        FlightInstance inst = flights.computeIfAbsent(key, k -> new FlightInstance(k, capTotal));
        // Nota: asumimos consistencia de catálogo (capTotal no cambia entre tandas para la misma instancia).
        return inst.capLibre();
    }

    /**
     * (Opcional) Integra al estado vuelos provenientes de un TEG “catálogo” (sin reservas).
     * Útil si generas primero un TEG base y luego quieres que el estado “conozca” esas instancias.
     */
    public void mergeFromTEGCatalog(VuelosTEG teg) {
        for (VuelosEdge e : teg.edges()) {
            if (e.getType() != VuelosEdge.Type.FLIGHT) continue;
            var from = e.getFrom();
            var to   = e.getTo();
            FlightKey k = new FlightKey(from.getIcao(), to.getIcao(), from.getTimeUtc(), to.getTimeUtc());
            int capTotal = e.getCapacity();
            flights.computeIfAbsent(k, kk -> new FlightInstance(kk, capTotal));
        }
    }

    /* ================== Dinámica temporal ================== */

    /**
     * Avanza el reloj a {@code now}:
     * - Cristaliza entregas de bookings con arrUtc <= now (sumando a inventario con límite de bodega).
     * - Elimina de la lista “inflight” los bookings ya entregados.
     * Devuelve un registro de entregas para auditoría.
     */
    public List<DeliveryRecord> advanceClock(Instant now, int batchNo) {
        if (lastAdvancedUtc != null && (now.equals(lastAdvancedUtc) || now.isBefore(lastAdvancedUtc))) {
            return List.of();
        }
        lastAdvancedUtc = now;

        List<BookingRecord> toDeliver = inflight.stream()
                .filter(b -> !b.arrUtc.isAfter(now)) // arrUtc <= now
                .collect(Collectors.toList());
        if (toDeliver.isEmpty()) return List.of();

        List<DeliveryRecord> out = new ArrayList<>(toDeliver.size());
        for (BookingRecord b : toDeliver) {
            int capMax = getMaxStorage(b.destino);
            int cur    = getInventory(b.destino);
            int acept  = Math.min(b.cantidad, Math.max(0, capMax - cur)); // clamp a bodega
            if (acept > 0) {
                inventoryByAirport.put(b.destino, cur + acept);
            }
            FlightKey fk = new FlightKey(b.origen, b.destino, b.depUtc, b.arrUtc);
            out.add(new DeliveryRecord(batchNo, b.orderId, b.destino, b.arrUtc, acept, fk));
            // Si acept < cantidad significa overflow de bodega; decide tu estrategia si quieres reintentar.
        }

        inflight.removeAll(toDeliver);
        return out;
    }

    /**
     * Aplica bookings aprobados por la seed:
     * - Verifica y reserva capacidad en la instancia de vuelo.
     * - Registra la carga “en vuelo” para que aterrice al advanceClock().
     */
    public void applyBookings(List<BookingRecord> bookings) {
        for (BookingRecord b : bookings) {
            FlightKey fk = new FlightKey(b.origen, b.destino, b.depUtc, b.arrUtc);
            FlightInstance inst = flights.computeIfAbsent(fk, k -> new FlightInstance(k, /*capTotal=*/b.cantidad));
            int libre = inst.capLibre();
            if (libre < b.cantidad) {
                throw new IllegalStateException("Overbooking en vuelo " + fk + " libre=" + libre + " pedido=" + b.cantidad);
            }
            inst.capUsada += b.cantidad;
            inflight.add(b);
        }
    }

    /* ================== Construcción de TEG ================== */

    /**
     * Construye un TEG planificable en el horizonte [t0, t1], usando el estado vivo:
     * - Crea nodos (aeropuerto@instantes) para cada vuelo dentro del horizonte.
     * - Crea aristas FLIGHT con capacidad remanente.
     * - Crea aristas WAIT encadenando tiempos por aeropuerto con capacidad = bodega.
     * - Crea SUPPLY desde Ω a cada nodo de sedes (cap grande).
     *
     * Útil si tu VueloTEGBuilder decide delegar en el estado la construcción.
     * Si en cambio tu builder genera el TEG desde el catálogo y luego reescribe capacidades
     * consultando este estado (ensureFlightAndGetRemaining), no necesitas llamar a populateTEG().
     */
    public void populateTEG(VuelosTEG teg,
                            AeropuertosMap aMap,
                            Instant t0,
                            Instant t1,
                            Set<String> sedes) {

        // 1) Crear nodos y aristas de vuelo con capLibre
        Map<String, List<VuelosNode>> nodesByAirport = new HashMap<>();

        for (FlightInstance inst : flights.values()) {
            Instant dep = inst.key.depUtc;
            Instant arr = inst.key.arrUtc;
            if (dep.isBefore(t0) || arr.isAfter(t1)) continue; // fuera de horizonte

            int capLibre = inst.capLibre();
            if (capLibre <= 0) continue;

            int capBodegaFrom = getMaxStorage(inst.key.origen);
            int capBodegaTo   = getMaxStorage(inst.key.destino);

            VuelosNode nFrom = teg.addOrGetNode(inst.key.origen, dep, capBodegaFrom, /*Ω*/false);
            VuelosNode nTo   = teg.addOrGetNode(inst.key.destino, arr, capBodegaTo, /*Ω*/false);
            nodesByAirport.computeIfAbsent(nFrom.getIcao(), k -> new ArrayList<>()).add(nFrom);
            nodesByAirport.computeIfAbsent(nTo.getIcao(),   k -> new ArrayList<>()).add(nTo);

            teg.addEdge(new VuelosEdge(nFrom, nTo, VuelosEdge.Type.FLIGHT, capLibre, /*vuelo*/null));
        }

        // 2) WAIT: encadenar tiempos por aeropuerto con capacidad = bodega
        for (Map.Entry<String, List<VuelosNode>> entry : nodesByAirport.entrySet()) {
            String icao = entry.getKey();
            int capBodega = getMaxStorage(icao);

            // Ordena nodos por tiempo
            List<VuelosNode> lista = entry.getValue().stream()
                    .filter(n -> !n.isSuperSource())
                    .distinct()
                    .sorted(Comparator.comparing(VuelosNode::getTimeUtc))
                    .collect(Collectors.toList());

            for (int i = 0; i + 1 < lista.size(); i++) {
                VuelosNode a = lista.get(i);
                VuelosNode b = lista.get(i + 1);
                // WAIT entre tiempos consecutivos del mismo aeropuerto
                teg.addEdge(new VuelosEdge(a, b, VuelosEdge.Type.WAIT, capBodega, null));
            }
        }

        // 3) SUPPLY: desde Ω hacia cada nodo de sede (cap grande)
        VuelosNode omega = teg.addOrGetNode("Ω", null, Integer.MAX_VALUE, true);
        for (VuelosNode n : teg.nodes()) {
            if (n.isSuperSource()) continue;
            if (n.getTimeUtc() == null) continue;
            if (n.getTimeUtc().isBefore(t0) || n.getTimeUtc().isAfter(t1)) continue;
            if (sedes.contains(n.getIcao())) {
                teg.addEdge(new VuelosEdge(omega, n, VuelosEdge.Type.SUPPLY, 1_000_000, null));
            }
        }
    }
}
