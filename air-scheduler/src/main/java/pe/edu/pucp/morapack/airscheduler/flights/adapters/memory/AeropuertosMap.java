package pe.edu.pucp.morapack.airscheduler.flights.adapters.memory;

import java.io.File;
import java.util.*;

import pe.edu.pucp.morapack.airscheduler.flights.domain.model.Aeropuerto;

public class AeropuertosMap {
    private final Map<String, Aeropuerto> aeropuertos;
    public AeropuertosMap() {
        aeropuertos = new HashMap<>();
    }

    public void agregar(Aeropuerto ae, String key) {
        if (key == null || ae == null)
            return; // Evitar claves o aeropuertos nulos
        aeropuertos.put(key, ae);
    }

    public Aeropuerto obtener(String key) {
        return aeropuertos.get(key);
    }

    public boolean existe(String key) {
        return aeropuertos.containsKey(key);
    }

    public void leerDatos(Scanner sc) {
        while (sc.hasNextLine()) {
            Aeropuerto ae = new Aeropuerto();
            String key = ae.leer(sc); // key = origen
            if (key != null)
                agregar(ae, key);
        }
    }

    // Leer datos desde System.in
    public void leerDatos() {
        leerDatos(new Scanner(System.in));
    }

    // Leer datos desde archivo
    public void leerDatos(String nomArch) throws Exception {
        File file = new File(nomArch);
        try (Scanner sc = new Scanner(file)) {
            leerDatos(sc);
        }
    }

    public Collection<Aeropuerto> values() {
        return aeropuertos.values();
    }

    public Set<String> keys() {
        return aeropuertos.keySet();
    }

    public int size() {
        return aeropuertos.size();
    }

    public boolean contains(String code) {
        return aeropuertos.containsKey(code);
    }
    // ==== NUEVO ====
    public int getCapBodega(String icao) {
        Aeropuerto a = aeropuertos.get(icao);
        return (a != null) ? a.getCapacidad() : 0;
    }

    // ==== OPCIONAL (útil para inicializar inventarios) ====
    public Set<String> allIcaos() {
        return Collections.unmodifiableSet(aeropuertos.keySet());
    }

}
