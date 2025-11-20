package pe.edu.pucp.morapack.airscheduler.api.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.ArchivoManager;

import java.nio.file.Path;
import java.nio.file.Paths;

@ApplicationScoped
public class ReportesService {
    @Inject
    ArchivoManager archivoManager; // Usamos el manager para la ruta

    private static final String FILENAME = "reporteSimulacion.txt";

    public Path getReportesFilePath() {
        return Paths.get(archivoManager.getReportsDir(), FILENAME);
    }

}
