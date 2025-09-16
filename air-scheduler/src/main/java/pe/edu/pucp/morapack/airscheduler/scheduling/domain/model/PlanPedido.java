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
    String destinoIcao;
    Instant creadoUtc;
    int demanda;

    @Singular
    List<TramoAsignado> tramos; // cada tramo llega al aeropuerto destino

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
        return new ArrayList<>(tramos);
    }
}