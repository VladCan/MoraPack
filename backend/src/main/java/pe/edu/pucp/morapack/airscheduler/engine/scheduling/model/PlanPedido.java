package pe.edu.pucp.morapack.airscheduler.engine.scheduling.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter; // Importante
import lombok.Singular;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Plan de un pedido dividido en múltiples rutas (cada ruta agrupa sus tramos y una cantidad). */
@Getter
@Setter // Agregado para permitir modificaciones si fuera necesario
@Builder
@AllArgsConstructor
public class PlanPedido {
    private final int idPedido;
    private final String aeropuertoDestino;
    private final Instant creadoUtc;
    private final int demanda;

    /** Conjunto de rutas por las que viajan “porciones” del pedido. */
    @Singular("ruta")
    private List<RutaAsignada> rutas;

    public PlanPedido(PlanPedido otro) {
        this.idPedido = otro.getIdPedido();
        this.aeropuertoDestino = otro.getAeropuertoDestino();
        this.creadoUtc = otro.getCreadoUtc();
        this.demanda = otro.getDemanda();

        // Copia profunda y MUTABLE de las rutas
        if (otro.rutas != null) {
            this.rutas = new ArrayList<>();
            for (RutaAsignada r : otro.rutas) {
                this.rutas.add(new RutaAsignada(r)); 
            }
        } else {
            this.rutas = new ArrayList<>();
        }
    }

    /**
     * MÉTODO SEGURO: Garantiza que la lista sea mutable antes de intentar borrar.
     * Esto evita el error UnsupportedOperationException.
     */
    public void removerRutasInvalidas() {
        if (this.rutas == null) return;

        // Si la lista no es un ArrayList (ej. es SingletonList de Lombok), la convertimos
        if (!(this.rutas instanceof ArrayList)) {
            this.rutas = new ArrayList<>(this.rutas);
        }

        this.rutas.removeIf(r -> r.getCantidad() <= 0 || r.getTramos() == null || r.getTramos().isEmpty());
    }

    /** Cantidad total asignada (suma de cantidades de todas las rutas). */
    public int totalAsignado() {
        return (rutas == null) ? 0 : rutas.stream().mapToInt(RutaAsignada::getCantidad).sum();
    }

    /** Cuanto falta asignar en alguna ruta para dicho PlanPedido */
    public int demandaRestante() {
        return Math.max(0, demanda - totalAsignado());
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
            rutas.clear();
        } else {
            rutas = new ArrayList<>();
        }
    }
}