// air-scheduler/src/main/java/pe/edu/pucp/morapack/airscheduler/scheduling/domain/service/SimulacionVentanas.java
package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.TEGEventBuilder;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosMap;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.TEGEventBuilderHelpers.TEGParametros;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.ArriboExogeno;
import pe.edu.pucp.morapack.airscheduler.orders.adapters.io.CargarPedidos;
import pe.edu.pucp.morapack.airscheduler.orders.domain.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.adapters.io.ImpresorSolucion;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.*;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.ssp.SSPGeneradorSeed;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Simulación por ventanas de Δt horas.
 * - NO elimina pedidos al activarlos: solo cuando queden completamente entregados.
 * - Mantiene tramos comprometidos en vuelo entre ventanas y procesa sus arribos cuando correspondan.
 */
public final class SimulacionVentanas {

    public record ResultadoGlobal(
            Map<Integer,Integer> entregadoPorPedido,
            Map<String, List<ArriboExogeno>> stockLibreAcumulado
    ) {}

    private final AeropuertosMap aeropuertosMap;
    private final VuelosMap vuelosMap;
    private final Set<String> sedes;
    private final long horasVentana;
    private final long horizonteTegHoras;
    private final Map<String, Integer> stockReservadoProx = new HashMap<>();
    private final Map<VueloProgramadoId, Integer> capReservadaProx = new HashMap<>();

    public Map<String, Integer> stockReservadoHastaProximoCorte() {
        return Map.copyOf(stockReservadoProx);
    }
    public Map<VueloProgramadoId, Integer> capacidadReservadaHastaProximoCorte() {
        return Map.copyOf(capReservadaProx);
    }
    // --- Estado persistente de la simulación ---
    /** Pedidos activos hasta que se completen (id -> estado). */
    private final Map<Integer, EstadoPedido> activos = new LinkedHashMap<>();
    /** Tramos comprometidos (despegaron antes de finVentana) que aún no han arribado. */
    private final List<TramoComprometido> enVuelo = new ArrayList<>();
    /** Arribos ya ocurridos (stock disponible) por aeropuerto. */
    private final Map<String, List<ArriboExogeno>> stockLibre = new HashMap<>();
    /** Entregado por pedido (para reporte final). */
    private final Map<Integer,Integer> entregadoPorPedido = new HashMap<>();

    public SimulacionVentanas(AeropuertosMap aeropuertosMap,
                              VuelosMap vuelosMap,
                              Set<String> sedes,
                              long horasVentana,
                              long horizonteTegHoras) {
        this.aeropuertosMap = aeropuertosMap;
        this.vuelosMap = vuelosMap;
        this.sedes = Set.copyOf(sedes);
        this.horasVentana = horasVentana;
        this.horizonteTegHoras = horizonteTegHoras;
    }

    
    /** Tramo comprometido (ya no se puede cambiar). */
    private record TramoComprometido(
            int pedidoId,
            VueloProgramadoId vueloId,
            int cantidad,
            String destinoFinal // del pedido (para saber si el arribo es final o intermedio)
    ) {}

    public ResultadoGlobal ejecutar(CargarPedidos pedidos, Instant relojInicial) {
        Instant reloj = relojInicial;

        while (true) {
            Instant finVentana = reloj.plus(horasVentana, ChronoUnit.HOURS);

            // 0) Procesar arribos de tramos previamente comprometidos con llegada <= finVentana
            procesarArribosEnVueloHasta(finVentana);

            // 1) “Activar” (NO eliminar) nuevos pedidos hasta finVentana
            var nuevos = pedidos.listarHasta(finVentana);    // ✅ solo lista, no remueve
            for (Pedido p : nuevos) {
                // Actívalos si aún no están activos (backlog)
                activos.putIfAbsent(p.getIdPedido(), new EstadoPedido(
                    p.getIdPedido(), p.getDestino(), p.getCreatedAtUtc(), cantidadPedido(p)
                ));
            }

            // ¿hay algo que planificar o algo en vuelo o aún hay pedidos por llegar?
            boolean hayActivosConRem = activos.values().stream().anyMatch(ep -> ep.remanenteParaPlan() > 0);
            if (!hayActivosConRem && enVuelo.isEmpty() && pedidos.isEmpty()) break;

            // 2) Construir TEG [finVentana, finVentana + horizonte]
            Instant inicioTeg = finVentana;
            Instant finTeg    = inicioTeg.plus(horizonteTegHoras, ChronoUnit.HOURS);

            var params = TEGParametros.builder()
                    .inicioUtc(inicioTeg)
                    .finUtc(finTeg)
                    .capacidadWaitPorDefecto(null)
                    .sedes(sedes)
                    .build();

            VuelosTEG teg = new TEGEventBuilder(aeropuertosMap, vuelosMap).construir(params);

            // 3) Preparar lista para SSP con remanentes (no sacamos pedidos no completos)
            List<Pedido> aPlanificar = new ArrayList<>();
            for (EstadoPedido ep : activos.values()) {
                int rem = ep.remanenteParaPlan();
                if (rem <= 0) continue;
                aPlanificar.add(wrapPedido(ep.getId(), ep.getDestino(), ep.getCreadoUtc(), rem));
            }

            // 4) Ejecutar SSP con stockLibre (arribos ya ocurridos)
            var ssp = new SSPGeneradorSeed(sedes, stockLibre);
            SolucionProgramacion seed = ssp.generarSeed(teg, aPlanificar, finVentana);

            // 5) Comprometer tramos con salida < finVentana y agregarlos a “enVuelo”
            for (PlanPedido plan : seed.getPlanPorPedido().values()) {
                EstadoPedido ep = activos.get(plan.getIdPedido());
                if (ep == null) continue;

                for (TramoAsignado t : plan.getTramos()) {
                    var id  = t.getVuelo();
                    var sal = id.getSalidaUtc();
                    var lle = id.getLlegadaUtc();
                    if (!sal.isBefore(finVentana)) continue; // aún no despega, puede replanificarse la próxima

                    // Reservar en vuelo
                    ep.setReservadoEnVuelo(ep.getReservadoEnVuelo() + t.getCantidad());
                    enVuelo.add(new TramoComprometido(ep.getId(), id, t.getCantidad(), ep.getDestino()));

                    // Si alcanza a llegar en esta ventana, procesarlo de inmediato
                    if (!lle.isAfter(finVentana)) {
                        if (id.getDestino().equals(ep.getDestino())) {
                            ep.setEntregado(ep.getEntregado() + t.getCantidad());
                            entregadoPorPedido.merge(ep.getId(), t.getCantidad(), Integer::sum);
                        } else {
                            stockLibre.computeIfAbsent(id.getDestino(), k -> new ArrayList<>())
                                      .add(new ArriboExogeno(lle, t.getCantidad()));
                        }
                        ep.setReservadoEnVuelo(ep.getReservadoEnVuelo() - t.getCantidad());
                        // también quitar el tramo de “enVuelo”
                    }
                }
            }

            // Limpiar de enVuelo los tramos que llegaron ≤ finVentana (si alguno fue procesado justo arriba)
            enVuelo.removeIf(tc -> !tc.vueloId().getLlegadaUtc().isAfter(finVentana));

            // 6) Eliminar SOLO pedidos completados (según tu requerimiento)
            activos.values().removeIf(EstadoPedido::completado);

            // 7) Mostrar / guardar (opcional)
            ImpresorSolucion.imprimirEnConsola(seed);
            ImpresorSolucion.guardarTodo(seed, finVentana, "seed");

            // 8) Avanzar reloj
            reloj = finVentana;
        }

        return new ResultadoGlobal(Map.copyOf(entregadoPorPedido), copiaInmutable(stockLibre));
    }

    // ---------------- utilitarios internos ----------------

    private void procesarArribosEnVueloHasta(Instant corte) {
        Iterator<TramoComprometido> it = enVuelo.iterator();
        while (it.hasNext()) {
            TramoComprometido tc = it.next();
            var id = tc.vueloId();
            if (id.getLlegadaUtc().isAfter(corte)) continue;

            EstadoPedido ep = activos.get(tc.pedidoId());
            if (ep != null) {
                if (id.getDestino().equals(tc.destinoFinal())) {
                    ep.setEntregado(ep.getEntregado() + tc.cantidad());
                    entregadoPorPedido.merge(ep.getId(), tc.cantidad(), Integer::sum);
                } else {
                    stockLibre.computeIfAbsent(id.getDestino(), k -> new ArrayList<>())
                              .add(new ArriboExogeno(id.getLlegadaUtc(), tc.cantidad()));
                }
                ep.setReservadoEnVuelo(ep.getReservadoEnVuelo() - tc.cantidad());
            }
            it.remove();
        }
    }

    private Pedido wrapPedido(int id, String destino, Instant creado, int cantidad) {
        // Wrapper simple compatible con SSP (getCantidad via reflexión si tu DTO no lo expone)
        return new Pedido() {
            @Override public int getIdPedido() { return id; }
            @Override public String  getDestino()  { return destino; }
            @Override public Instant getCreatedAtUtc() { return creado; }
            public int getCantidad() { return cantidad; }
        };
    }

    private Map<String, List<ArriboExogeno>> copiaInmutable(Map<String, List<ArriboExogeno>> m) {
        Map<String, List<ArriboExogeno>> out = new HashMap<>();
        for (var e : m.entrySet()) out.put(e.getKey(), List.copyOf(e.getValue()));
        return Map.copyOf(out);
    }

    private int cantidadPedido(Pedido p) {
        try {
            return (int) Pedido.class.getMethod("getCantidad").invoke(p);
        } catch (Exception ignore) {
            try {
                return (int) Pedido.class.getMethod("getCantPaquetes").invoke(p);
            } catch (Exception e2) {
                throw new IllegalStateException("Define campo cantidad en Pedido (getCantidad o getCantPaquetes).");
            }
        }
    }
    public void comprometerYProcesar(SolucionProgramacion plan, Instant presenteUtc) {
        // limpiar reservas calculadas en la iteración anterior
        stockReservadoProx.clear();
        capReservadaProx.clear();

        Instant proximoCorte = presenteUtc.plus(horasVentana, ChronoUnit.HOURS);

        for (PlanPedido pp : plan.getPlanPorPedido().values()) {
            EstadoPedido ep = activos.get(pp.getIdPedido());
            if (ep == null) continue;

            for (TramoAsignado t : pp.getTramos()) {
                var id  = t.getVuelo();
                var sal = id.getSalidaUtc();
                var lle = id.getLlegadaUtc();

                if (sal.isBefore(presenteUtc)) {
                    // 1) YA DESPEGA antes del presente: comprometer y, si llega ≤ presente, procesar arribo
                    ep.setReservadoEnVuelo(ep.getReservadoEnVuelo() + t.getCantidad());
                    enVuelo.add(new TramoComprometido(ep.getId(), id, t.getCantidad(), ep.getDestino()));

                    if (!lle.isAfter(presenteUtc)) {
                        if (id.getDestino().equals(ep.getDestino())) {
                            ep.setEntregado(ep.getEntregado() + t.getCantidad());
                            entregadoPorPedido.merge(ep.getId(), t.getCantidad(), Integer::sum);
                        } else {
                            stockLibre.computeIfAbsent(id.getDestino(), k -> new ArrayList<>())
                                    .add(new ArriboExogeno(lle, t.getCantidad()));
                        }
                        ep.setReservadoEnVuelo(ep.getReservadoEnVuelo() - t.getCantidad());
                        // quitar de enVuelo si corresponde
                        enVuelo.removeIf(tc -> tc.pedidoId()==ep.getId() && tc.vueloId().equals(id));
                    }
                } else if (!sal.isAfter(proximoCorte)) {
                    // 2) Sale entre (presente, próximo corte]: RESERVA para la siguiente ventana
                    capReservadaProx.merge(id, t.getCantidad(), Integer::sum);
                    stockReservadoProx.merge(id.getOrigen(), t.getCantidad(), Integer::sum);
                }
                // 3) Si sale después del próximo corte: no reservamos (se reoptimiza en la siguiente iteración)
            }
        }

        // limpiar tramos enVuelo que ya llegaron ≤ presente (por si alguno quedó)
        enVuelo.removeIf(tc -> !tc.vueloId().getLlegadaUtc().isAfter(presenteUtc));

        // eliminar solo pedidos completados
        activos.values().removeIf(EstadoPedido::completado);
    }
}
