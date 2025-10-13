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

public class OcupacionPorAeropuerto {
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

    private Integer ocupacion(String idAeropuerto, Instant t){
        if (t == null) throw new IllegalArgumentException("Null date");

        TreeMap<Instant, Integer> evs = eventosDe(idAeropuerto);
        TreeMap<Instant, Integer> cks = checkpointsDe(idAeropuerto);

        Instant dayStart = inicioDeDiaUTC(t);
        asegurarCheckpoint(idAeropuerto, dayStart);

        int ocupacion = cks.get(dayStart);

        for (var e : evs.subMap(dayStart, false, t, true).values()) {
            ocupacion += e;
        }
        return ocupacion;

    }

    public Integer maxReservable(String idAeropuerto, Instant inicio, Instant fin){
        validarIntervalo(inicio, fin);

        /*DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneOffset.UTC);
        Instant objetivo = Instant.parse("2025-10-10T00:54:00Z");
        if (idAeropuerto.equals("LOWW") && !inicio.isAfter(objetivo) && !inicio.isBefore(objetivo) && eventosDe(idAeropuerto).size() >= 18){
            System.out.println("Estamos en la fecha: " + formatter.format(inicio));
        }*/

        int capacidad = capacidadDe(idAeropuerto);
        if (capacidad <= 0) return 0; //Para asegurar como siempre.

        TreeMap<Instant, Integer> evs = eventosDe(idAeropuerto);

        //Obtenemos la ocupación justo antes de aplicar los eventos en "inicio"

        int occ = ocupacion(idAeropuerto, inicio);
        int pico = occ;

        for (var delta : evs.subMap(inicio, false, fin, true).values()) {
            occ += delta;
            if (occ > pico) pico = occ;
        }

        int holgura = capacidad - pico;

        return Math.max(0, holgura);
    }

    public void reservar(String idAeropuerto, Instant inicio, Instant fin, int q){
        validarIntervalo(inicio, fin);
        if (q <= 0) throw new IllegalArgumentException("q debe ser > 0");

        /*DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneOffset.UTC);
        Instant objetivo = Instant.parse("2025-10-10T02:00:00Z");
        if (idAeropuerto.equals("LOWW") && !inicio.isAfter(objetivo) && !inicio.isBefore(objetivo)){
            System.out.println("Estamos en la fecha: " + formatter.format(inicio));
        }*/

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

        // Actualiza checkpoints en medianoches dentro de [inicio, fin)
        actualizarCheckpointsEnRango(idAeropuerto, inicio, fin, +q);
    }

    //Esta está pensada para replanificación/cancelación:
    public void liberar(String idAeropuerto, Instant inicio, Instant fin, int q){
        //Tambien debería de hacer lo de Si el delta queda 0, se remueve.

        validarIntervalo(inicio, fin);
        if (q <= 0) throw new IllegalArgumentException("q debe ser > 0");

        TreeMap<Instant, Integer> evs = eventosDe(idAeropuerto);

        /*DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneOffset.UTC);
        Instant objetivo = Instant.parse("2025-10-09T00:54:00Z");
        if (idAeropuerto.equals("LOWW") && !inicio.isAfter(objetivo) && !inicio.isBefore(objetivo)){
            System.out.println("Estamos en la fecha: " + formatter.format(inicio));
        }*/


        // === Protección: para no permitir ocupación negativa en el intervalo ===
        // Ocupación justo antes de aplicar la liberación
        int occ = ocupacion(idAeropuerto, inicio);
        int minOcc = occ;
        //int minOcc = Integer.MAX_VALUE;;

        // Recorremos los deltas existentes en [inicio, fin) para ver el piso de ocupación
        for (int delta : evs.subMap(inicio, false, fin, false).values()) {
            occ += delta;
            if (occ < minOcc) minOcc = occ;
        }
        //if (minOcc == Integer.MAX_VALUE) minOcc = occ;

        // No podemos liberar más de la mínima ocupación del intervalo
        // Esto es más como un cinturón, obviamente solo se debería de liberar lo que hemos reservado. Por precaución
        // igual lo colocamos.
        int qEfectivo = Math.min(q, Math.max(0, minOcc));
        if (qEfectivo != q) System.out.println("qEfectivo != q: " + qEfectivo + " != " + q);
        if (qEfectivo == 0) {
            System.out.println("No podemos liberar de forma segura");
            // Nada que liberar de forma segura; salimos sin tocar eventos
            return;
        }

        // === Aplicamos deltas inversos a reservar ===
        // liberar = -q en inicio, +q en fin
        evs.merge(inicio, -qEfectivo, Integer::sum);
        evs.merge(fin,     +qEfectivo, Integer::sum);

        // Limpiamos deltas que queden en 0
        Integer dIni = evs.get(inicio);
        if (dIni != null && dIni == 0) evs.remove(inicio);
        Integer dFin = evs.get(fin);
        if (dFin != null && dFin == 0) evs.remove(fin);

        // Actualiza checkpoints en medianoches dentro de [inicio, fin) con signo contrario
        actualizarCheckpointsEnRango(idAeropuerto, inicio, fin, -qEfectivo);
    }

    private void actualizarCheckpointsEnRango(String idAeropuerto, Instant inicio, Instant fin, int q){
        if (!inicio.isBefore(fin)) return;

        var ck = checkpointsDe(idAeropuerto);

        /*DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneOffset.UTC);
        Instant objetivo = Instant.parse("2025-10-10T00:00:00Z");
        if (idAeropuerto.equals("LOWW") && !inicio.isAfter(objetivo) && !inicio.isBefore(objetivo)){
            System.out.println("Estamos en la fecha: " + formatter.format(inicio));
        }*/


        Instant dayStart = inicioDeDiaUTC(inicio);
        Instant midnight = inicio.equals(dayStart) ? dayStart : dayStart.plusSeconds(24 * 60 * 60);

        while (midnight.isBefore(fin)){
            asegurarCheckpoint(idAeropuerto, midnight);

            ck.merge(midnight, q, Integer::sum);
            Integer v = ck.get(midnight);
            if (v != null && v == 0) ck.remove(midnight);

            midnight = midnight.plusSeconds(24 * 60 * 60); // siguiente medianoche
        }


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
        for (var delta : ev.subMap(desde, false, dayStart, true).values()) {
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
        if (!a.isBefore(b)) System.out.println("Intervalo inválido: inicio: " + a + ", fin: " + b);
        if (!a.isBefore(b)) throw new IllegalArgumentException("intervalo inválido: inicio >= fin");
    }

    private int capacidadDe(String idAeropuerto) {
        Integer cap = aeropuertosMap.obtener(idAeropuerto).getCapacidad();
        if (cap <= 0) throw new IllegalStateException("Capacidad no configurada para aeropuerto: " + idAeropuerto);
        return cap;
    }


}
