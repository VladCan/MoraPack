package pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Vuelo;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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

    public Map<String, List<Integer>> getCanceladosMap() {
        return canceladosMap;
    }
    public void setCanceladosMap(Map<String, List<Integer>> mapa) {
        if (mapa != null) {
            canceladosMap.clear();
            canceladosMap.putAll(mapa);
        }
    }

public List<String> obtenerVuelosCancelados(Instant wStart, Instant wEnd) {
    if (wStart == null || wEnd == null) return List.of();

    ZoneId zone = ZoneOffset.UTC;
    DateTimeFormatter fFecha = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(zone);
    DateTimeFormatter fHora = DateTimeFormatter.ofPattern("HHmm").withZone(zone);

    List<String> resultado = new ArrayList<>();

    for (Map.Entry<String, List<Integer>> entry : canceladosMap.entrySet()) {
        String idVuelo = entry.getKey(); // Ejemplo: EBCI-OYSN-08:38
        List<Integer> dias = entry.getValue();

        // Separar origen, destino y hora
        String[] partes = idVuelo.split("-");
        if (partes.length < 3) continue;

        String origen = partes[0];
        String destino = partes[1];
        String horaStr = partes[2];

        LocalTime hora;
        try {
            hora = LocalTime.parse(horaStr);
        } catch (Exception e) {
            continue; // formato inválido
        }

        // Recorremos el rango de fechas de wStart a wEnd
        Instant cursor = wStart;
        while (!cursor.isAfter(wEnd)) {
            var fechaZ = cursor.atZone(zone);
            int diaActual = fechaZ.getDayOfMonth();

            if (dias.contains(diaActual)) {
                // Crear instante exacto del vuelo cancelado
                Instant salida = fechaZ
                        .withHour(hora.getHour())
                        .withMinute(hora.getMinute())
                        .withSecond(0)
                        .toInstant();

                String idInstancia = String.format("%s-%s-%s-%s",
                        origen,
                        destino,
                        fFecha.format(salida),
                        fHora.format(salida));

                resultado.add(idInstancia);
            }

            cursor = cursor.plus(1, ChronoUnit.DAYS);
        }
    }

    return resultado;
}

}

