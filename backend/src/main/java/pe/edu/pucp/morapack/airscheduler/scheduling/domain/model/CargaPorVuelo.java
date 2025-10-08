package pe.edu.pucp.morapack.airscheduler.scheduling.domain.model;


import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/** Lleva el control de capacidad y carga asignada por vuelo programado. */
@Getter
public class CargaPorVuelo {

    private Map<VueloProgramadoId, Integer> capacidad = new HashMap<>();
    private Map<VueloProgramadoId, Integer> asignado  = new HashMap<>();
    private Map<String, Map<String, Integer>> data = new HashMap<>();
    public void registrarCapacidad(VueloProgramadoId id, int cap) {
        capacidad.merge(id, cap, Integer::sum); // si el TEG duplica, sumamos (por seguridad)
    }

    public CargaPorVuelo() {
    }

    public CargaPorVuelo(CargaPorVuelo otra) {
        this.capacidad = new HashMap<>();
        for (Map.Entry<VueloProgramadoId, Integer> e : otra.capacidad.entrySet()) {
            this.capacidad.put(new VueloProgramadoId(e.getKey()), e.getValue());
        }

        this.asignado = new HashMap<>();
        for (Map.Entry<VueloProgramadoId, Integer> e : otra.asignado.entrySet()) {
            this.asignado.put(new VueloProgramadoId(e.getKey()), e.getValue());
        }

        this.data = new HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> e : otra.data.entrySet()) {
            this.data.put(e.getKey(), new HashMap<>(e.getValue())); // copia profunda
        }
    }

    public int capacidad(VueloProgramadoId id) {
        return capacidad.getOrDefault(id, 0);
    }

    public int asignado(VueloProgramadoId id) {
        return asignado.getOrDefault(id, 0);
    }

    public int residual(VueloProgramadoId id) {
        int capacidad = capacidad(id);
        int asignado = asignado(id);

        /*if (capacidad(id) > 0 || asignado > 0) {
            System.out.println("Es mayor a 0!");
        }*/

        return Math.max(0, capacidad(id) - asignado(id));
    }

    public void asignar(VueloProgramadoId id, int cantidad) {
        //if (cantidad <= 0) return;
        int nuevo = asignado(id) + cantidad;
        int capacidad = capacidad(id);

        if (nuevo > capacidad) {
            System.out.println("Nuevo: " + nuevo + ", cantidad: " + capacidad);
            throw new IllegalStateException("Se excede la capacidad del vuelo: " + id);
        }
        asignado.put(id, nuevo);
    }



    public Iterable<? extends Map.Entry<String, Map<String, Integer>>> entrySet() {
        return data.entrySet();
    }
    public void put(String vuelo, Map<String, Integer> carga) {
        data.put(vuelo, carga);
    }
}