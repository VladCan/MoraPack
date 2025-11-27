package pe.edu.pucp.morapack.airscheduler.api.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io.ArchivoManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@ApplicationScoped
public class ReportesService {
    @Inject
    ArchivoManager archivoManager; // Usamos el manager para la ruta

    private static final String FILENAME = "reporteSimulacion.txt";
    private static final String LAST_PLAN = "ultimaPlanificacion.txt";

    public Path getReportesFilePath() {
        return Paths.get(archivoManager.getReportsDir(), FILENAME);
    }

    public Path getLastPlanFilePath() {
        return Paths.get(archivoManager.getReportsDir(), LAST_PLAN);
    }

    private Path getFilePath(String fileName) {
        return Paths.get(archivoManager.getReportsDir(), fileName);
    }

    private void borrarSiExiste(String fileName) {
        Path p = getFilePath(fileName);
        try {
            if (Files.exists(p)) {
                Files.delete(p);
                System.out.println("[ReportesService] Eliminado: " + p);
            }
            else{
                System.out.println("[ReportesService] El archivo no existe, no se puede eliminar.");
            }
        } catch (IOException e) {
            System.err.println("[ReportesService] (x) No se pudo borrar reporte: " + e.getMessage());
        }
    }

    public void limpiarReportesPrevios(){
        borrarSiExiste(FILENAME);
        borrarSiExiste(LAST_PLAN);
    }

}
