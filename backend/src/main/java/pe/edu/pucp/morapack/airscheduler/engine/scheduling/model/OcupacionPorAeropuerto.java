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
 * Gestor global de ocupación aeroportuaria.
 * Mantiene las reservas por aeropuerto durante toda la simulación.
 */
public class OcupacionPorAeropuerto {
    
    private Map<String, TreeMap<Instant, Integer>> eventos = new HashMap<>();
    private Map<String, TreeMap<Instant, Integer>> checkpoints = new HashMap<>(); 
    private AeropuertosMap aeropuertosMap;

    public OcupacionPorAeropuerto(AeropuertosMap aeropuertosMap) {
        this.aeropuertosMap = aeropuertosMap;
    }

    // Constructor copia
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

    public OcupacionPorAeropuerto copiaProfunda() {
        return new OcupacionPorAeropuerto(this);
    }

    public void copiarDesde(OcupacionPorAeropuerto original) {
        this.eventos.clear();
        for (Map.Entry<String, TreeMap<Instant, Integer>> entry : original.eventos.entrySet()) {
            this.eventos.put(entry.getKey(), new TreeMap<>(entry.getValue()));
        }

        this.checkpoints.clear();
        for (Map.Entry<String, TreeMap<Instant, Integer>> entry : original.checkpoints.entrySet()) {
            this.checkpoints.put(entry.getKey(), new TreeMap<>(entry.getValue()));
        }
        this.aeropuertosMap = original.aeropuertosMap;
    }

    // ==========================================
    // MÉTODOS DE CONSULTA
    // ==========================================

    /**
     * ALIAS: Permite consultar la ocupación en un instante dado.
     * Requerido por operadores externos como WarehouseSmartRemoval.
     */
    public int consultar(String idAeropuerto, Instant t) {
        return ocupacion(idAeropuerto, t);
    }

    public Integer disponible(String idAeropuerto, Instant t) {
        return aeropuertosMap.obtener(idAeropuerto).getCapacidad() - ocupacion(idAeropuerto, t);
    }

    public Integer ocupacion(String idAeropuerto, Instant t){
        if (t == null) throw new IllegalArgumentException("Null date");

        TreeMap<Instant, Integer> evs = eventosDe(idAeropuerto);
        TreeMap<Instant, Integer> cks = checkpointsDe(idAeropuerto);

        Instant dayStart = inicioDeDiaUTC(t);
        int ocupacion = cks.getOrDefault(dayStart, 0);

        // Sumamos todos los eventos desde el inicio del día hasta el instante t
        for (var e : evs.subMap(dayStart, false, t, true).values()) {
            ocupacion += e;
        }
        return ocupacion;
    }

    public Integer maxReservable(String idAeropuerto, Instant inicio, Instant fin){
        validarIntervalo(inicio, fin);

        if (inicio.equals(fin)) {
            return Integer.MAX_VALUE; 
        }

        int capacidad = capacidadDe(idAeropuerto);
        if (capacidad <= 0) return 0; 

        TreeMap<Instant, Integer> evs = eventosDe(idAeropuerto);

        int occ = ocupacion(idAeropuerto, inicio);
        int pico = occ;

        for (var delta : evs.subMap(inicio, false, fin, false).values()) {
            occ += delta;
            if (occ > pico) pico = occ;
        }

        int holgura = capacidad - pico;
        return Math.max(0, holgura);
    }

    // ==========================================
    // MÉTODOS DE MODIFICACIÓN (RESERVAR/LIBERAR)
    // ==========================================

    public void reservar(String idAeropuerto, Instant inicio, Instant fin, int q){
        validarIntervalo(inicio, fin);
        if (q <= 0) throw new IllegalArgumentException("q debe ser > 0");
        if (inicio.equals(fin)) return;

        int maxQ = maxReservable(idAeropuerto, inicio, fin);
        
        // Nota: En ALNS relajado permitimos sobre-reservar para penalizar luego,
        // pero si quisieras restricción dura, descomenta esto:
        // if (q > maxQ) return; 

        TreeMap<Instant, Integer> evs = eventosDe(idAeropuerto);
        evs.merge(inicio, q, Integer::sum);
        evs.merge(fin, -q, Integer::sum);

        if (evs.get(inicio) != null && evs.get(inicio) == 0) evs.remove(inicio);
        if (evs.get(fin) != null && evs.get(fin) == 0) evs.remove(fin);

        actualizarCheckpointsEnRango(idAeropuerto, inicio, fin, +q);
    }

    public void liberar(String idAeropuerto, Instant inicio, Instant fin, int q){
        validarIntervalo(inicio, fin);
        if (q <= 0) throw new IllegalArgumentException("q debe ser > 0");
        if (inicio.equals(fin)) return;

        TreeMap<Instant, Integer> evs = eventosDe(idAeropuerto);

        // Protección contra stocks negativos:
        int occ = ocupacion(idAeropuerto, inicio);
        int minOcc = occ;

        for (int delta : evs.subMap(inicio, false, fin, false).values()) {
            occ += delta;
            if (occ < minOcc) minOcc = occ;
        }

        // Solo liberamos lo que realmente existe (protección física)
        int qEfectivo = Math.min(q, Math.max(0, minOcc));
        if (qEfectivo == 0) return;

        evs.merge(inicio, -qEfectivo, Integer::sum);
        evs.merge(fin,     +qEfectivo, Integer::sum);

        Integer dIni = evs.get(inicio);
        if (dIni != null && dIni == 0) evs.remove(inicio);
        Integer dFin = evs.get(fin);
        if (dFin != null && dFin == 0) evs.remove(fin);

        actualizarCheckpointsEnRango(idAeropuerto, inicio, fin, -qEfectivo);
    }

    private void actualizarCheckpointsEnRango(String idAeropuerto, Instant inicio, Instant fin, int q){
        if (q == 0) return;
        if (!inicio.isBefore(fin)) return;

        var ck = checkpointsDe(idAeropuerto);
        Instant dayStart = inicioDeDiaUTC(inicio);
        Instant midnight = inicio.equals(dayStart) ? dayStart : dayStart.plusSeconds(24 * 60 * 60);

        while (midnight.isBefore(fin)){
            Integer v = ck.merge(midnight, q, Integer::sum);
            if (v != null && v == 0) ck.remove(midnight);
            midnight = midnight.plusSeconds(24 * 60 * 60); 
        }
    }

    // ==========================================
    // HELPERS Y REPORTING
    // ==========================================

    public Map<String, TreeMap<Instant, Integer>> getEventos() { return eventos; }
    public Map<String, TreeMap<Instant, Integer>> getCheckpoints() { return checkpoints; }

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
                    intervalos.add(new IntervaloOcupacion(anterior, instante, acumulado));
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

    private TreeMap<Instant, Integer> eventosDe(String id) {
        return eventos.computeIfAbsent(id, k -> new TreeMap<>());
    }

    private TreeMap<Instant, Integer> checkpointsDe(String id) {
        return checkpoints.computeIfAbsent(id, k -> new TreeMap<>());
    }

    private static Instant inicioDeDiaUTC(Instant t) {
        return t.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private void validarIntervalo(Instant a, Instant b) {
        if (a == null || b == null) throw new IllegalArgumentException("inicio/fin no pueden ser null");
        if (a.isAfter(b)) throw new IllegalArgumentException("intervalo inválido: inicio > fin");
    }

    private int capacidadDe(String idAeropuerto) {
        Integer cap = aeropuertosMap.obtener(idAeropuerto).getCapacidad();
        if (cap <= 0) throw new IllegalStateException("Capacidad no configurada para aeropuerto: " + idAeropuerto);
        return cap;
    }

    /**
     * Verifica exceso global de capacidad o stock negativo.
     * Útil para penalizaciones en el ALNS.
     */
    public boolean hayExcesoDeCapacidad() {
        for (var entry : eventos.entrySet()) {
            String idAeropuerto = entry.getKey();
            
            // Ignorar sedes infinitas si fuera necesario, pero mejor manejarlo en el ALNS
            // if (SEDES_INFINITAS.contains(idAeropuerto)) continue;

            TreeMap<Instant, Integer> deltas = entry.getValue();
            if (deltas == null || deltas.isEmpty()) continue;

            int capacidad;
            try {
                capacidad = capacidadDe(idAeropuerto);
            } catch (Exception e) {
                capacidad = 0; 
            }

            int ocupacionActual = 0;
            for (Integer delta : deltas.values()) {
                ocupacionActual += delta;
                if (ocupacionActual > capacidad) return true;
                if (ocupacionActual < 0) return true;
            }
        }
        return false;
    }
    /**
     * Calcula la ocupación máxima histórica registrada para un aeropuerto
     * iterando sobre todos sus eventos cronológicos.
     */
    public int getMaxOcupacionGlobal(String idAeropuerto) {
        // 1. Obtener los eventos (deltas) del aeropuerto
        TreeMap<Instant, Integer> evs = eventos.get(idAeropuerto);
        
        // Si no hay eventos, la ocupación máxima es 0
        if (evs == null || evs.isEmpty()) {
            return 0;
        }

        int maxOcupacion = 0;
        int ocupacionActual = 0;

        // 2. Barrido (Sweep Line): Iteramos por los valores ordenados por tiempo
        for (int delta : evs.values()) {
            ocupacionActual += delta;
            
            // Actualizamos el pico máximo encontrado
            if (ocupacionActual > maxOcupacion) {
                maxOcupacion = ocupacionActual;
            }
        }

        return maxOcupacion;
    }
}