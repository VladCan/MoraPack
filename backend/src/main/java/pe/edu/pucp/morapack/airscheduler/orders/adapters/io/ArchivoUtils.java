package pe.edu.pucp.morapack.airscheduler.orders.adapters.io;

import java.io.InputStream;
import  java.util.Scanner;
public class ArchivoUtils {
    public static Scanner getScannerFromResource(String nombreArchivo) {
        InputStream is = ArchivoUtils.class.getClassLoader().getResourceAsStream(nombreArchivo);
        if (is == null) {
            System.out.println("Archivo no encontrado en recursos: " + nombreArchivo);
            return null;
        }
        return new Scanner(is);
    }
}
