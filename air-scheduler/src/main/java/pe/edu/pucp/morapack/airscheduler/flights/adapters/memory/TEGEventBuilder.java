package pe.edu.pucp.morapack.airscheduler.flights.adapters.memory;

import lombok.RequiredArgsConstructor;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

import pe.edu.pucp.morapack.airscheduler.flights.domain.model.Vuelo;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosEdge;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.AereopuertoNode;

@RequiredArgsConstructor
public final class TEGEventBuilder {

    private static final int CAP_INFINITA = Integer.MAX_VALUE / 2; // “infinito” seguro al sumar

    private final AeropuertosMap aeropuertosMap;
    private final VuelosMap vuelosMap;

    // Parámetros
    private Instant inicioUtc;
    private Instant finUtc;
    private int minConexionMin = 45;                 // p.ej. 45
    private Integer capacidadWaitPorDefecto = null;  // null => usar capacidad del aeropuerto
    private Set<String> sedes = Set.of();            // ICAOs sedes (Ω por sede)

    // Setters encadenables
    public TEGEventBuilder inicio(Instant inicio) { this.inicioUtc = inicio; return this; }
    public TEGEventBuilder fin(Instant fin) { this.finUtc = fin; return this; }
    public TEGEventBuilder minConexionMin(int m) { this.minConexionMin = m; return this; }
    public TEGEventBuilder capacidadWaitPorDefecto(Integer cap) { this.capacidadWaitPorDefecto = cap; return this; }
    public TEGEventBuilder sedes(Set<String> s) { this.sedes = s; return this; }

    /** Construye el TEG basado en eventos en [inicioUtc, finUtc]. */
    public VuelosTEG build() {
        validarVentana();

        VuelosTEG teg = new VuelosTEG();

        // 1) Recolectar eventos de salida/llegada (+conexión) por aeropuerto
        Map<String, NavigableSet<Instant>> eventosPorAP = recolectarEventos(inicioUtc, finUtc);

        // 2) Crear nodos de eventos por aeropuerto
        Map<String, List<AereopuertoNode>> nodosPorAP = crearNodosEventos(teg, eventosPorAP);

        // 3) SUPPLY infinito desde Ω hacia el primer evento de cada sede
        crearSupplyInfinito(teg, nodosPorAP);

        // 4) WAIT entre eventos consecutivos en cada aeropuerto
        crearWaits(teg, nodosPorAP);

        // 5) FLIGHT exactos (y asegurar evento de conexión mínima en destino)
        crearFlights(teg, eventosPorAP);

        return teg;
    }

    // ------------------ PASOS PRIVADOS (claros y compactos) ------------------

    private void validarVentana() {
        Objects.requireNonNull(inicioUtc, "inicioUtc");
        Objects.requireNonNull(finUtc, "finUtc");
        if (!inicioUtc.isBefore(finUtc))
            throw new IllegalArgumentException("inicioUtc >= finUtc");
    }

    /** Recolecta salidas/llegadas exactas y llegada+minConexion dentro de la ventana. */
    private Map<String, NavigableSet<Instant>> recolectarEventos(Instant inicio, Instant fin) {
        Map<String, NavigableSet<Instant>> eventos = new HashMap<>();

        for (String origen : vuelosMap.origenes()) {
            for (Vuelo v : vuelosMap.vuelosDesde(origen)) {
                for (Instant tSalida : instantesDiariosEnVentana(inicio, fin, v.getHoraGMTOrigen())) {
                    Instant tLlegada = combinarFechaYHora(tSalida, v.getHoraGMTDestino());
                    if (!tLlegada.isAfter(tSalida)) tLlegada = tLlegada.plus(1, ChronoUnit.DAYS);

                    addEvento(eventos, v.getOrigen(),  tSalida);
                    addEvento(eventos, v.getDestino(), tLlegada);
                    addEvento(eventos, v.getDestino(), tLlegada.plus(minConexionMin, ChronoUnit.MINUTES));
                }
            }
        }
        // Asegurar un evento inicial por sede para enganchar SUPPLY
        for (String sede : sedes) addEvento(eventos, sede, inicio);
        return eventos;
    }

    private void addEvento(Map<String, NavigableSet<Instant>> eventos, String ap, Instant t) {
        eventos.computeIfAbsent(ap, k -> new TreeSet<>()).add(t);
    }

    /** Crea nodos (aeropuerto@evento) y devuelve el índice por aeropuerto. */
    private Map<String, List<AereopuertoNode>> crearNodosEventos(
            VuelosTEG teg, Map<String, NavigableSet<Instant>> eventosPorAP) {

        Map<String, List<AereopuertoNode>> nodosPorAP = new HashMap<>();
        for (Map.Entry<String, NavigableSet<Instant>> e : eventosPorAP.entrySet()) {
            String ap = e.getKey();
            int capAP = aeropuertosMap.getCapBodega(ap);
            List<AereopuertoNode> lista = new ArrayList<>(e.getValue().size());
            for (Instant t : e.getValue()) {
                lista.add( teg.agregarONodo(ap, t, capAP, false) );
            }
            nodosPorAP.put(ap, lista);
        }
        return nodosPorAP;
    }

    /** SUPPLY infinito: Ω-sede → primer evento de la sede. */
    private void crearSupplyInfinito(VuelosTEG teg, Map<String, List<AereopuertoNode>> nodosPorAP) {
        for (String sede : sedes) {
            List<AereopuertoNode> eventosSede = nodosPorAP.getOrDefault(sede, List.of());
            if (eventosSede.isEmpty()) continue;
            AereopuertoNode omega = teg.agregarONodo("OMEGA-" + sede, null, 0, true);
            AereopuertoNode primerEvento = eventosSede.get(0);
            teg.agregarArco(new VuelosEdge(omega, primerEvento, VuelosEdge.Type.SUPPLY, CAP_INFINITA, null));
        }
    }

    /** Crea WAIT entre eventos consecutivos de cada aeropuerto. */
    private void crearWaits(VuelosTEG teg, Map<String, List<AereopuertoNode>> nodosPorAP) {
        for (Map.Entry<String, List<AereopuertoNode>> e : nodosPorAP.entrySet()) {
            String ap = e.getKey();
            List<AereopuertoNode> eventos = e.getValue();
            if (eventos.size() <= 1) continue;

            int capWait = capacidadWait(ap);
            for (int i = 0; i < eventos.size() - 1; i++) {
                AereopuertoNode a = eventos.get(i), b = eventos.get(i + 1);
                teg.agregarArco(new VuelosEdge(a, b, VuelosEdge.Type.WAIT, capWait, null));
            }
        }
    }

    /** Crea FLIGHT exactos y asegura llegada+conexión (nodo y WAIT). */
    private void crearFlights(VuelosTEG teg, Map<String, NavigableSet<Instant>> eventosPorAP) {
        for (String origen : vuelosMap.origenes()) {
            for (Vuelo v : vuelosMap.vuelosDesde(origen)) {
                for (Instant salida : instantesDiariosEnVentana(inicioUtc, finUtc, v.getHoraGMTOrigen())) {
                    Instant llegada = combinarFechaYHora(salida, v.getHoraGMTDestino());
                    if (!llegada.isAfter(salida)) llegada = llegada.plus(1, ChronoUnit.DAYS);

                    AereopuertoNode nSalida  = teg.agregarONodo(
                            v.getOrigen(), salida, aeropuertosMap.getCapBodega(v.getOrigen()), false);
                    AereopuertoNode nLlegada = teg.agregarONodo(
                            v.getDestino(), llegada, aeropuertosMap.getCapBodega(v.getDestino()), false);

                    // SOLO FLIGHT (sin addEvento ni WAIT manual):
                    teg.agregarArco(new VuelosEdge(nSalida, nLlegada, VuelosEdge.Type.FLIGHT, v.getCapacidad(), v));
                }
            }
        }
    }

    // ------------------ HELPERS ------------------

    private int capacidadWait(String ap) {
        return (capacidadWaitPorDefecto != null) ? capacidadWaitPorDefecto : aeropuertosMap.getCapBodega(ap);
    }

    /** Instantes diarios en [inicio, fin) con la hora (UTC) indicada por LocalTime. */
    private List<Instant> instantesDiariosEnVentana(Instant inicio, Instant fin, LocalTime horaGMT) {
        List<Instant> res = new ArrayList<>();
        ZonedDateTime z0  = inicio.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime zFn = fin.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);
        for (ZonedDateTime z = z0; !z.isAfter(zFn); z = z.plusDays(1)) {
            Instant t = z.withHour(horaGMT.getHour())
                         .withMinute(horaGMT.getMinute())
                         .withSecond(0).withNano(0)
                         .toInstant();
            if (!t.isBefore(inicio) && t.isBefore(fin)) res.add(t);
        }
        return res;
    }

    /** Combina la fecha de un Instant con una hora GMT (sin fecha). */
    private Instant combinarFechaYHora(Instant baseDia, LocalTime horaGMT) {
        ZonedDateTime z = baseDia.atZone(ZoneOffset.UTC);
        return z.withHour(horaGMT.getHour())
                .withMinute(horaGMT.getMinute())
                .withSecond(0).withNano(0)
                .toInstant();
    }
}
