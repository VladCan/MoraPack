package pe.edu.pucp.morapack.airscheduler.engine.flights.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.Locale;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Aeropuerto {

    // Patron más tolerante a columnas separadas por 2+ espacios
    // y a lat/lon en DMS o decimal.
    private static final Pattern LINE_PATTERN = Pattern.compile(
        "^\\s*(\\d+)\\s+" +            // 1: ID
        "(\\w+)\\s+" +                 // 2: Código
        "(.+?)\\s{2,}" +               // 3: Ciudad (hasta 2+ espacios)
        "(.+?)\\s{2,}" +               // 4: País   (hasta 2+ espacios)
        "(\\w+)\\s+" +                 // 5: Abreviatura (ignorada)
        "([+-]?\\d+)\\s+" +            // 6: GMT
        "(\\d+)\\s+" +                 // 7: Capacidad
        "Latitude:\\s+(.+?)\\s+" +     // 8: Latitud (DMS/decimal con espacios)
        "Longitude:\\s+(.+?)\\s*$"     // 9: Longitud (DMS/decimal con espacios)
    );

    private int id;
    private String codigo;
    private String ciudad;
    private String pais;
    private int GMT;
    private int capacidad;
    /** Guardaremos en DECIMAL como texto, e.g., "-12.02139" */
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
            return null; // formato no válido
        }

        this.id        = Integer.parseInt(m.group(1));
        this.codigo    = m.group(2);
        this.ciudad    = m.group(3).trim();
        this.pais      = m.group(4).trim();
        this.GMT       = Integer.parseInt(m.group(6));
        this.capacidad = Integer.parseInt(m.group(7));

        // Parsear coordenadas -> decimal
        String latRaw = m.group(8);
        String lonRaw = m.group(9);
        Double latDec = parseCoordinateToDecimal(latRaw, true);
        Double lonDec = parseCoordinateToDecimal(lonRaw, false);

        // Si no se pudieron parsear, no ensuciamos el objeto
        if (latDec == null || lonDec == null) {
            return null;
        }
        this.latitud   = String.format(Locale.US, "%.6f", latDec);
        this.longitud  = String.format(Locale.US, "%.6f", lonDec);

        this.continente = inferContinente(this.id);

        return this.codigo;
    }

    /**
     * Convierte una coordenada en DMS o decimal a double (grados decimales).
     * Acepta formatos como:
     *  - 04° 42' 05" N
     *  - 74° 08' 49" W
     *  - -12.0219
     *  - 12,0219 S
     */
    private static Double parseCoordinateToDecimal(String raw, boolean isLat) {
        if (raw == null) return null;
        String s = raw.trim()
                      .toUpperCase(Locale.ROOT)
                      .replace(',', '.'); // por si vienen comas

        // Detectar hemisferio si existe
        int sign = 1;
        if (s.endsWith("N")) sign =  1;
        if (s.endsWith("E")) sign =  1;
        if (s.endsWith("S")) sign = -1;
        if (s.endsWith("W")) sign = -1;

        // Quitar letras cardinales y espacios extremos
        s = s.replaceAll("[NSEW]", "").trim();

        // Si ya es decimal simple, intentar parsear directo
        try {
            // Soporta "-12.0219" o "12.0219"
            if (s.matches("[+-]?\\d+(?:\\.\\d+)?")) {
                double v = Double.parseDouble(s);
                return sign * v;
            }
        } catch (NumberFormatException ignored) { /* probar DMS */ }

        // Intentar DMS: deg [sep] min [sep] sec (cualquiera puede tener decimales)
        // Ej: 04° 42' 05"   |  74° 08' 49"
        Pattern dms = Pattern.compile(
            "([+-]?\\d+(?:\\.\\d+)?)\\D+(\\d+(?:\\.\\d+)?)?\\D*(\\d+(?:\\.\\d+)?)?"
        );
        Matcher dm = dms.matcher(s);
        if (!dm.find()) return null;

        double deg = parseOrZero(dm.group(1));
        double min = parseOrZero(dm.group(2));
        double sec = parseOrZero(dm.group(3));

        double decimal = Math.abs(deg) + (min / 60.0) + (sec / 3600.0);
        // Si había signo explícito en degrees, respétalo
        if (deg < 0) sign = -1;

        // Validación básica de rango
        double val = sign * decimal;
        if (isLat) {
            if (val < -90 || val > 90) return null;
        } else {
            if (val < -180 || val > 180) return null;
        }
        return val;
    }

    private static double parseOrZero(String s) {
        if (s == null || s.isBlank()) return 0.0;
        try { return Double.parseDouble(s); }
        catch (NumberFormatException e) { return 0.0; }
    }

    private static String inferContinente(int id) {
        if (id >= 1  && id <= 10) return "AmericaSur";
        if (id >= 11 && id <= 20) return "Europa";
        if (id >= 21 && id <= 30) return "Asia";
        return null;
    }
}
