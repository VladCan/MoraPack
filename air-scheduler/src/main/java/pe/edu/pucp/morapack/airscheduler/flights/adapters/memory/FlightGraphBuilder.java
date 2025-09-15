package pe.edu.pucp.morapack.airscheduler.flights.adapters.memory;

import java.util.List;
import java.util.Map;

import pe.edu.pucp.morapack.airscheduler.flights.domain.model.Aeropuerto;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.Vuelo;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosEdge;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosNode;

public final class FlightGraphBuilder {
    private FlightGraphBuilder(){}

    public static VuelosGraph build(AeropuertosMap aMap, VuelosMap vMap){
        VuelosGraph G = new VuelosGraph();

        // nodos
        for (Aeropuerto a : aMap.values()){
            G.addNode(new VuelosNode(a.getCodigo(), a.getCapacidad()));
        }
        // arcos
        for (Map.Entry<String, List<Vuelo>> e : vMap.getVuelosPorOrigen().entrySet()){
            var from = G.node(e.getKey());
            if (from == null) continue;
            for (Vuelo v : e.getValue()){
                var to = G.node(v.getDestino());
                if (to == null) continue;
                G.addEdge(new VuelosEdge(from, to, v));
            }
        }
        return G;
    }
}
