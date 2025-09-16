package pe.edu.pucp.morapack.airscheduler.flights.adapters.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;

import lombok.RequiredArgsConstructor;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.TEGEventBuilderHelpers.CreadorTEG;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.TEGEventBuilderHelpers.IndexadorEventos;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.TEGEventBuilderHelpers.TEGParametros;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.AereopuertoNode;

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
        Map<String, NavigableSet<Instant>> eventosPorAP =
                IndexadorEventos.recolectarEventos(vuelosMap, p);

        // 2) Crear TEG
        VuelosTEG teg = new VuelosTEG();

        // 3) Nodos por aeropuerto
        Map<String, List<AereopuertoNode>> nodosPorAP =
                CreadorTEG.crearNodosEventos(teg, eventosPorAP, aeropuertosMap);

        // 4) Ω-sedes → primer evento
        CreadorTEG.crearSupplySedes(teg, nodosPorAP, p.getSedes());

        // 5) SUPPLY puntual de carga ya en vuelo
        CreadorTEG.inyectarArribosExogenos(teg, p, aeropuertosMap);

        // 6) WAIT entre eventos contiguos por aeropuerto
        CreadorTEG.crearWaits(teg, nodosPorAP, p, aeropuertosMap);

        // 7) FLIGHT exactos para salidas en [inicio, fin)
        CreadorTEG.crearFlights(teg, vuelosMap, p, aeropuertosMap);

        return teg;
    }
}
