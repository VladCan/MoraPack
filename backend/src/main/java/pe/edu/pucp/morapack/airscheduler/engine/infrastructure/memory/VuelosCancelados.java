package pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Vuelo;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class VuelosCancelados {

    // Ahora usamos SET para búsqueda O(1)
    private final Map<String, Set<Integer>> canceladosMap = new HashMap<>();

    // Lee el archivo y llena el mapa
    public void leerDatos(Scanner sc) {
        while (sc.hasNextLine()) {
            String linea = sc.nextLine().trim();
            if (linea.isEmpty()) continue;

            try {
                // Ejemplo: 05.EBCI-OYSN-08:38
                String[] partes = linea.split("\\.");
                if (partes.length != 2) continue;

                int dia = Integer.parseInt(partes[0]);     // Día del mes
                String idVuelo = partes[1];                // "EBCI-OYSN-08:38"

                canceladosMap
                        .computeIfAbsent(idVuelo, k -> new HashSet<>())
                        .add(dia);

            } catch (Exception e) {
                System.err.println("Error procesando línea: " + linea);
            }
        }
    }

    public Set<Integer> diasCancelado(Vuelo vuelo) {
        if (vuelo == null) return Set.of();

        String idVuelo = String.format("%s-%s-%s",
                vuelo.getOrigen(),
                vuelo.getDestino(),
                vuelo.getHoraOrigen().toString());

        return canceladosMap.getOrDefault(idVuelo, Set.of());
    }

    public Map<String, Set<Integer>> getCanceladosMap() {
        return canceladosMap;
    }

    public void setCanceladosMap(Map<String, Set<Integer>> mapa) {
        if (mapa != null) {
            canceladosMap.clear();
            canceladosMap.putAll(mapa);
        }
    }

    // Tu método optimizado
    public List<VueloCancelado> obtenerVuelosCancelados(Instant wStart, Instant wEnd) {

        if (wStart == null || wEnd == null) return List.of();

        ZoneId zone = ZoneOffset.UTC;
        List<VueloCancelado> resultado = new ArrayList<>();

        for (Map.Entry<String, Set<Integer>> entry : canceladosMap.entrySet()) {

            String idVuelo = entry.getKey();  // Ejemplo: "EBCI-OYSN-08:38"
            Set<Integer> dias = entry.getValue();
            if (dias == null || dias.isEmpty())
                continue;

            String[] partes = idVuelo.split("-");
            if (partes.length < 3)
                continue;

            String origen = partes[0];
            String destino = partes[1];

            LocalTime hora;
            try {
                hora = LocalTime.parse(partes[2]);
            } catch (Exception e) {
                continue;
            }

            // 👇 ESTE ES EL MES REAL: lo obtenemos de la ventana.
            for (Instant cursor = wStart; !cursor.isAfter(wEnd); cursor = cursor.plus(1, ChronoUnit.DAYS)) {

                LocalDate fecha = cursor.atZone(zone).toLocalDate();

                // Verificamos si el día del mes coincide
                if (!dias.contains(fecha.getDayOfMonth()))
                    continue;

                // Construimos el instante real en ese mes/año
                Instant instante = fecha.atTime(hora).atZone(zone).toInstant();

                resultado.add(new VueloCancelado(
                        origen,
                        destino,
                        fecha,
                        hora,
                        instante
                ));
            }
        }

        return resultado;
    }

}
