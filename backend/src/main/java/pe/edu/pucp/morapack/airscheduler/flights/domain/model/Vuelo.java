package pe.edu.pucp.morapack.airscheduler.flights.domain.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
// Opcional: si quieres construir con builder, descomenta:
// import lombok.Builder;

import java.time.LocalTime;
import java.util.Scanner;

@Data
@NoArgsConstructor
@AllArgsConstructor
// @Builder  // <- opcional, si quieres usar patrón builder
public class Vuelo {
    // ORIGEN-DESTINO-HoraOrigen-HoraDestino-Capacidad
    private int id;
    private String origen;
    private String destino;
    private LocalTime horaOrigen;
    private LocalTime horaDestino;
    private LocalTime horaGMTOrigen;
    private LocalTime horaGMTDestino;
    private int capacidad;

    // Constructor corto: conserva tu firma y comportamiento; delega al all-args
    public Vuelo(int id, String origen, String destino, LocalTime horaOrigen, LocalTime horaDestino, int capacidad) {
        this(id, origen, destino, horaOrigen, horaDestino, null, null, capacidad);
    }

    /** Lee una línea ORIGEN-DESTINO-HH:mm-HH:mm-capacidad, muta this y retorna this.origen (igual que antes). */
    public String leer(Scanner sc, int i) {
        if (!sc.hasNextLine()) return null;
        String linea = sc.nextLine(); // sin trim para respetar formato original
        String[] partes = linea.split("-");
        if (partes.length != 5) {
            System.out.println("Formato incorrecto: " + linea);
            return null;
        }

        this.id = i;
        this.origen = partes[0];
        this.destino = partes[1];
        this.horaOrigen = LocalTime.parse(partes[2]);
        this.horaDestino = LocalTime.parse(partes[3]);
        this.capacidad = Integer.parseInt(partes[4]);

        return this.origen;
    }

    @Override
    public String toString() {
        return "Vuelo " + id + " [" + origen + " → " + destino + "] "
                + horaOrigen + " - " + horaDestino
                + " Capacidad: " + capacidad
                + " OrigenGMT " + horaGMTOrigen
                + " DestinoGMT " + horaGMTDestino;
    }

    public void llenarHoraGMT(int offsetOrigenHoras, int offsetDestinoHoras) {
        if (horaOrigen != null)  this.horaGMTOrigen  = horaOrigen.minusHours(offsetOrigenHoras);
        if (horaDestino != null) this.horaGMTDestino = horaDestino.minusHours(offsetDestinoHoras);
    }

    public double getCosto() {
        if (horaOrigen == null || horaDestino == null) return 0.0;

        // Calcular duración en segundos
        int duracionSegundos = horaDestino.toSecondOfDay() - horaOrigen.toSecondOfDay();
        if (duracionSegundos < 0) duracionSegundos += 24 * 3600; // ajuste si cruza medianoche
        double duracionHoras = duracionSegundos / 3600.0;

        // Parámetros logísticos
        double costoBase = 50.0;                     // costo mínimo fijo por operación
        double penalizacionDuracion = duracionHoras * 20.0; // costo por hora de vuelo
        double factorCapacidad = (capacidad > 0) ? (100.0 / capacidad) : 1.0; // penaliza baja capacidad

        // Fórmula final
        return costoBase + penalizacionDuracion * factorCapacidad;
    }


}
