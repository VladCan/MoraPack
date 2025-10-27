package pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.teg.helpers;

import static pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.teg.helpers.FechasTEG.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosMap;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.ArriboExogeno;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.OcupacionAlmacen;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Vuelo;

public final class IndexadorEventos {

    private IndexadorEventos() {
    }

    /**
     * Junta todos los instantes de evento por aeropuerto (salidas, llegadas, sedes
     * y exógenos).
     */
    public static Map<String, NavigableSet<Instant>> recolectarEventos(VuelosMap vuelosMap, TEGParametros p) {
        Map<String, NavigableSet<Instant>> eventos = new HashMap<>();

        // Escanear salidas desde un día antes para capturar llegadas dentro de [inicio,
        // fin)
        Instant desdeSalidas = p.getInicioUtc().minus(1, ChronoUnit.DAYS);

        for (String origen : vuelosMap.origenes()) {
            for (Vuelo v : vuelosMap.vuelosDesde(origen)) {
                for (Instant tSalida : instantesDiariosEnVentana(desdeSalidas, p.getFinUtc(), v.getHoraGMTOrigen())) {
                    Instant tLlegada = combinarFechaYHora(tSalida, v.getHoraGMTDestino());
                    if (!tLlegada.isAfter(tSalida))
                        tLlegada = tLlegada.plus(1, ChronoUnit.DAYS);

                    if (!tSalida.isBefore(p.getInicioUtc()) && tSalida.isBefore(p.getFinUtc())) {
                        addEvento(eventos, v.getOrigen(), tSalida);
                    }
                    if (!tLlegada.isBefore(p.getInicioUtc()) && tLlegada.isBefore(p.getFinUtc())) {
                        addEvento(eventos, v.getDestino(), tLlegada);
                    }
                }
            }
        }

        // Asegurar evento "inicio" por sede (para enganchar Ω-sede)
        if (p.getSedes() != null) {
            for (String sede : p.getSedes())
                addEvento(eventos, sede, p.getInicioUtc());
        }

        // Añadir instantes de arribos exógenos (para que existan nodos y WAITs)
        Map<String, List<ArriboExogeno>> exo = p.getArribosLibres();
        if (exo != null && !exo.isEmpty()) {
            for (var e : exo.entrySet()) {
                String ap = e.getKey();
                if (ap == null)
                    continue;
                for (ArriboExogeno ax : e.getValue()) {
                    if (ax == null || ax.arriboUtc() == null)
                        continue;
                    Instant tArr = ax.arriboUtc();
                    if (!tArr.isBefore(p.getInicioUtc()) && tArr.isBefore(p.getFinUtc())) {
                        addEvento(eventos, ap, tArr);
                    }
                }
            }
        }
        List<OcupacionAlmacen> resvs = p.getReservasWaitIniciales();
        if (resvs != null && !resvs.isEmpty()) {
            for (OcupacionAlmacen r : resvs) {
                if (r == null || r.aeropuerto() == null)
                    continue;
                String ap = r.aeropuerto();
                // desde y hasta dentro del [inicio, fin)
                Instant d = r.desde();
                Instant h = r.hasta();
                if (d != null && !d.isBefore(p.getInicioUtc()) && d.isBefore(p.getFinUtc())) {
                    addEvento(eventos, ap, d);
                }
                if (h != null && !h.isBefore(p.getInicioUtc()) && h.isBefore(p.getFinUtc())) {
                    addEvento(eventos, ap, h);
                }
            }
        }

        // Eventos por stock inicial: asegura evento en inicio para el AP
        Map<String, Integer> stock = p.getStockInicial();
        if (stock != null && !stock.isEmpty()) {
            for (String ap : stock.keySet()) {
                if (ap != null)
                    addEvento(eventos, ap, p.getInicioUtc());
            }
        }
        return eventos;
    }

    private static void addEvento(Map<String, NavigableSet<Instant>> eventos, String ap, Instant t) {
        eventos.computeIfAbsent(ap, k -> new TreeSet<>()).add(t);
    }
}