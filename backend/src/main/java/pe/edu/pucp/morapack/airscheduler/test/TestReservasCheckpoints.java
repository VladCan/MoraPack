package pe.edu.pucp.morapack.airscheduler.test;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.ArchivoUtils;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.OcupacionPorAeropuerto;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

public class TestReservasCheckpoints {
    public static void main(String[] args) {

        final Set<String> sedes = new HashSet<>(Arrays.asList("SPIM", "EBCI", "UBBB"));
        AeropuertosMap aeropuertosMap = new AeropuertosMap();// Aeropuertos (incluye husos horarios)
        try (Scanner sc = ArchivoUtils.getScannerFromFilePath(
                "aeropuertos.txt")) {
            if (sc == null)
                return;
            aeropuertosMap.leerDatos(sc);
        }

        OcupacionPorAeropuerto ocupacionPorAeropuerto = new OcupacionPorAeropuerto(aeropuertosMap);

        Instant f1 = Instant.parse("2025-10-08T06:18:00Z");
        Instant f2 = Instant.parse("2025-10-08T08:18:00Z");
        ocupacionPorAeropuerto.reservar("LOWW", f1, f2, 200);
        //El checkpoint debería de ser 0

        Instant f3 = Instant.parse("2025-10-09T00:00:00Z");
        Instant f4 = Instant.parse("2025-10-09T02:00:00Z");
        ocupacionPorAeropuerto.reservar("LOWW", f3, f4, 300);
        //El nuevo checkpoint del 2025-10-09 debería de ser 300

        Instant f5 = Instant.parse("2025-10-09T10:00:00Z");
        Instant f6 = Instant.parse("2025-10-10T01:00:00Z");
        ocupacionPorAeropuerto.reservar("LOWW", f5, f6, 250);
        //El nuevo checkpoint del 2025-10-10 debería de ser 300

        Instant f7 = Instant.parse("2025-10-10T11:00:00Z");
        Instant f8 = Instant.parse("2025-10-11T00:00:00Z");
        ocupacionPorAeropuerto.reservar("LOWW", f7, f8, 240);
        //El checkpoint del 2025-10-11 deberia de ser 0

        ocupacionPorAeropuerto.liberar("LOWW", f1, f2, 200);
        ocupacionPorAeropuerto.liberar("LOWW", f3, f4, 300);
        ocupacionPorAeropuerto.liberar("LOWW", f5, f6, 250);
        ocupacionPorAeropuerto.liberar("LOWW", f7, f8, 240);
    }
}
