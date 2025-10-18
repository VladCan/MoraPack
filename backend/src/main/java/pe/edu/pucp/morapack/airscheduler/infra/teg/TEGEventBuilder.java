package pe.edu.pucp.morapack.airscheduler.infra.teg;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;

import lombok.RequiredArgsConstructor;
import pe.edu.pucp.morapack.airscheduler.engine.flights.model.AereopuertoNode;
import pe.edu.pucp.morapack.airscheduler.infra.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.infra.memory.VuelosMap;
import pe.edu.pucp.morapack.airscheduler.infra.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.infra.teg.helpers.CreadorTEG;
import pe.edu.pucp.morapack.airscheduler.infra.teg.helpers.IndexadorEventos;
import pe.edu.pucp.morapack.airscheduler.infra.teg.helpers.TEGParametros;

@RequiredArgsConstructor
public final class TEGEventBuilder {

    private final AeropuertosMap aeropuertosMap;
    private final VuelosMap vuelosMap;

    /** Punto de entrada único. */
    public VuelosTEG construir(TEGParametros p) {
        if (p == null || p.getInicioUtc() == null || p.getFinUtc() == null
                || !p.getInicioUtc().isBefore(p.getFinUtc())) {
            throw new IllegalArgumentException("Parámetros de TEG inválidos (inicio/fin).");
        }

        // 1) Indexar todos los instantes de evento por aeropuerto
        Map<String, NavigableSet<Instant>> eventosPorAP = IndexadorEventos.recolectarEventos(vuelosMap, p);

        // 2) Crear TEG
        VuelosTEG teg = new VuelosTEG();

        // 3) Nodos por aeropuerto
        Map<String, List<AereopuertoNode>> nodosPorAP = CreadorTEG.crearNodosEventos(teg, eventosPorAP, aeropuertosMap);

        // 4) Ω-sedes → primer evento
        CreadorTEG.crearSupplySedes(teg, nodosPorAP, p.getSedes());

        // 5) Stock inicial (consumible)
        CreadorTEG.inyectarStockInicial(teg, nodosPorAP, p, aeropuertosMap);

        // 6) SUPPLY puntual de carga ya en vuelo
        CreadorTEG.inyectarArribosLibres(teg, p, aeropuertosMap);

        // 7) WAIT
        CreadorTEG.crearWaits(teg, nodosPorAP, p, aeropuertosMap);

        // 8) Reservas de bodega (NO consumibles)
        CreadorTEG.aplicarReservasWaitIniciales(teg, nodosPorAP, p);

        // 9) FLIGHT
        CreadorTEG.crearFlights(teg, vuelosMap, p, aeropuertosMap);

        return teg;
    }
}