package pe.edu.pucp.morapack.airscheduler.flights.adapters.memory;

import pe.edu.pucp.morapack.airscheduler.flights.domain.model.*;

import java.time.*;
import java.util.*;

public final class VueloTEGBuilder {
    private VueloTEGBuilder(){}

    /**
     * Construye TEG en UTC:
     * - Instancia diaria de cada vuelo entre [start,end]
     * - Arcos WAIT (cap=capacidad aeropuerto) en NO-sedes
     * - Arcos SUPPLY Ω→(sede,t_salida) con cap = suma de caps de vuelos que salen en t_salida
     */
    public static VuelosTEG build(AeropuertosMap aMap,
                                  VuelosMap vMap,
                                  Instant start, Instant end,
                                  Set<String> sedesSiempreLlenas) {

        Objects.requireNonNull(start); Objects.requireNonNull(end);
        if (!start.isBefore(end)) throw new IllegalArgumentException("start >= end");

        VuelosTEG G = new VuelosTEG();

        // Ω super source (sin tiempo)
        final String OMEGA = "OMEGA";
        VuelosNode omega = G.addOrGetNode(OMEGA, null, 0, true);

        // eventos por aeropuerto (para WAIT)
        Map<String, TreeSet<Instant>> eventos = new HashMap<>();
        // suma de capacidad de salidas por (sede, t) para SUPPLY
        Map<String, Map<Instant, Integer>> sumCapSalidasSede = new HashMap<>();

        // rango de días (UTC)
        LocalDate d0 = LocalDateTime.ofInstant(start, ZoneOffset.UTC).toLocalDate();
        LocalDate d1 = LocalDateTime.ofInstant(end,   ZoneOffset.UTC).toLocalDate();

        for (Map.Entry<String, List<Vuelo>> e : vMap.getVuelosPorOrigen().entrySet()) {
            String origen = e.getKey();
            var aeroOri = aMap.obtener(origen);
            if (aeroOri == null) continue;

            int capAlmOrigen = aeroOri.getCapacidad();

            for (Vuelo v : e.getValue()) {
                LocalTime depGMT = v.getHoraGMTOrigen();
                LocalTime arrGMT = v.getHoraGMTDestino();
                if (depGMT == null || arrGMT == null) continue;

                for (LocalDate d = d0; !d.isAfter(d1); d = d.plusDays(1)) {
                    Instant dep = d.atTime(depGMT).toInstant(ZoneOffset.UTC);
                    Instant arr = d.atTime(arrGMT).toInstant(ZoneOffset.UTC);
                    if (!arr.isAfter(dep)) arr = arr.plus(Period.ofDays(1)); // llegada día+1

                    // filtra por horizonte
                    if (dep.isBefore(start) || dep.isAfter(end)) continue;

                    // nodos (origen@dep, destino@arr)
                    var aeroDst = aMap.obtener(v.getDestino());
                    if (aeroDst == null) continue;

                    VuelosNode nDep = G.addOrGetNode(origen, dep, capAlmOrigen, false);
                    VuelosNode nArr = G.addOrGetNode(v.getDestino(), arr, aeroDst.getCapacidad(), false);

                    // arco de vuelo
                    G.addEdge(new VuelosEdge(nDep, nArr, VuelosEdge.Type.FLIGHT, v.getCapacidad(), v));

                    // eventos para WAIT
                    eventos.computeIfAbsent(origen, k -> new TreeSet<>()).add(dep);
                    eventos.computeIfAbsent(v.getDestino(), k -> new TreeSet<>()).add(arr);

                    // si es sede "siempre llena", acumular cap de salidas en ese instante dep
                    if (sedesSiempreLlenas.contains(origen)) {
                        sumCapSalidasSede
                            .computeIfAbsent(origen, k -> new HashMap<>())
                            .merge(dep, v.getCapacidad(), Integer::sum);
                    }
                }
            }
        }

        // arcos WAIT (sólo en NO-sedes)
        for (var entry : eventos.entrySet()) {
            String icao = entry.getKey();
            if (sedesSiempreLlenas.contains(icao)) continue; // en sedes: sin espera (JIT)

            var aero = aMap.obtener(icao);
            if (aero == null) continue;
            int capAlm = aero.getCapacidad();

            var times = entry.getValue();
            if (times.size() < 2) continue;

            Instant prev = null;
            for (Instant t : times) {
                if (prev != null) {
                    VuelosNode a = G.addOrGetNode(icao, prev, capAlm, false);
                    VuelosNode b = G.addOrGetNode(icao, t,    capAlm, false);
                    G.addEdge(new VuelosEdge(a, b, VuelosEdge.Type.WAIT, capAlm, null));
                }
                prev = t;
            }
        }

        // arcos SUPPLY desde Ω a cada salida de sede (cap = suma de caps que parten en ese instante)
        for (var entry : sumCapSalidasSede.entrySet()) {
            String sede = entry.getKey();
            int capAlmSede = aMap.obtener(sede).getCapacidad(); // informativo

            for (var depEntry : entry.getValue().entrySet()) {
                Instant tSalida = depEntry.getKey();
                int capSum = depEntry.getValue();

                VuelosNode nSalida = G.addOrGetNode(sede, tSalida, capAlmSede, false);
                G.addEdge(new VuelosEdge(omega, nSalida, VuelosEdge.Type.SUPPLY, capSum, null));
            }
        }

        return G;
    }

    // Overload por comodidad: horizonte a partir de pedidos (si lo necesitas)
    public static VuelosTEG build(AeropuertosMap aMap,
                                  LiveTEGState live,
                                  Instant t0,
                                  Instant t1,
                                  Set<String> sedes) {
        VuelosTEG teg = new VuelosTEG();
        // Delegamos en el estado vivo para poblar el grafo recortado al horizonte
        live.populateTEG(teg, aMap, t0, t1, sedes);
        return teg;
    }
}
