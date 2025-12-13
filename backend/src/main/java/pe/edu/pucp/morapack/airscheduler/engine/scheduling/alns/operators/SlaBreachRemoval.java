package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import lombok.RequiredArgsConstructor;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Destructor enfocado en RF1 (SLA 46h).
 * Elimina los pedidos que llegan tarde o con muy poco margen, 
 * para que el reparador "TimeAttack" intente encontrarles una ruta más rápida.
 */
@RequiredArgsConstructor
public class SlaBreachRemoval extends BaseDestructor {

    private final int numeroABorrar;
    private final Random rnd = new Random();
    
    // RF1: 46 horas desde la creación
    private static final long SLA_HORAS = 46;

    @Override
    public void destroy(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {
        
        // 1. Filtrar y Ordenar candidatos por "Peor Margen de Tiempo"
        List<PlanPedido> candidatos = s.getPlanPorPedido().values().stream()
            // Solo nos interesan pedidos que tengan rutas asignadas (si no tiene ruta, no hay nada que borrar)
            .filter(p -> p.getRutas() != null && !p.getRutas().isEmpty())
            .sorted((p1, p2) -> {
                // Calculamos margen: (Deadline - Llegada).
                // Valores negativos significan retraso. Valores pequeños significan riesgo.
                long margen1 = calcularMargenMinutos(p1);
                long margen2 = calcularMargenMinutos(p2);
                
                // Orden ascendente: Los más negativos (o menores) van primero (son los peores)
                return Long.compare(margen1, margen2); 
            })
            // Tomamos un pool 3 veces mayor al necesario para dar variedad estocástica
            .limit(numeroABorrar * 3L) 
            .collect(Collectors.toList());

        if (candidatos.isEmpty()) return;

        // 2. Selección aleatoria dentro de los peores (para evitar ciclos deterministas)
        Collections.shuffle(candidatos, rnd);

        int borrados = 0;
        
        for (PlanPedido plan : candidatos) {
            if (borrados >= numeroABorrar) break;

            // Lista para conservar rutas que NO se pueden borrar (ej. el avión ya salió)
            List<RutaAsignada> rutasToKeep = new ArrayList<>();
            boolean huboCambio = false;

            // 3. Iteramos las rutas existentes (obtenidas como read-only)
            for (RutaAsignada ruta : plan.getRutas()) {
                
                // BLINDAJE: Si la ruta ya está en el aire (primer tramo ya salió), no la tocamos.
                if (ruta.getTramos() != null && !ruta.getTramos().isEmpty()) {
                    Instant salidaPrimerTramo = ruta.getTramos().get(0).getVuelo().getSalidaUtc();
                    if (!salidaPrimerTramo.isAfter(presenteUTC)) {
                        rutasToKeep.add(ruta); // Se queda
                        continue;
                    }
                }

                // Si no ha salido, la destruimos para liberar espacio
                liberarRuta(plan, ruta, journal, s);
                huboCambio = true;
            }

            // 4. Si liberamos algo, actualizamos el mapa con un NUEVO objeto PlanPedido
            if (huboCambio) {
                // Usamos el Builder para crear una versión limpia
                PlanPedido nuevoPlan = PlanPedido.builder()
                        .idPedido(plan.getIdPedido())
                        .aeropuertoDestino(plan.getAeropuertoDestino())
                        .creadoUtc(plan.getCreadoUtc())
                        .demanda(plan.getDemanda())
                        .rutas(rutasToKeep) // Solo las que estaban en vuelo
                        .build();

                // Reemplazamos en la solución
                s.getPlanPorPedido().put(nuevoPlan.getIdPedido(), nuevoPlan);
                borrados++;
            }
        }
    }

    /**
     * Calcula cuántos minutos sobran antes del deadline.
     * Retorna Long.MIN_VALUE si el pedido no llega a destino (caso crítico).
     */
    private long calcularMargenMinutos(PlanPedido p) {
        Instant llegada = p.ultimaLlegada();
        
        // Si no tiene fecha de llegada (rutas incompletas), es el peor caso posible.
        // Lo priorizamos para borrar y re-intentar.
        if (llegada == null) return Long.MIN_VALUE;

        Instant deadline = p.getCreadoUtc().plus(Duration.ofHours(SLA_HORAS));
        
        // Retorna diferencia en minutos. 
        // Negativo = Tarde (SLA roto). Positivo = A tiempo.
        return Duration.between(llegada, deadline).toMinutes();
    }
}