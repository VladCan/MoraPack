package pe.edu.pucp.morapack.airscheduler.engine.scheduling.model;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.AeropuertosMap;

/**
 * Esto es el Gestor global de ocupación aeroportuaria.
 * Mantiene las reservas por aeropuerto durante toda la simulación.
 * Se instancia una sola vez por ejecución (no es static o singleton, digamos que es similar).
 */

/// PRECAUCIÓN ANTES DE HACERLO GLOBAL O FINAL, SE USA UNA INSTANCIA NEW EN EL REGRET REPAIR
public class OcupacionPorAeropuerto {
    private Map<String, TreeMap<Instant, Integer>> eventos = new HashMap<>();
    private Map<String, TreeMap<Instant, Integer>> checkpoints = new HashMap<>(); //Esto es para no tener que sumar los eventos de hace tiempo (me recuerda a SO)¿
    //Validar si la key es el IATA del aeropuerto
    private AeropuertosMap aeropuertosMap;

    public OcupacionPorAeropuerto(AeropuertosMap aeropuertosMap) {
        this.aeropuertosMap = aeropuertosMap;
    }

    /// LOS GETTERS SOLO SE USAN PARA PROBAR EL JOURNAL EN ALNS (VER QUE EL ROLLBACK FUNCIONA)
    public Map<String, TreeMap<Instant, Integer>> getEventos() {
        return eventos;
    }

    public Map<String, TreeMap<Instant, Integer>> getCheckpoints() {
        return checkpoints;
    }

    //Constructor copia
    public OcupacionPorAeropuerto(OcupacionPorAeropuerto original){
        this.eventos = new HashMap<>();
        for (Map.Entry<String, TreeMap<Instant, Integer>> entry : original.eventos.entrySet()) {
            this.eventos.put(entry.getKey(), new TreeMap<>(entry.getValue()));
        }

        this.checkpoints = new HashMap<>();
        for (Map.Entry<String, TreeMap<Instant, Integer>> entry : original.checkpoints.entrySet()) {
            this.checkpoints.put(entry.getKey(), new TreeMap<>(entry.getValue()));
        }

        this.aeropuertosMap = original.aeropuertosMap;
    }

    /// 2 Métodos para no usar Journal en ALNS:

    public OcupacionPorAeropuerto copiaProfunda() {
        return new OcupacionPorAeropuerto(this);
    }

    public void copiarDesde(OcupacionPorAeropuerto original) {
        // Copiar eventos
        this.eventos.clear();
        for (Map.Entry<String, TreeMap<Instant, Integer>> entry : original.eventos.entrySet()) {
            this.eventos.put(entry.getKey(), new TreeMap<>(entry.getValue()));
        }

        // Copiar checkpoints
        this.checkpoints.clear();
        for (Map.Entry<String, TreeMap<Instant, Integer>> entry : original.checkpoints.entrySet()) {
            this.checkpoints.put(entry.getKey(), new TreeMap<>(entry.getValue()));
        }

        // El aeropuertosMap debería ser el mismo "tipo" siempre.
        // Si quieres ser explícito:
        this.aeropuertosMap = original.aeropuertosMap;
    }


    ///
    /// Funciones principales: disponible, ocupacion, maxReservable, reservar, liberar.
    ///

    public Integer disponible(String idAeropuerto, Instant t) {
        return aeropuertosMap.obtener(idAeropuerto).getCapacidad() - ocupacion(idAeropuerto, t);
    }

    /// POR AHORA USAR SOLO DENTRO DE LA CLASE (TOINCLUSIVE TRUE)
    public Integer ocupacion(String idAeropuerto, Instant t){
        if (t == null) throw new IllegalArgumentException("Null date");

        TreeMap<Instant, Integer> evs = eventosDe(idAeropuerto);
        TreeMap<Instant, Integer> cks = checkpointsDe(idAeropuerto);

        Instant dayStart = inicioDeDiaUTC(t);
        //asegurarCheckpoint(idAeropuerto, dayStart);

        /// GetOrDefault
        int ocupacion = cks.getOrDefault(dayStart, 0);

        for (var e : evs.subMap(dayStart, false, t, true).values()) {
            ocupacion += e;
        }
        return ocupacion;

    }

    public Integer maxReservable(String idAeropuerto, Instant inicio, Instant fin){
        validarIntervalo(inicio, fin);

        if (inicio.equals(fin)) {
            // No hay tiempo de estancia ⇒ no se necesita holgura.
            // Puedes devolver Integer.MAX_VALUE o la capacidad del almacén.
            //System.out.println("Inicio y fin iguales.");
            return Integer.MAX_VALUE; // preferible para no bloquear conexiones “pegadas”
        }

        int capacidad = capacidadDe(idAeropuerto);
        if (capacidad <= 0) return 0; //Para asegurar como siempre.

        TreeMap<Instant, Integer> evs = eventosDe(idAeropuerto);

        //Obtenemos la ocupación justo antes de aplicar los eventos en "inicio"

        int occ = ocupacion(idAeropuerto, inicio);
        int pico = occ;

        for (var delta : evs.subMap(inicio, false, fin, false).values()) {
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
        Instant objetivo = Instant.parse("2025-10-10T06:18:00Z");
        if (idAeropuerto.equals("LOWW") && !inicio.isAfter(objetivo) && !inicio.isBefore(objetivo)){
            System.out.println("Estamos en la fecha: " + formatter.format(inicio));
        }

        if (inicio.equals(fin)){
            //System.out.println("Inicio y fin iguales.");
            return;
        }

        int maxQ = maxReservable(idAeropuerto, inicio, fin);
        if (q > maxQ){ //Si queremos asignar más de lo que realmente se puede.

            System.out.println("⚠\uFE0FReserva excede holgura. aeropuerto=" + idAeropuerto +
                    " q=" + q + " > maxReservable=" + maxQ +
                    " en [" + inicio + ", " + fin + ") de "+ idAeropuerto);
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

        if (inicio.equals(fin)){
            //System.out.println("Inicio y fin iguales.");
            return;
        }

        TreeMap<Instant, Integer> evs = eventosDe(idAeropuerto);

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
        //if (qEfectivo != q) System.out.println("qEfectivo != q: " + qEfectivo + " != " + q);
        if (qEfectivo == 0) {
            System.out.println("⚠\uFE0FNo podemos liberar de forma segura de" + idAeropuerto);
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

        if (q == 0) {
            System.out.println("Estamos pasando un q=0 en actualizarCheckpointsEnRango");
            return;
        }
        if (!inicio.isBefore(fin)) return;

        var ck = checkpointsDe(idAeropuerto);

        Instant dayStart = inicioDeDiaUTC(inicio);
        Instant midnight = inicio.equals(dayStart) ? dayStart : dayStart.plusSeconds(24 * 60 * 60);

        while (midnight.isBefore(fin)){
            //Merge y recuperamos el valor
            Integer v = ck.merge(midnight, q, Integer::sum);
            if (v < 0) System.out.println("Resultado del merge < 0");
            if (v != null && v == 0) ck.remove(midnight);

            midnight = midnight.plusSeconds(24 * 60 * 60); // siguiente medianoche
        }
    }

    /**
     * Expone reservas activas por aeropuerto, útil para depuración o reporting.
     */
    public Map<String, List<IntervaloOcupacion>> snapshotActual() {
        Map<String, List<IntervaloOcupacion>> resultado = new HashMap<>();
        for (var entry : eventos.entrySet()) {
            String aeropuerto = entry.getKey();
            TreeMap<Instant, Integer> deltas = entry.getValue();
            if (deltas == null || deltas.isEmpty()) continue;

            int acumulado = 0;
            Instant anterior = null;
            List<IntervaloOcupacion> intervalos = new ArrayList<>();

            for (var punto : deltas.entrySet()) {
                Instant instante = punto.getKey();
                int delta = punto.getValue();

                if (anterior != null && acumulado > 0 && anterior.isBefore(instante)) {
                    IntervaloOcupacion intervalo = new IntervaloOcupacion(anterior, instante, acumulado);
                    intervalos.add(intervalo);
                }

                acumulado += delta;
                if (acumulado < 0) acumulado = 0;
                anterior = instante;
            }

            if (!intervalos.isEmpty()) {
                resultado.put(aeropuerto, intervalos);
            }
        }
        return resultado;
    }

    public record IntervaloOcupacion(Instant inicio, Instant fin, int cantidad) {}

    ///
    /// Funciones secundarias-helpers: disponible, ocupacion, maxReservable, reservar, liberar.
    ///

    private TreeMap<Instant, Integer> eventosDe(String id) {
        return eventos.computeIfAbsent(id, k -> new TreeMap<>());
    }

    private TreeMap<Instant, Integer> checkpointsDe(String id) {
        return checkpoints.computeIfAbsent(id, k -> new TreeMap<>());
    }

    /** Inicio de día en UTC para el instante dado. */
    private static Instant inicioDeDiaUTC(Instant t) {
        return t.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private void validarIntervalo(Instant a, Instant b) {
        if (a == null || b == null) throw new IllegalArgumentException("inicio/fin no pueden ser null");
        if (a.isAfter(b)) throw new IllegalArgumentException("intervalo inválido: inicio > fin");
        //if (!a.isBefore(b)) System.out.println("Intervalo inválido: inicio: " + a + ", fin: " + b);
        //if (!a.isBefore(b)) throw new IllegalArgumentException("intervalo inválido: inicio >= fin");
    }

    private int capacidadDe(String idAeropuerto) {
        Integer cap = aeropuertosMap.obtener(idAeropuerto).getCapacidad();
        if (cap <= 0) throw new IllegalStateException("Capacidad no configurada para aeropuerto: " + idAeropuerto);
        return cap;
    }

    /**
     * Limpia eventos futuros desde el instante dado (inclusive).
     * Preserva eventos históricos (pasados) y checkpoints.
     * Útil para replanificación: elimina eventos de ventanas anteriores que ya no son válidos.
     * 
     * IMPORTANTE: NO recalcula checkpoints automáticamente porque puede causar inconsistencias.
     * Los checkpoints se mantienen como están y se actualizarán cuando se agreguen nuevos eventos.
     */
    public void limpiarEventosFuturosDesde(Instant desde) {
        if (desde == null) return;
        
        // Limpiar eventos futuros para cada aeropuerto
        for (Map.Entry<String, TreeMap<Instant, Integer>> entry : eventos.entrySet()) {
            TreeMap<Instant, Integer> eventosAeropuerto = entry.getValue();
            if (eventosAeropuerto == null || eventosAeropuerto.isEmpty()) continue;
            
            // Obtener eventos futuros (desde 'desde' inclusive hacia adelante)
            var eventosFuturos = eventosAeropuerto.tailMap(desde, true);
            if (!eventosFuturos.isEmpty()) {
                // Crear una copia de las claves para evitar ConcurrentModificationException
                List<Instant> instantesAEliminar = new ArrayList<>(eventosFuturos.keySet());
                for (Instant instante : instantesAEliminar) {
                    eventosAeropuerto.remove(instante);
                }
            }
        }
        
        // NO recalcular checkpoints - se preservan para mantener la ocupación histórica
        // Los checkpoints representan la ocupación acumulada al inicio de cada día
        // y deben mantenerse intactos para preservar la información histórica
    }

}
