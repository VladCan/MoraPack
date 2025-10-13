package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosEdge;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.VueloProgramadoId;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.ssp.SSPGeneradorSeed;

import java.util.*;

public final class IndexVuelos {
    private final Map<String, List<VueloFicha>> porDestino = new HashMap<>();
    private final Map<String, List<VueloFicha>> porOrigen  = new HashMap<>();
    private final List<VueloFicha> all = new ArrayList<>();

    public IndexVuelos(VuelosTEG teg) {
        for (VuelosEdge e : teg.arcos()) {
            if (e.tipo() != VuelosEdge.Type.FLIGHT) continue;
            var a = e.salida();
            var b = e.destino();
            if (a == null || b == null || a.getTiempoUTC() == null || b.getTiempoUTC() == null) continue;

            VueloProgramadoId id = new VueloProgramadoId(
                    a.getCodigoAP(), b.getCodigoAP(), a.getTiempoUTC(), b.getTiempoUTC());
            VueloFicha vf = new VueloFicha(id, e.capacidad());

            all.add(vf);
            porDestino.computeIfAbsent(id.getDestino(), k -> new ArrayList<>()).add(vf);
            porOrigen.computeIfAbsent(id.getOrigen(),  k -> new ArrayList<>()).add(vf);
        }
        porDestino.values().forEach(lst -> lst.sort(Comparator.comparing(v -> v.id().getLlegadaUtc())));
        porOrigen.values().forEach(lst  -> lst.sort(Comparator.comparing(v -> v.id().getSalidaUtc())));
    }

    public List<VueloFicha> porDestino(String destino) { return porDestino.getOrDefault(destino, List.of()); }
    public List<VueloFicha> porOrigen(String origen)   { return porOrigen.getOrDefault(origen, List.of()); }
    public List<VueloFicha> todos() { return all; }
}
