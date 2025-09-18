package pe.edu.pucp.morapack.airscheduler.scheduling.domain.model;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Plan consolidado de un pedido (posibles múltiples vuelos directos). */
@Value
@Builder
public class PlanPedido {
    int idPedido;
    String destinoIcao;//TODO: cambiar nombre a aereopuertoDestino aeropuerto destino
    Instant creadoUtc;
    int demanda;

    @Singular
    List<TramoAsignado> tramos; // cada tramo llega al aeropuerto destino
    //solución de un pedido

    public PlanPedido(int idPedido, String destinoIcao, Instant creadoUtc, int demanda, List<TramoAsignado> tramos) {
        this.idPedido = idPedido;
        this.destinoIcao = destinoIcao;
        this.creadoUtc = creadoUtc;
        this.demanda = demanda;
        //  siempre guardamos como lista mutable
        this.tramos = (tramos == null) ? new ArrayList<>() : new ArrayList<>(tramos);
    }

    public int totalAsignado() {
        return tramos.stream().mapToInt(TramoAsignado::getCantidad).sum();
    }

    public boolean estaCompleto() {
        return totalAsignado() >= demanda;
    }

    public Instant primeraLlegada() {
        return tramos.stream().map(TramoAsignado::getLlegadaUtc)
                .min(Instant::compareTo).orElse(null);
    }

    public Instant ultimaLlegada() {
        return tramos.stream().map(TramoAsignado::getLlegadaUtc)
                .max(Instant::compareTo).orElse(null);
    }

    public boolean respetaVentana2h(Duration ventana2h) {
        if (tramos.isEmpty()) return true; // nada asignado aún
        var a = primeraLlegada();
        var b = ultimaLlegada();
        return a != null && b != null && !b.isAfter(a.plus(ventana2h));
    }

    public boolean respetaSLA(Duration slaMax) {
        var fin = ultimaLlegada();
        return fin == null || !fin.isAfter(creadoUtc.plus(slaMax));
    }

    public List<TramoAsignado> getTramosMutable() {
        return tramos;
    }

    public void limpiarTramos() {
        tramos.clear();
    }


    public boolean respetaSLAConPickup(Duration maxLlegadaDesdeCreacion) {
        Instant ult = ultimaLlegada();
        if (ult == null) return false; // no hay llegadas => no cumple
        Instant limite = creadoUtc.plus(maxLlegadaDesdeCreacion);
        return !ult.isAfter(limite);
    }
}