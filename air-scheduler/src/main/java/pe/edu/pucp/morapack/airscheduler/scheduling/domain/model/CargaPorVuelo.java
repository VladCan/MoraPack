package pe.edu.pucp.morapack.airscheduler.scheduling.domain.model;


import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/** Lleva el control de capacidad y carga asignada por vuelo programado. */
@Getter
public class CargaPorVuelo {

    private final Map<VueloProgramadoId, Integer> capacidad = new HashMap<>();
    private final Map<VueloProgramadoId, Integer> asignado  = new HashMap<>();

    public void registrarCapacidad(VueloProgramadoId id, int cap) {
        capacidad.merge(id, cap, Integer::sum); // si el TEG duplica, sumamos (por seguridad)
    }

    public int capacidad(VueloProgramadoId id) {
        return capacidad.getOrDefault(id, 0);
    }

    public int asignado(VueloProgramadoId id) {
        return asignado.getOrDefault(id, 0);
    }

    public int residual(VueloProgramadoId id) {
        return Math.max(0, capacidad(id) - asignado(id));
    }

    public void asignar(VueloProgramadoId id, int cantidad) {
        if (cantidad <= 0) return;
        int nuevo = asignado(id) + cantidad;
        if (nuevo > capacidad(id)) {
            throw new IllegalStateException("Se excede la capacidad del vuelo: " + id);
        }
        asignado.put(id, nuevo);
    }
}