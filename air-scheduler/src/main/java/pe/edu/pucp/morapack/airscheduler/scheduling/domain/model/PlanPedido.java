package pe.edu.pucp.morapack.airscheduler.scheduling.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Plan de un pedido dividido en múltiples rutas (cada ruta agrupa sus tramos y una cantidad). */
@Getter
@Builder
@AllArgsConstructor
public class PlanPedido {
    private final int idPedido;
    private final String aeropuertoDestino;
    private final Instant creadoUtc;
    private final int demanda;

    /** Conjunto de rutas por las que viajan “porciones” del pedido. */
    @Singular("ruta")
    private final List<RutaAsignada> rutas;

    public PlanPedido(PlanPedido otro) {
        this.idPedido = otro.getIdPedido();
        this.aeropuertoDestino = otro.getAeropuertoDestino();
        this.creadoUtc = otro.getCreadoUtc();
        this.demanda = otro.getDemanda();

        // Copia profunda de las rutas
        if (otro.getRutas() != null) {
            this.rutas = new ArrayList<>();
            for (RutaAsignada r : otro.getRutas()) {
                this.rutas.add(new RutaAsignada(r)); // usamos constructor copia de RutaAsignada
            }
        } else {
            this.rutas = new ArrayList<>();
        }
    }


    /** Cantidad total asignada (suma de cantidades de todas las rutas). */
    public int totalAsignado() {
        return (rutas == null) ? 0 : rutas.stream().mapToInt(RutaAsignada::getCantidad).sum();
    }



    /** ¿El pedido está completo? */
    public boolean estaCompleto() {
        return totalAsignado() >= demanda;
    }

    /** Primera llegada global entre TODAS las rutas. */
    public Instant primeraLlegada() {
        if (rutas == null || rutas.isEmpty()) return null;
        return rutas.stream()
                .map(RutaAsignada::primeraLlegada)
                .filter(java.util.Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(null);
    }

    /** Última llegada global entre TODAS las rutas. */
    public Instant ultimaLlegada() {
        if (rutas == null || rutas.isEmpty()) return null;
        return rutas.stream()
                .map(RutaAsignada::ultimaLlegada)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
    }

    /** SLA: la última llegada global debe estar dentro de creado + slaMax. */
    public boolean respetaSLA(Duration slaMax) {
        Instant fin = ultimaLlegada();
        return fin == null || !fin.isAfter(creadoUtc.plus(slaMax));
    }

    /** SLA con pickup: la última llegada debe ocurrir antes de (creado + (SLA - pickupTime)). */
    public boolean respetaSLAConPickup(Duration maxLlegadaDesdeCreacion) {
        Instant ult = ultimaLlegada();
        if (ult == null) return false;
        Instant limite = creadoUtc.plus(maxLlegadaDesdeCreacion);
        return !ult.isAfter(limite);
    }

    /** Acceso de solo lectura a rutas. */
    public List<RutaAsignada> getRutas() {
        return (rutas == null) ? List.of() : Collections.unmodifiableList(rutas);
    }

    /** Vista aplanada de tramos (compatibilidad con código previo que recorre “tramos”). */
    public List<TramoAsignado> getTramosAplanados() {
        if (rutas == null) return List.of();
        return rutas.stream().flatMap(r -> r.getTramos().stream()).toList();
    }

    public void limpiarTramos() {
        if (rutas != null) {
            rutas.forEach(r -> r.getTramos().clear());
        }
    }

}
