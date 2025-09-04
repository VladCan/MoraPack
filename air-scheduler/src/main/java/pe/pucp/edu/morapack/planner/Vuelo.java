package pe.pucp.edu.morapack.planner;
import java.time.*;

import java.util.Scanner;

public class Vuelo {

    //ORIGEN-DESTINO-HoraOrigen-HoraDestino-Capacidad
        private int id;
        private String origen;
        private String destino;
        private LocalTime horaOrigen;
        private LocalTime horaDestino;
        private LocalTime horaGMTOrigen;
        private LocalTime horaGMTDestino;
        private int capacidad;

    public Vuelo() {
    }

    public Vuelo(int id, String origen, String destino, LocalTime horaOrigen, LocalTime horaDestino, int capacidad) {
        this.id = id;
        this.origen = origen;
        this.destino = destino;
        this.horaOrigen = horaOrigen;
        this.horaDestino = horaDestino;
        this.capacidad = capacidad;
    }

    public Vuelo(int id, String origen, String destino, LocalTime horaOrigen, LocalTime horaDestino, LocalTime horaGMTOrigen, LocalTime horaGMTDestino, int capacidad) {
        this.id = id;
        this.origen = origen;
        this.destino = destino;
        this.horaOrigen = horaOrigen;
        this.horaDestino = horaDestino;
        this.horaGMTOrigen = horaGMTOrigen;
        this.horaGMTDestino = horaGMTDestino;
        this.capacidad = capacidad;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getOrigen() {
        return origen;
    }

    public void setOrigen(String origen) {
        this.origen = origen;
    }

    public LocalTime getHoraDestino() {
        return horaDestino;
    }

    public void setHoraDestino(LocalTime horaDestino) {
        this.horaDestino = horaDestino;
    }

    public int getCapacidad() {
        return capacidad;
    }

    public void setCapacidad(int capacidad) {
        this.capacidad = capacidad;
    }

    public LocalTime getHoraOrigen() {
        return horaOrigen;
    }

    public void setHoraOrigen(LocalTime horaOrigen) {
        this.horaOrigen = horaOrigen;
    }

    public LocalTime getHoraGMTOrigen() {
        return horaGMTOrigen;
    }

    public void setHoraGMTOrigen(LocalTime horaGMTOrigen) {
        this.horaGMTOrigen = horaGMTOrigen;
    }

    public LocalTime getHoraGMTDestino() {
        return horaGMTDestino;
    }

    public void setHoraGMTDestino(LocalTime horaGMTDestino) {
        this.horaGMTDestino = horaGMTDestino;
    }

    public String getDestino() {
        return destino;
    }

    public void setDestino(String destino) {
        this.destino = destino;
    }

    public String leer(Scanner sc,int i){
        if (!sc.hasNextLine()) return null;
        String linea = sc.nextLine();
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
                + horaOrigen + " - " + horaDestino + " Capacidad: " + capacidad + " OrigenGMT " + horaGMTOrigen + " DestinoGMT "+horaGMTDestino;
    }

    public void llenarHoraGMT(int origen,int destino ){
            if(horaOrigen!=null){
                this.horaGMTOrigen=horaOrigen.minusHours(origen);
            }
            if(horaDestino!=null){
                this.horaGMTDestino=horaDestino.minusHours(destino);
            }
    }
}
