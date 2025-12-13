package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public class RouteTransaction {

    private static final Set<String> HUBS = Set.of("SPIM", "EBCI", "UBBB");
    private static final Duration PICKUP_FINAL = Duration.ofHours(2);

    /**
     * Ejecuta una operación atómica sobre los recursos (Vuelos y Almacenes).
     * @param multiplier 1 para RESERVAR (Constructor), -1 para LIBERAR (Destructor)
     */
    public static void aplicarCambios(SolucionProgramacion s, ALNS.Journal journal, 
                                      PlanPedido plan, List<RutaAsignada> rutas, int multiplier) {
        
        if (rutas == null || rutas.isEmpty()) return;

        for (RutaAsignada r : rutas) {
            int q = r.getCantidad();
            // Si q es positivo, el multiplier define el signo final.
            // (q * 1 = reserva), (q * -1 = liberación).
            int delta = q * multiplier; 

            if (q <= 0) continue;

            List<TramoAsignado> tramos = r.getTramos();
            if (tramos == null || tramos.isEmpty()) continue;

            for (int i = 0; i < tramos.size(); i++) {
                TramoAsignado t = tramos.get(i);
                VueloProgramadoId v = t.getVuelo();

                // 1. CAPACIDAD DE VUELO (RF4)
                s.getCargaPorVuelo().asignar(v, delta);

                // 2. ALMACÉN ORIGEN / CONEXIÓN (RF3)
                // Si no es Hub, ocupa espacio desde que llega (o se crea) hasta que sale.
                if (!HUBS.contains(v.getOrigen())) {
                    Instant inicioEspera = (i == 0) ? plan.getCreadoUtc() : tramos.get(i - 1).getVuelo().getLlegadaUtc();
                    Instant finEspera = v.getSalidaUtc();

                    // Validación de integridad temporal
                    if (inicioEspera != null && finEspera != null && finEspera.isAfter(inicioEspera)) {
                        // Aquí llamamos al journal con el signo correcto
                        operarJournal(journal, v.getOrigen(), inicioEspera, finEspera, delta);
                    }
                }

                // 3. ALMACÉN DESTINO FINAL (RF2)
                // Solo si es el último tramo y no es Hub.
                if (i == tramos.size() - 1 && !HUBS.contains(v.getDestino())) {
                    Instant llegada = v.getLlegadaUtc();
                    Instant salidaEstimada = llegada.plus(PICKUP_FINAL);
                    
                    operarJournal(journal, v.getDestino(), llegada, salidaEstimada, delta);
                }
            }
        }
    }

    // Helper privado para manejar la lógica de reservar/liberar en el Journal
    private static void operarJournal(ALNS.Journal journal, String ap, Instant ini, Instant fin, int delta) {
        if (delta > 0) {
            journal.reservar(ap, ini, fin, delta);
        } else {
            // Pasamos delta positivo a liberar, porque el método liberar espera "cuánto liberar"
            journal.liberar(ap, ini, fin, Math.abs(delta));
        }
    }
}