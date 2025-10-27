package pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory;

import lombok.RequiredArgsConstructor;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Vuelo;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public final class VuelosMap {

    private final Map<String, List<Vuelo>> vuelosPorOrigen = new HashMap<>();
    private final AeropuertosMap aeropuertosMap;

    /** Agrega un vuelo al map, llenando primero las horas GMT según el catálogo de aeropuertos. */
    public void agregar(Vuelo vue, String key) {
        if (key == null) return; // misma corta-circuito que tenías

        // Llenar GMT usando offsets del aeropuerto de origen/destino
        vue.llenarHoraGMT(
                aeropuertosMap.obtener(vue.getOrigen()).getGMT(),
                aeropuertosMap.obtener(vue.getDestino()).getGMT()
        );

        vuelosPorOrigen
                .computeIfAbsent(key, k -> new ArrayList<>())
                .add(vue);
    }

    /** Lee vuelos desde un Scanner (formato ORIGEN-DESTINO-HH:mm-HH:mm-capacidad). */
    public void leerDatos(Scanner sc) {
        for (int i = 1; sc.hasNextLine(); i++) {
            Vuelo vue = new Vuelo();
            String key = vue.leer(sc, i); // key = origen (puede ser null si la línea es inválida)
            agregar(vue, key);
        }
    }

    /** Lee datos desde System.in. */
    public void leerDatos() {
        leerDatos(new Scanner(System.in));
    }

    /** Lee datos desde archivo. */
    public void leerDatos(String nomArch) throws Exception {
        try (Scanner sc = new Scanner(new File(nomArch))) {
            leerDatos(sc);
        }
    }

    /** Devuelve el Map completo. */
    public Map<String, List<Vuelo>> getVuelosPorOrigen() {
        return vuelosPorOrigen;
    }

    /** Imprime todos los vuelos agrupados por origen. */
    public void imprimirVuelos() {
        vuelosPorOrigen.forEach((origen, lista) -> {
            System.out.println("Vuelos desde " + origen + ":");
            lista.forEach(System.out::println);
            System.out.println();
        });
    }

    /** Cantidad total de vuelos almacenados. */
    public int totalVuelos() {
        return vuelosPorOrigen.values().stream().mapToInt(List::size).sum();
    }

    /** Conjunto de orígenes disponibles. */
    public Set<String> origenes() {
        return vuelosPorOrigen.keySet();
    }

    /** Lista inmutable de vuelos desde un origen (vacía si no existe). */
    public List<Vuelo> vuelosDesde(String origen) {
        return vuelosPorOrigen.getOrDefault(origen, List.of());
    }

    /** Opcional: devuelve copia inmutable del map si no quieres que lo muten desde afuera. */
    public Map<String, List<Vuelo>> snapshotInmutable() {
        return vuelosPorOrigen.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> List.copyOf(e.getValue())
                ));
    }
}
