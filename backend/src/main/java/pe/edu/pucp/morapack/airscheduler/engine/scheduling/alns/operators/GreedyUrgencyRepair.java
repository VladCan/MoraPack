package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.service.IndexVuelos;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.service.VueloFicha;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CONSTRUCTOR "SEED-LIKE" (Sin Split)
 * Prioriza pedidos viejos. Busca rutas enteras priorizando menos escalas.
 * Valida estrictamente capacidad de vuelo y almacén.
 * Usa RouteTransaction para garantizar integridad transaccional.
 */
public class GreedyUrgencyRepair implements RepairOperator {

    private final List<String> sedes;
    private final IndexVuelos indexVuelos;

    // Reglas de Negocio
    private static final int MAX_HOPS = 3; 
    private static final long MIN_CONN_H = 1; // Mínimo 1h conexión
    private static final long MAX_CONN_H = 6; // Máximo 6h (RF6: No saturar almacén)
    private static final long SLA_HOURS = 46; // RF1
    private static final Set<String> HUBS = Set.of("SPIM", "EBCI", "UBBB"); // RF5

    public GreedyUrgencyRepair(List<String> sedes, VuelosTEG teg) {
        this.sedes = sedes;
        this.indexVuelos = new IndexVuelos(teg);
    }

    @Override
    public void repair(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {
        // 1. Identificar pedidos sin ruta válida (huérfanos o rotos)
        List<PlanPedido> huerfanos = s.getPlanPorPedido().values().stream()
                .filter(this::necesitaReparacion)
                .collect(Collectors.toList());

        if (huerfanos.isEmpty()) return;

        // 2. ORDENAR POR URGENCIA (Como el Seed)
        // Los más antiguos primero para asegurarles lugar en los vuelos críticos.
        huerfanos.sort(Comparator.comparing(PlanPedido::getCreadoUtc));

        for (PlanPedido plan : huerfanos) {
            repararPedidoCompleto(plan, s, journal, presenteUTC);
        }
    }

    private void repararPedidoCompleto(PlanPedido plan, SolucionProgramacion s, ALNS.Journal journal, Instant now) {
        int demanda = plan.getDemanda();
        String destino = plan.getAeropuertoDestino();
        
        // El paquete está disponible desde su creación o desde "ahora" si ya pasó tiempo.
        Instant refTime = plan.getCreadoUtc().isAfter(now) ? plan.getCreadoUtc() : now;

        // Estrategia Jerárquica del Seed: Intentar 0 hops, luego 1, luego 2...
        // Esto es mucho más eficiente que un Dijkstra global para redes pequeñas/medianas.
        for (String sede : sedes) {
            // Evitar rutas circulares tontas (Sede A -> Sede A)
            if (sede.equals(destino)) continue;

            for (int hops = 0; hops <= MAX_HOPS; hops++) {
                // Buscamos una ruta válida completa
                Ruta ruta = buscarRutaDFS(sede, destino, refTime, hops, s.getCargaPorVuelo(), journal, plan.getCreadoUtc(), demanda, new ArrayList<>());
                
                if (ruta != null) {
                    // ¡ÉXITO! Asignar y salir
                    // Usamos la transacción segura para garantizar integridad
                    asignarRuta(s, journal, plan, ruta, demanda);
                    return; // Pasamos al siguiente pedido
                }
            }
        }
    }

    // --- BÚSQUEDA DFS (Similar a tu Seed pero validando JOURNAL dinámico) ---
    private Ruta buscarRutaDFS(String actual, String destino, Instant earliestSalida, int hopsRestantes, 
                               CargaPorVuelo cargaGlobal, ALNS.Journal journal, Instant created, int demanda, List<VueloFicha> path) {
        
        // Caso base: Llegamos al destino
        if (actual.equals(destino)) {
            // Validar estancia final de 2h (RF2)
            if (!HUBS.contains(destino)) {
                Instant llegada = path.get(path.size()-1).id().getLlegadaUtc();
                // Verificamos si hay espacio para las 2 horas obligatorias
                if (journal.getOcc().maxReservable(destino, llegada, llegada.plus(Duration.ofHours(2))) < demanda) {
                    return null; // No cabe en destino final, ruta inválida
                }
            }
            return new Ruta(new ArrayList<>(path));
        }

        if (hopsRestantes < 0) return null;

        // CORRECCIÓN CRÍTICA AQUÍ: COPIAR LA LISTA
        List<VueloFicha> rawSalidas = indexVuelos.porOrigen(actual);
        if (rawSalidas == null || rawSalidas.isEmpty()) return null;

        // Creamos una copia nueva (ArrayList) para poder ordenarla sin afectar a otras iteraciones
        List<VueloFicha> salidas = new ArrayList<>(rawSalidas);

        // Ordenamos por salida temprana para ser Greedy (First Fit)
        salidas.sort(Comparator.comparing(v -> v.id().getSalidaUtc()));

        for (VueloFicha f : salidas) {
            // 1. Tiempos
            if (f.id().getSalidaUtc().isBefore(earliestSalida)) continue;
            
            // RF1: No violar SLA global
            if (Duration.between(created, f.id().getLlegadaUtc()).toHours() > SLA_HOURS) continue;

            // 2. Capacidad Vuelo (RF4)
            if (cargaGlobal.residual(f.id()) < demanda) continue;

            // 3. Validaciones de Conexión y Almacén (RF3 y RF6)
            if (!path.isEmpty()) {
                VueloFicha prev = path.get(path.size()-1);
                Instant llegadaPrev = prev.id().getLlegadaUtc();
                long esperaSegundos = Duration.between(llegadaPrev, f.id().getSalidaUtc()).getSeconds();

                // RF6: Ventana de conexión estricta (1h a 6h)
                if (esperaSegundos < MIN_CONN_H * 3600 || esperaSegundos > MAX_CONN_H * 3600) continue;

                // RF3: Espacio en almacén intermedio
                if (!HUBS.contains(actual)) {
                    if (journal.getOcc().maxReservable(actual, llegadaPrev, f.id().getSalidaUtc()) < demanda) {
                        continue; // No cabe en la escala
                    }
                }
            } else {
                // Primer tramo: Validar almacén origen si no es Hub
                if (!HUBS.contains(actual)) {
                    // Validamos desde creación hasta salida (o ventana corta si ya pasó tiempo)
                    Instant checkIni = created.isBefore(f.id().getSalidaUtc().minusSeconds(3600)) 
                            ? f.id().getSalidaUtc().minusSeconds(3600) : created;
                    
                    if (journal.getOcc().maxReservable(actual, checkIni, f.id().getSalidaUtc()) < demanda) {
                        continue;
                    }
                }
            }

            // Recursión
            path.add(f);
            Ruta r = buscarRutaDFS(f.id().getDestino(), destino, f.id().getLlegadaUtc(), hopsRestantes - 1, cargaGlobal, journal, created, demanda, path);
            if (r != null) return r; // Greedy: retornamos la primera válida que encontramos
            path.remove(path.size()-1);
        }

        return null;
    }

    private void asignarRuta(SolucionProgramacion s, ALNS.Journal journal, PlanPedido plan, Ruta r, int q) {
        // 1. Construimos los objetos de tramo (sin tocar el mapa todavía)
        List<TramoAsignado> tramos = new ArrayList<>();
        for (VueloFicha f : r.legs) {
            tramos.add(new TramoAsignado(f.id(), q, f.id().getLlegadaUtc()));
        }
        
        List<RutaAsignada> rutaFinal = List.of(new RutaAsignada(q, tramos));

        // 2. USAMOS LA TRANSACCIÓN CENTRALIZADA PARA RESERVAR (Multiplier +1)
        RouteTransaction.aplicarCambios(s, journal, plan, rutaFinal, 1);

        // 3. Guardar el plan
        PlanPedido nuevo = PlanPedido.builder()
                .idPedido(plan.getIdPedido())
                .aeropuertoDestino(plan.getAeropuertoDestino())
                .creadoUtc(plan.getCreadoUtc())
                .demanda(q)
                .rutas(rutaFinal)
                .build();
        
        s.getPlanPorPedido().put(plan.getIdPedido(), nuevo);
    }

    private boolean necesitaReparacion(PlanPedido p) {
        // Un pedido necesita reparación si no tiene rutas asignadas
        return p.getRutas() == null || p.getRutas().isEmpty();
    }

    static class Ruta {
        List<VueloFicha> legs;
        Ruta(List<VueloFicha> l) { legs = l; }
    }
}