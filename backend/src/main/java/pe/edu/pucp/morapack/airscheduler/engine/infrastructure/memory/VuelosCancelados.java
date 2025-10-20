package pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Vuelo;

import java.time.LocalTime;
import java.util.*;

public class VuelosCancelados {

    // Mapa: idVuelo -> lista de días en que fue cancelado
    private final Map<String, List<Integer>> canceladosMap = new HashMap<>();

    public void leerDatos(Scanner sc) {
        while (sc.hasNextLine()) {
            String linea = sc.nextLine().trim();
            if (linea.isEmpty()) continue; // Ignorar líneas vacías

            try {
                // Ejemplo: 05.EBCI-OYSN-08:38
                String[] partes = linea.split("\\.");
                if (partes.length != 2) continue;

                int dia = Integer.parseInt(partes[0]); // Día del mes
                String idVuelo = partes[1];            // "EBCI-OYSN-08:38"

                // Registrar día de cancelación
                canceladosMap
                        .computeIfAbsent(idVuelo, k -> new ArrayList<>())
                        .add(dia);

            } catch (Exception e) {
                System.err.println("Error procesando línea: " + linea + " → " + e.getMessage());
            }
        }
    }

    public List<Integer> diasCancelado(Vuelo vuelo) {
        if (vuelo == null) return List.of();

        String idVuelo = String.format("%s-%s-%s",
                vuelo.getOrigen(),
                vuelo.getDestino(),
                vuelo.getHoraOrigen().toString());

        List<Integer> dias = canceladosMap.get(idVuelo);

        return dias != null ? List.copyOf(dias) : List.of();
    }
}

