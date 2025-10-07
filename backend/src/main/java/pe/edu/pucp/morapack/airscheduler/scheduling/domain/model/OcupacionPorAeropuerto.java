package pe.edu.pucp.morapack.airscheduler.scheduling.domain.model;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.AeropuertosMap;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Esto es el Gestor global de ocupación aeroportuaria.
 * Mantiene las reservas por aeropuerto durante toda la simulación.
 * Se instancia una sola vez por ejecución (no es static o singleton, digamos que es similar).
 */

public class OcupacionPorAeropuerto {//TODO => LIMPIAR ESTO SOLO DEBE TENER LA INFO DEL PRESENTE
    private Map<String, TreeMap<Instant, Integer>> eventos = new HashMap<>();
    private Map<String, TreeMap<Instant, Integer>> checkpoints = new HashMap<>(); //Esto es para no tener que sumar los eventos de hace tiempo (me recuerda a SO)¿
    //Validar si la key es el IATA del aeropuerto
    private AeropuertosMap aeropuertosMap;

    public OcupacionPorAeropuerto(AeropuertosMap aeropuertosMap) {
        this.aeropuertosMap = aeropuertosMap;
    }

    ///
    /// Funciones principales: disponible, ocupacion, maxReservable, reservar, liberar.
    ///

    public Integer disponible(String idAeropuerto, Instant t) {
        return aeropuertosMap.obtener(idAeropuerto).getCapacidad() - ocupacion(idAeropuerto, t);
    }

    public Integer ocupacion(String idAeropuerto, Instant t){
        if (t == null) throw new IllegalArgumentException("Null date");

        TreeMap<Instant, Integer> evs = eventosDe(idAeropuerto);
        TreeMap<Instant, Integer> cks = checkpointsDe(idAeropuerto);

        Instant dayStart = inicioDeDiaUTC(t);
        asegurarCheckpoint(idAeropuerto, dayStart);

        int ocupacion = cks.get(dayStart);

        for (var e : evs.subMap(dayStart, false, t, false).values()) {
            ocupacion += e;
        }
        return ocupacion;

    }

    public Integer maxReservable(String idAeropuerto, Instant inicio, Instant fin){
        validarIntervalo(inicio, fin);

        int capacidad = capacidadDe(idAeropuerto);
        if (capacidad <= 0) return 0; //Para asegurar como siempre.

        TreeMap<Instant, Integer> evs = eventosDe(idAeropuerto);

        //Obtenemos la ocupación justo antes de aplicar los eventos en "inicio"

        int occ = ocupacion(idAeropuerto, inicio);
        int pico = occ;

        for (var delta : evs.subMap(inicio, true, fin, false).values()) {
            occ += delta;
            if (occ > pico) pico = occ;
        }

        int holgura = capacidad - pico;

        return Math.max(0, holgura);
    }

    public void reservar(String idAeropuerto, Instant inicio, Instant fin, int q){
        validarIntervalo(inicio, fin);
        if (q <= 0) throw new IllegalArgumentException("q debe ser > 0");


        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneOffset.UTC);

        /*Instant objetivo = Instant.parse("2025-10-07T03:23:00Z");
        if (!inicio.isAfter(objetivo) && !inicio.isBefore(objetivo)) {
            //System.out.println("Estamos en la fecha: " + formatter.format(inicio));
        };
        */

        int maxQ = maxReservable(idAeropuerto, inicio, fin);
        if (q > maxQ){ //Si queremos asignar más de lo que realmente se puede.
            System.out.println("Reserva excede holgura. aeropuerto=" + idAeropuerto +
                    " q=" + q + " > maxReservable=" + maxQ +
                    " en [" + inicio + ", " + fin + ")");
            return;
        }


        //if (idAeropuerto.equals("SKBO"))
            //System.out.println("Vamos a reservar " + q  + " desde " + formatter.format(inicio) + " hasta " + formatter.format(fin) + " porque maxReservable = " + maxQ);

        TreeMap<Instant, Integer> evs = eventosDe(idAeropuerto);
        evs.merge(inicio, q, Integer::sum);
        evs.merge(fin, -q, Integer::sum);

        //Si el delta queda en 0, se remueve.
        if (evs.get(inicio) != null && evs.get(inicio) == 0) evs.remove(inicio);
        if (evs.get(fin) != null && evs.get(fin) == 0) evs.remove(fin);

        invalidarCheckpoints(idAeropuerto, inicio, fin);
    }

    /** Borra checkpoints desde el día de 'inicio' hacia adelante
     *  (para que se reconstruyan la próxima vez con los eventos nuevos). */
    private void invalidarCheckpoints(String idAeropuerto, Instant inicio, Instant fin) {
        TreeMap<Instant, Integer> ck = checkpointsDe(idAeropuerto);
        if (ck.isEmpty()) return;

        Instant dIni = inicioDeDiaUTC(inicio);
        // opción conservadora: limpiar todos los futuros checkpoints
        ck.tailMap(dIni, true).clear();

        // si prefieres solo hasta el día de 'fin', usa:
        // Instant dFin = inicioDeDiaUTC(fin);
        // ck.subMap(dIni, true, dFin, true).clear();
    }

    //Esta está pensada para replanificación/cancelación:
    void liberar(String idAeropuerto, Instant inicio, Instant fin, int q){
        //Tambien debería de hacer lo de Si el delta queda 0, se remueve.
    }

    ///
    /// Funciones secundarias-helpers: disponible, ocupacion, maxReservable, reservar, liberar.
    ///

    private TreeMap<Instant, Integer> eventosDe(String id) {
        return eventos.computeIfAbsent(id, k -> new TreeMap<>());
    }

    private TreeMap<Instant, Integer> checkpointsDe(String id) {
        return checkpoints.computeIfAbsent(id, k -> new TreeMap<>());
    }

    private void asegurarCheckpoint(String id, Instant dayStart) {
        var ck = checkpointsDe(id);
        if (ck.containsKey(dayStart)) return;

        var ev = eventosDe(id);

        // Tomamos el checkpoint previo si existe; si no, partimos de 0
        Map.Entry<Instant, Integer> prevCk = ck.floorEntry(dayStart);
        int occ = (prevCk != null) ? prevCk.getValue() : 0;
        Instant desde = (prevCk != null) ? prevCk.getKey() : Instant.MIN; // si no hay, desde el principio

        // Sumamos eventos en (desde, dayStart)
        for (var delta : ev.subMap(desde, false, dayStart, false).values()) {
            occ += delta;
        }
        ck.put(dayStart, occ);
    }

    /** Inicio de día en UTC para el instante dado. */
    private static Instant inicioDeDiaUTC(Instant t) {
        return t.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private void validarIntervalo(Instant a, Instant b) {
        if (a == null || b == null) throw new IllegalArgumentException("inicio/fin no pueden ser null");
        if (!a.isBefore(b)) throw new IllegalArgumentException("intervalo inválido: inicio >= fin");
    }

    private int capacidadDe(String idAeropuerto) {
        Integer cap = aeropuertosMap.obtener(idAeropuerto).getCapacidad();
        if (cap <= 0) throw new IllegalStateException("Capacidad no configurada para aeropuerto: " + idAeropuerto);
        return cap;
    }


}
