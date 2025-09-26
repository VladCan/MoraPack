package pe.edu.pucp.morapack.airscheduler.flights.domain.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Aeropuerto {

    // Precompilamos el patrón para no recrearlo en cada llamada
    private static final Pattern LINE_PATTERN = Pattern.compile(
        "(\\d+)\\s+" +             // 1: ID
        "(\\w+)\\s+" +             // 2: Código
        "(.+?)\\s{2,}" +           // 3: Ciudad (hasta dos+ espacios)
        "(.+?)\\s+" +              // 4: País
        "(\\w+)\\s+" +             // 5: Abreviatura (ignorada)
        "([+-]?\\d+)\\s+" +        // 6: GMT
        "(\\d+)\\s+" +             // 7: Capacidad
        "Latitude:\\s+(.+?)\\s+" + // 8: Latitud
        "Longitude:\\s+(.+)"       // 9: Longitud
    );

    private int id;
    private String codigo;
    private String ciudad;
    private String pais;
    private int GMT;
    private int capacidad;
    private String latitud;
    private String longitud;
    private String continente;

    /** Lee y setea este objeto desde el scanner; devuelve el código (igual que antes) o null si falla. */
    public String leer(Scanner sc) {
        if (!sc.hasNextLine()) return null;
        String linea = sc.nextLine().trim();
        if (linea.isBlank() || !Character.isDigit(linea.charAt(0))) {
            return null; // ignorar cabeceras o líneas vacías
        }

        Matcher m = LINE_PATTERN.matcher(linea);
        if (!m.matches()) {
            return null; // no es formato válido, no ensuciar salida
        }

        this.id        = Integer.parseInt(m.group(1));
        this.codigo    = m.group(2);
        this.ciudad    = m.group(3);
        this.pais      = m.group(4);
        this.GMT       = Integer.parseInt(m.group(6));
        this.capacidad = Integer.parseInt(m.group(7));
        this.latitud   = m.group(8);
        this.longitud  = m.group(9);
        this.continente = inferContinente(this.id);

        return this.codigo;
    }


    private static String inferContinente(int id) {
        if (id >= 1  && id <= 10) return "AmericaSur";
        if (id >= 11 && id <= 20) return "Europa";
        if (id >= 21 && id <= 30) return "Asia";
        return null;
    }
}
