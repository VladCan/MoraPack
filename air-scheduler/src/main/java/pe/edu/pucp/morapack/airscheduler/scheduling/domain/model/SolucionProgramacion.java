package pe.edu.pucp.morapack.airscheduler.scheduling.domain.model;


import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Solución completa: planes por pedido + carga agregada por vuelo. */
@Getter
@Builder
public class SolucionProgramacion {

    @Singular("plan")
    private final Map<Integer, PlanPedido> planPorPedido;

    private final CargaPorVuelo cargaPorVuelo;

    public SolucionProgramacion(Map<Integer, PlanPedido> planPorPedido, CargaPorVuelo cargaPorVuelo) {
        this.planPorPedido = Map.copyOf(planPorPedido); // hacemos copia inmutable
        this.cargaPorVuelo = cargaPorVuelo; // si es mutable, considerar copia defensiva
    }

    public SolucionProgramacion(SolucionProgramacion otra) {
        this.planPorPedido = Map.copyOf(otra.planPorPedido); // inmutable
        this.cargaPorVuelo = otra.cargaPorVuelo; // si es mutable, deberías copiar también
    }


    public PlanPedido planDe(int idPedido) {
        return planPorPedido.get(idPedido);
    }

    public boolean respetaCapacidadesVuelos() {
        return cargaPorVuelo.getAsignado().entrySet().stream()
                .allMatch(e -> e.getValue() <= cargaPorVuelo.capacidad(e.getKey()));
    }
    //@Deprecated
    //public boolean respetaVentana2hTodos(Duration ventana2h) {
    //    return planPorPedido.values().stream().allMatch(p -> p.respetaVentana2h(ventana2h));
    //}
    public boolean respetaSLAConPickupTodos(Duration pickupTime) {
        // 46h de llegada para dar 2h de recogida y cumplir SLA 48h
        Duration maxLlegada = Duration.ofHours(46);
        return planPorPedido.values().stream().allMatch(p -> p.respetaSLAConPickup(maxLlegada));
    }

    public boolean respetaSLA48hTodos() {
        return planPorPedido.values().stream().allMatch(p -> p.respetaSLA(Duration.ofHours(48)));
    }

    public Map<Integer, PlanPedido> asMap() {
        return Collections.unmodifiableMap(planPorPedido);
    }

    public List<Integer> pedidosIncompletos() {
        return planPorPedido.values().stream()
                .filter(p -> !p.estaCompleto())
                .map(PlanPedido::getIdPedido)
                .toList();
    }

    public void imprimir(Instant reloj, String titulo) {
        // Encabezado general
        System.out.println("=".repeat(90));
        System.out.println("[SOLUCIÓN] " + (titulo == null ? "" : titulo));
        System.out.println("Corte en (reloj): " + reloj);
        System.out.println("=".repeat(90));

        // Contenedores por bloque
        StringBuilder sbEntregados = new StringBuilder();
        StringBuilder sbParciales  = new StringBuilder();
        StringBuilder sbNoEntreg   = new StringBuilder();

        // Cabecera común
        String header = String.format("%-8s %-8s %10s %12s %12s %20s",
                "Pedido", "Destino", "Demanda", "Entregado", "Restante", "Última llegada≤reloj");
        String sep = "-".repeat(90);

        // Clasificar cada plan
        for (PlanPedido p : planPorPedido.values()) {
            int demanda = p.getDemanda();
            int entregado = entregadoHasta(p, reloj);
            int restante = Math.max(0, demanda - entregado);
            Instant ultimaLlegadaHastaReloj = ultimaLlegadaHasta(p, reloj);

            String fila = String.format("%-8d %-8s %10d %12d %12d %20s",
                    p.getIdPedido(),
                    p.getDestinoIcao(),
                    demanda,
                    entregado,
                    restante,
                    (ultimaLlegadaHastaReloj == null ? "-" : ultimaLlegadaHastaReloj.toString())
            );

            if (entregado >= demanda) {
                sbEntregados.append(fila).append('\n');
            } else if (entregado > 0) {
                sbParciales.append(fila).append('\n');
            } else {
                sbNoEntreg.append(fila).append('\n');
            }
        }

        // 1) BLOQUE: ENTREGADOS
        System.out.println(">> PEDIDOS ENTREGADOS (cumplen 100% hasta el reloj)");
        System.out.println(header);
        System.out.println(sep);
        System.out.print(sbEntregados.length() == 0 ? "(sin registros)\n" : sbEntregados.toString());
        System.out.println();

        // 2) BLOQUE: PARCIALMENTE ENTREGADOS
        System.out.println(">> PEDIDOS PARCIALMENTE ENTREGADOS (tienen llegadas pero no completan demanda)");
        System.out.println(header);
        System.out.println(sep);
        System.out.print(sbParciales.length() == 0 ? "(sin registros)\n" : sbParciales.toString());
        System.out.println();

        // 3) BLOQUE: NO ENTREGADOS
        System.out.println(">> PEDIDOS NO ENTREGADOS (0 unidades entregadas hasta el reloj)");
        System.out.println(header);
        System.out.println(sep);
        System.out.print(sbNoEntreg.length() == 0 ? "(sin registros)\n" : sbNoEntreg.toString());
        System.out.println("=".repeat(90));
    }

    /** Suma de cantidades de tramos cuya llegada es <= reloj. */
    private int entregadoHasta(PlanPedido plan, Instant reloj) {
        if (plan.getTramos() == null) return 0;
        return plan.getTramos().stream()
                .filter(t -> t.getLlegadaUtc() != null && !t.getLlegadaUtc().isAfter(reloj))
                .mapToInt(TramoAsignado::getCantidad)
                .sum();
    }

    /** Última llegada (máxima) <= reloj; null si no hay llegadas hasta el reloj. */
    private Instant ultimaLlegadaHasta(PlanPedido plan, Instant reloj) {
        if (plan.getTramos() == null) return null;
        return plan.getTramos().stream()
                .map(TramoAsignado::getLlegadaUtc)
                .filter(l -> l != null && !l.isAfter(reloj))
                .max(Instant::compareTo)
                .orElse(null);
    }
}
