package pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.TEGEventBuilderHelpers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosMap;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.AereopuertoNode;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.ArriboExogeno;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.Vuelo;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosEdge;
import static pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.TEGEventBuilderHelpers.FechasTEG.instantesDiariosEnVentana;
import static pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.TEGEventBuilderHelpers.FechasTEG.combinarFechaYHora;

public final class CreadorTEG {

    private static final int CAP_INFINITA = Integer.MAX_VALUE / 2;

    private CreadorTEG() {}

    public static Map<String, List<AereopuertoNode>> crearNodosEventos(
            VuelosTEG teg, Map<String, NavigableSet<Instant>> eventosPorAP, AeropuertosMap aeropuertosMap) {

        Map<String, List<AereopuertoNode>> nodosPorAP = new HashMap<>();
        for (var e : eventosPorAP.entrySet()) {
            String ap = e.getKey();
            int capAP = aeropuertosMap.getCapBodega(ap);
            List<AereopuertoNode> lista = new ArrayList<>(e.getValue().size());
            for (Instant t : e.getValue()) {
                lista.add( teg.agregarONodo(ap, t, capAP, false) );
            }
            nodosPorAP.put(ap, lista);
        }
        return nodosPorAP;
    }

    public static void crearSupplySedes(VuelosTEG teg, Map<String, List<AereopuertoNode>> nodosPorAP, Set<String> sedes) {
        if (sedes == null) return;
        for (String sede : sedes) {
            List<AereopuertoNode> eventosSede = nodosPorAP.getOrDefault(sede, List.of());
            if (eventosSede.isEmpty()) continue;
            AereopuertoNode omega = teg.agregarONodo("OMEGA-" + sede, null, 0, true);
            AereopuertoNode primerEvento = eventosSede.get(0);
            teg.agregarArco(new VuelosEdge(omega, primerEvento, VuelosEdge.Type.SUPPLY, CAP_INFINITA, null));
        }
    }

    public static void inyectarArribosExogenos(VuelosTEG teg, TEGParametros p, AeropuertosMap aeropuertosMap) {
        Map<String, List<ArriboExogeno>> exo = p.getArribosExogenos();
        if (exo == null || exo.isEmpty()) return;

        for (var e : exo.entrySet()) {
            String destino = e.getKey();
            if (destino == null) continue;

            AereopuertoNode omegaArr = teg.agregarONodo("OMEGA-ARR-" + destino, null, 0, true);

            for (ArriboExogeno ax : e.getValue()) {
                if (ax == null || ax.arriboUtc() == null || ax.cantidad() <= 0) continue;
                Instant tArr = ax.arriboUtc();
                if (tArr.isBefore(p.getInicioUtc()) || !tArr.isBefore(p.getFinUtc())) continue;

                AereopuertoNode nArr = teg.agregarONodo(destino, tArr, aeropuertosMap.getCapBodega(destino), false);
                teg.agregarArco(new VuelosEdge(omegaArr, nArr, VuelosEdge.Type.SUPPLY, ax.cantidad(), null));
            }
        }
    }

    public static void crearWaits(VuelosTEG teg, Map<String, List<AereopuertoNode>> nodosPorAP,
                           TEGParametros p, AeropuertosMap aeropuertosMap) {
        for (var e : nodosPorAP.entrySet()) {
            String ap = e.getKey();
            List<AereopuertoNode> eventos = e.getValue();
            if (eventos.size() <= 1) continue;

            int capWait = (p.getCapacidadWaitPorDefecto() != null)
                    ? p.getCapacidadWaitPorDefecto()
                    : aeropuertosMap.getCapBodega(ap);

            for (int i = 0; i < eventos.size() - 1; i++) {
                AereopuertoNode a = eventos.get(i), b = eventos.get(i + 1);
                teg.agregarArco(new VuelosEdge(a, b, VuelosEdge.Type.WAIT, capWait, null));
            }
        }
    }

    public static void crearFlights(VuelosTEG teg, VuelosMap vuelosMap, TEGParametros p, AeropuertosMap aeropuertosMap) {
        for (String origen : vuelosMap.origenes()) {
            for (Vuelo v : vuelosMap.vuelosDesde(origen)) {
                for (Instant salida : instantesDiariosEnVentana(p.getInicioUtc(), p.getFinUtc(), v.getHoraGMTOrigen())) {
                    Instant llegada = combinarFechaYHora(salida, v.getHoraGMTDestino());
                    if (!llegada.isAfter(salida)) llegada = llegada.plus(1, ChronoUnit.DAYS);

                    AereopuertoNode nSalida  = teg.agregarONodo(v.getOrigen(),  salida,
                            aeropuertosMap.getCapBodega(v.getOrigen()), false);
                    AereopuertoNode nLlegada = teg.agregarONodo(v.getDestino(), llegada,
                            aeropuertosMap.getCapBodega(v.getDestino()), false);

                    teg.agregarArco(new VuelosEdge(nSalida, nLlegada, VuelosEdge.Type.FLIGHT, v.getCapacidad(), v));
                }
            }
        }
    }
}