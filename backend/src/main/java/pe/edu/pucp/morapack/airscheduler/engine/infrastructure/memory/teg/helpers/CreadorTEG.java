package pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.teg.helpers;

import static pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.teg.helpers.FechasTEG.combinarFechaYHora;
import static pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.teg.helpers.FechasTEG.instantesDiariosEnVentana;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosCancelados;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosMap;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.AereopuertoNode;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.ArriboExogeno;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.OcupacionAlmacen;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Vuelo;
import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.VuelosEdge;

public final class CreadorTEG {

    private static final int CAP_INFINITA = Integer.MAX_VALUE / 2;

    private CreadorTEG() {
    }

    public static Map<String, List<AereopuertoNode>> crearNodosEventos(
            VuelosTEG teg, Map<String, NavigableSet<Instant>> eventosPorAP, AeropuertosMap aeropuertosMap) {

        Map<String, List<AereopuertoNode>> nodosPorAP = new HashMap<>();
        for (var e : eventosPorAP.entrySet()) {
            String ap = e.getKey();
            int capAP = aeropuertosMap.getCapBodega(ap);
            List<AereopuertoNode> lista = new ArrayList<>(e.getValue().size());
            for (Instant t : e.getValue()) {
                lista.add(teg.agregarONodo(ap, t, capAP, false));
            }
            nodosPorAP.put(ap, lista);
        }
        return nodosPorAP;
    }

    public static void crearSupplySedes(VuelosTEG teg, Map<String, List<AereopuertoNode>> nodosPorAP,
            Set<String> sedes) {
        if (sedes == null)
            return;
        for (String sede : sedes) {
            List<AereopuertoNode> eventosSede = nodosPorAP.getOrDefault(sede, List.of());
            if (eventosSede.isEmpty())
                continue;
            AereopuertoNode omega = teg.agregarONodo("OMEGA-" + sede, null, 0, true);
            AereopuertoNode primerEvento = eventosSede.get(0);
            teg.agregarArco(new VuelosEdge(omega, primerEvento, VuelosEdge.Type.SUPPLY, CAP_INFINITA, null));
        }
    }

    public static void inyectarArribosLibres(VuelosTEG teg, TEGParametros p, AeropuertosMap aeropuertosMap) {
        Map<String, List<ArriboExogeno>> exo = p.getArribosLibres();
        if (exo == null || exo.isEmpty())
            return;

        for (var e : exo.entrySet()) {
            String destino = e.getKey();
            if (destino == null)
                continue;
            AereopuertoNode omegaArr = teg.agregarONodo("OMEGA-ARR-" + destino, null, 0, true);

            for (ArriboExogeno ax : e.getValue()) {
                if (ax == null || ax.arriboUtc() == null || ax.cantidad() <= 0)
                    continue;
                Instant tArr = ax.arriboUtc();
                if (tArr.isBefore(p.getInicioUtc()) || !tArr.isBefore(p.getFinUtc()))
                    continue;

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
            if (eventos.size() <= 1)
                continue;

            int capWait = (p.getCapacidadWaitPorDefecto() != null)
                    ? p.getCapacidadWaitPorDefecto()
                    : aeropuertosMap.getCapBodega(ap);

            for (int i = 0; i < eventos.size() - 1; i++) {
                AereopuertoNode a = eventos.get(i), b = eventos.get(i + 1);
                teg.agregarArco(new VuelosEdge(a, b, VuelosEdge.Type.WAIT, capWait, null));
            }
        }
    }

    public static void crearFlights(VuelosTEG teg, VuelosMap vuelosMap, TEGParametros p,
            AeropuertosMap aeropuertosMap) {
        VuelosCancelados cancelados = new VuelosCancelados();
        cancelados.setCanceladosMap(p.getVuelosCancelados());

        for (String origen : vuelosMap.origenes()) {
            for (Vuelo v : vuelosMap.vuelosDesde(origen)) {
                List<Integer> diasCancelados = cancelados.diasCancelado(v);
                if(diasCancelados!=null && !diasCancelados.isEmpty()){
                    System.out.printf("✈️  Vuelo cancelado: %s en días %s%n",
                            v.getHoraOrigen(), diasCancelados);
                }
                for (Instant salida : instantesDiariosEnVentana(p.getInicioUtc(), p.getFinUtc(),
                        v.getHoraGMTOrigen())) {
                    Instant llegada = combinarFechaYHora(salida, v.getHoraGMTDestino()); 
                    if (!llegada.isAfter(salida))
                        llegada = llegada.plus(1, ChronoUnit.DAYS);

                    AereopuertoNode nSalida = teg.agregarONodo(v.getOrigen(), salida,
                            aeropuertosMap.getCapBodega(v.getOrigen()), false);
                    AereopuertoNode nLlegada = teg.agregarONodo(v.getDestino(), llegada,
                            aeropuertosMap.getCapBodega(v.getDestino()), false);

                    teg.agregarArco(new VuelosEdge(nSalida, nLlegada, VuelosEdge.Type.FLIGHT, v.getCapacidad(), v));
                }
            }
        }
    }

    public static void inyectarStockInicial(
            VuelosTEG teg,
            Map<String, List<AereopuertoNode>> nodosPorAP,
            TEGParametros p,
            AeropuertosMap aeropuertosMap) {

        Map<String, Integer> stock = p.getStockInicial();
        if (stock == null || stock.isEmpty())
            return;

        for (var e : stock.entrySet()) {
            String ap = e.getKey();
            int cantidad = e.getValue() == null ? 0 : e.getValue();
            if (ap == null || cantidad <= 0)
                continue;

            List<AereopuertoNode> eventos = nodosPorAP.getOrDefault(ap, List.of());
            if (eventos.isEmpty())
                continue;

            // Nodo Omega que provee el stock en el primer evento >= inicio
            AereopuertoNode omega = teg.agregarONodo("OMEGA-STOCK-" + ap, null, 0, true);

            // primer evento del AP (gracias al addEvento(inicio) ya existe)
            AereopuertoNode primer = eventos.get(0);

            teg.agregarArco(new VuelosEdge(omega, primer, VuelosEdge.Type.SUPPLY, cantidad, null));
        }
    }

    public static void aplicarReservasWaitIniciales(
            VuelosTEG teg,
            Map<String, List<AereopuertoNode>> nodosPorAP,
            TEGParametros p) {

        List<OcupacionAlmacen> resvs = p.getReservasWaitIniciales();
        if (resvs == null || resvs.isEmpty())
            return;

        for (OcupacionAlmacen r : resvs) {
            if (r == null || r.aeropuerto() == null || r.cantidad() <= 0)
                continue;

            String ap = r.aeropuerto();
            Instant desde = r.desde(), hasta = r.hasta();
            if (desde == null || hasta == null || !desde.isBefore(hasta))
                continue;

            List<AereopuertoNode> eventos = nodosPorAP.get(ap);
            if (eventos == null || eventos.size() <= 1)
                continue;

            // recorre los segmentos [ti, ti+1)
            for (int i = 0; i < eventos.size() - 1; i++) {
                AereopuertoNode a = eventos.get(i), b = eventos.get(i + 1);
                Instant ti = a.getTiempoUTC(), tj = b.getTiempoUTC();
                if (ti == null || tj == null)
                    continue;

                boolean solapa = ti.isBefore(hasta) && !tj.isBefore(desde); // [ti,tj) ∩ [desde,hasta) ≠ ∅
                if (!solapa)
                    continue;

                // Reduce capacidad del WAIT (a,b) en 'cantidad'
                // -> necesitas un helper en VuelosTEG para ubicar el WAIT edge (a,b)
                teg.reducirCapacidadWaitEntre(a, b, r.cantidad());
            }
        }
    }

}