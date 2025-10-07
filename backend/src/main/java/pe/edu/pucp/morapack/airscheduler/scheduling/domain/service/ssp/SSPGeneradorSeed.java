package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.ssp;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.ArriboExogeno;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosEdge;
import pe.edu.pucp.morapack.airscheduler.orders.domain.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Seed SSP (greedy) con rutas de 0..N transbordos:
 *  - Multi-hop solo inicia en SEDES (stock "ilimitado" para seed).
 *  - Directo desde no-sede permitido SOLO si hay stockLibre (arribos exógenos no comprometidos) antes de la salida.
 *
 * Orden de intento por pedido:
 *   1) Rutas desde sedes con 0 transbordos (directo S->D), luego 1, 2, ..., H_MAX (S->...->D),
 *      siempre eligiendo la ruta de llegada más temprana dentro del SLA/ventana.
 *   2) Si aún queda demanda: directos desde no-sede con stock libre (opcional).
 *
 * Respeta:
 *  - Capacidad residual por vuelo.
 *  - Ventana de consolidación 2h (todas las llegadas del pedido dentro de 2h desde la primera).
 *  - SLA 48h (llegada final <= createdAt + 48h).
 *
 * *** ACTUALIZADO ***
 *  - Ahora PlanPedido se llena con List<RutaAsignada> (cada ruta tiene su cantidad y sus tramos).
 */
public class SSPGeneradorSeed {

    private static final int H_MAX = 3;                        // tope razonable de escalas para evitar explosión
    private final Set<String> sedes;                           // orígenes habilitados para multi-hop
    private final StockLibre stockLibre;                       // stock disponible por no-sede (arribos exógenos no comprometidos)
    private final OcupacionPorAeropuerto ocupacionPorAeropuerto;//clase nueva implementada para control de stocks en tierra
    private final Duration slaLlegadaMax = Duration.ofHours(46);

    /** Construye con sedes y arribos libres (no comprometidos) por aeropuerto. */
    public SSPGeneradorSeed(Set<String> sedes, Map<String, List<ArriboExogeno>> arribosLibres) {
        this.sedes = (sedes == null) ? Set.of() : Set.copyOf(sedes);
        this.stockLibre = new StockLibre(arribosLibres);
        //Nuevo:
        this.ocupacionPorAeropuerto = null;
    }

    /** Si no tienes arribos libres todavía. */
    public SSPGeneradorSeed(Set<String> sedes) {
        this(sedes, Map.of(), null);
    }

    public SSPGeneradorSeed(Set<String> sedes, Map<String, List<ArriboExogeno>> arribosLibres, OcupacionPorAeropuerto ocupacionPorAeropuerto) {
        this.sedes = (sedes == null) ? Set.of() : Set.copyOf(sedes);
        this.stockLibre = new StockLibre(arribosLibres);
        this.ocupacionPorAeropuerto = ocupacionPorAeropuerto;
    }


    public SolucionProgramacion generarSeed(VuelosTEG teg,
                                            List<Pedido> pedidosOrdenados,
                                            Instant presenteUtc) {

        // Índices de vuelos para búsqueda por origen/destino
        IndexVuelos idx = new IndexVuelos(teg);

        // Capacidades iniciales (residuales) por vuelo
        CargaPorVuelo carga = new CargaPorVuelo();
        for (var vf : idx.todos()) carga.registrarCapacidad(vf.id, vf.capacidad);

        Map<Integer, PlanPedido> planPorPedido = new HashMap<>();

        for (Pedido p : pedidosOrdenados) {
            String dest = p.getDestino();
            int demanda = cantidadPedido(p);
            int rem = demanda;
            Instant limite = p.getCreatedAtUtc().plus(slaLlegadaMax);

            // ahora acumulamos RUTAS, no una lista plana de tramos
            List<RutaAsignada> rutasAsignadas = new ArrayList<>();
            Instant primeraLlegada = null; // ancla para la ventana 2h

            while (rem > 0) {
                // Si ya hay primera llegada, restringimos el límite por consolidación
                Instant limiteLlegada = limite;

                // 1) Encontrar la MEJOR ruta (mín #escalas, luego menor llegada) desde cualquier sede a dest
                Ruta ruta = null;
                for (int hops = 0; hops <= H_MAX; hops++) {
                    ruta = buscarRutaMinHops(idx, carga, sedes, dest, presenteUtc, limiteLlegada, hops);
                    if (ruta != null) break;
                }

                // 2) Si no hay ruta desde sedes, opcional: intenta DIRECTO desde no-sede con stock libre
                /*if (ruta == null) {
                    ruta = buscarDirectoNoSede(idx, carga, dest, presenteUtc, limiteLlegada);
                }*/

                if (ruta == null) break; // no hay más forma de asignar en esta ventana/SLA

                // 3.1) Determinar cantidad asignable: mínimo de residuales en los vuelos de la ruta
                int capRuta = capacidadEnRuta(carga, ruta);
                if (capRuta <= 0) {
                    // ruta inútil, evitamos bucle
                    break;
                }

                //3.2) Determinar cantidad asignable: mínimo de residuales en las esperas/llegada+2h de la ruta
                int capOcup = capacidadPorOcupacion(ruta);
                if (capOcup <= 0) {
                    // La ruta no “entra” por ocupación → intenta otra ruta
                    continue;
                }

                int q = Math.min(Math.min(capRuta, capOcup), rem);

                // 4) Si la ruta inicia en no-sede, validar/consumir stock libre antes de la salida del primer tramo
                String origenInicial = ruta.legs.get(0).id.getOrigen();
                Instant salidaInicial = ruta.legs.get(0).id.getSalidaUtc();
                if (!sedes.contains(origenInicial)) {
                    int disp = stockLibre.disponible(origenInicial, salidaInicial);
                    if (disp <= 0) {
                        // no hay stock; intenta otra ruta
                        break;
                    }
                    q = Math.min(q, disp);
                    if (q <= 0) break;
                    stockLibre.consumir(origenInicial, salidaInicial, q);
                }

                // 5.1) Reservar 'q' en los aeropuertos/almacenes
                reservarOcupacionesRuta(ruta, q);

                // 5.2) Asignar 'q' en todos los vuelos de los tramos y construir la RutaAsignada
                List<TramoAsignado> tramosRuta = new ArrayList<>(ruta.legs.size());
                for (VueloFicha leg : ruta.legs) {
                    carga.asignar(leg.id, q);
                    tramosRuta.add(new TramoAsignado(leg.id, q, leg.id.getLlegadaUtc()));
                }
                rutasAsignadas.add(new RutaAsignada(q, tramosRuta));

                // 6) Actualizar remanente y ventana 2h
                rem -= q;
                if (primeraLlegada == null) primeraLlegada = ruta.arriboFinal;
                // El while seguirá buscando más rutas que respeten la ventana 2h (con el límiteLlegada ajustado).
            }

            PlanPedido plan = PlanPedido.builder()
                    .idPedido(p.getIdPedido())
                    .aeropuertoDestino(dest)
                    .creadoUtc(p.getCreatedAtUtc())
                    .demanda(demanda)
                    .rutas(rutasAsignadas)   // <<<<<<<<<<<<<<<<<<<<<<<<<<<
                    .build();

            planPorPedido.put(p.getIdPedido(), plan);
        }

        return SolucionProgramacion.builder()
                .planPorPedido(planPorPedido)
                .cargaPorVuelo(carga)
                .build();
    }

    // ===================== Helper para validar y reservar espacios de almacén =====================

    static final class Intervalo {
        final String ap;
        final Instant ini;
        final Instant fin;
        Intervalo(String ap, Instant ini, Instant fin) { this.ap = ap; this.ini = ini; this.fin = fin; }
    }

    private List<Intervalo> intervalosDeRuta(Ruta r){
        List<Intervalo> ints = new ArrayList<>();
        List<VueloFicha> legs = r.legs;

        for (int i = 0; i<legs.size() - 1; i++) {
            var a = legs.get(i);
            var b = legs.get(i + 1);
            Instant ini = a.id.getLlegadaUtc();
            Instant fin = b.id.getSalidaUtc();

            //Aca modelamos si hay espera real (casi siempre habrá)
            if (ini.isBefore(fin)){
                ints.add(new Intervalo(a.id.getDestino(), ini, fin));
            }
        }

        var last = legs.get(legs.size() - 1);
        Instant arrUTC = last.id.getLlegadaUtc();
        ints.add(new Intervalo(last.id.getDestino(), arrUTC, arrUTC.plus(Duration.ofHours(2))));
        return ints;
    }

    private int capacidadPorOcupacion(Ruta ruta){
        int min = Integer.MAX_VALUE;
        for (Intervalo it : intervalosDeRuta(ruta)){
            int q = ocupacionPorAeropuerto.maxReservable(it.ap, it.ini, it.fin);
            min = Math.min(min, q);
        }
        return (min == Integer.MAX_VALUE) ? 0 : min;
    }

    private void reservarOcupacionesRuta(Ruta ruta, int q){
        for (Intervalo it : intervalosDeRuta(ruta)){
            if (it.ini.isBefore(it.fin)){
                ocupacionPorAeropuerto.reservar(it.ap, it.ini, it.fin, q);
            }
        }
    }

    // ===================== BÚSQUEDA DE RUTAS =====================

    /** Ruta candidata (legs + llegada final). */
    static final class Ruta {
        final List<VueloFicha> legs;
        final Instant arriboFinal;
        Ruta(List<VueloFicha> legs) {
            this.legs = legs;
            this.arriboFinal = legs.get(legs.size()-1).id.getLlegadaUtc();
        }
    }

    /**
     * Busca una ruta con exactamente hops transbordos (legs = hops+1) desde cualquier sede hasta 'dest',
     * cumpliendo tiempos y con alguna capacidad residual (>0) en cada tramo.
     * Selecciona la de llegada más temprana.
     */
    private Ruta buscarRutaMinHops(IndexVuelos idx,
                                   CargaPorVuelo carga,
                                   Set<String> sedes,
                                   String dest,
                                   Instant earliest,
                                   Instant latest,
                                   int hops) {
        Ruta mejor = null;

        // Por cada sede, DFS acotado por #hops y tiempos
        for (String sede : sedes) {
            Ruta r = dfsRutas(idx, carga, sede, dest, earliest, latest, hops, new ArrayList<>());
            if (r != null) {
                if (mejor == null || r.arriboFinal.isBefore(mejor.arriboFinal)) {
                    mejor = r;
                }
            }
        }
        return mejor;
    }

    /**
     * DFS por #hops con poda por tiempos/capacidad.
     * path acumula los vuelos ya elegidos; earliest es la salida mínima del siguiente vuelo.
     */
    private Ruta dfsRutas(IndexVuelos idx,
                          CargaPorVuelo carga,
                          String origen,
                          String dest,
                          Instant earliest,
                          Instant latest,
                          int hopsRestantes,
                          List<VueloFicha> path) {

        // salidas desde 'origen' a partir de 'earliest'
        List<VueloFicha> salidas = idx.porOrigen(origen);
        if (salidas.isEmpty()) return null;

        Ruta mejor = null;

        // Iterar por vuelos que respeten el tiempo
        for (VueloFicha f : salidas) {
            if (f.id.getSalidaUtc().isBefore(earliest)) continue;
            if (f.id.getLlegadaUtc().isAfter(latest)) continue;

            // poda por capacidad: si no hay residual, no sigas
            if (carga.residual(f.id) <= 0) continue;

            // poda por holgura/estancia para aeropuerto de llegada: si no hay espacio para 1 unidad, no sigas
            if (!path.isEmpty()) {
                VueloFicha prev = path.get(path.size() - 1);
                String apEscala = prev.id.getDestino();
                Instant inicioEspera = prev.id.getLlegadaUtc();
                Instant finEspera = f.id.getSalidaUtc();

                // Sólo si hay espera real (inicio < fin) verificamos holgura
                if (inicioEspera.isBefore(finEspera)) {
                    int holgura = ocupacionPorAeropuerto.maxReservable(apEscala, inicioEspera, finEspera);
                    if (holgura < 1) continue; // NO hay espacio ni para 1 → podar rama
                }
            }

            path.add(f);

            if (f.id.getDestino().equals(dest)) {
                // alcanzamos el destino: ruta válida solo si hopsRestantes == 0
                if (hopsRestantes == 0) {
                    String apDestino = f.id.getDestino();
                    Instant arrUTC = f.id.getLlegadaUtc();
                    Instant wait = arrUTC.plus(Duration.ofHours(2));

                    int holgura = ocupacionPorAeropuerto.maxReservable(apDestino, arrUTC, wait);

                    if (holgura >= 1){
                        Ruta cand = new Ruta(new ArrayList<>(path));
                        if (mejor == null || cand.arriboFinal.isBefore(mejor.arriboFinal)) mejor = cand;
                    }
                }
            } else if (hopsRestantes > 0) {
                // extender desde el nuevo aeropuerto, earliest = llegada del vuelo actual
                Ruta rec = dfsRutas(idx, carga, f.id.getDestino(), dest, f.id.getLlegadaUtc(), latest, hopsRestantes - 1, path);
                if (rec != null) {
                    if (mejor == null || rec.arriboFinal.isBefore(mejor.arriboFinal)) mejor = rec;
                }
            }

            path.remove(path.size() - 1);
        }

        return mejor;
    }

    /** Como último recurso: directo desde no-sede con stock libre antes de la salida. */
    private Ruta buscarDirectoNoSede(IndexVuelos idx,
                                     CargaPorVuelo carga,
                                     String dest,
                                     Instant earliest,
                                     Instant latest) {
        List<VueloFicha> direct = idx.porDestino(dest).stream()
                .filter(vf -> !vf.id.getSalidaUtc().isBefore(earliest)
                           && !vf.id.getLlegadaUtc().isAfter(latest))
                .sorted(Comparator.comparing(v -> v.id.getLlegadaUtc()))
                .collect(Collectors.toList());

        for (VueloFicha vf : direct) {
            if (carga.residual(vf.id) <= 0) continue;
            return new Ruta(List.of(vf));
        }
        return null;
    }

    private int capacidadEnRuta(CargaPorVuelo carga, Ruta r) {
        int min = Integer.MAX_VALUE;
        for (VueloFicha leg : r.legs) {
            min = Math.min(min, carga.residual(leg.id));
        }
        return (min == Integer.MAX_VALUE) ? 0 : min;
    }

    private static Instant min(Instant a, Instant b) { return (a.isBefore(b) ? a : b); }

    // ===================== ESTRUCTURAS DE APOYO =====================

    private int cantidadPedido(Pedido p) {
        try {
            return (int) Pedido.class.getMethod("getCantidad").invoke(p);
        } catch (Exception ignore) {
            try {
                return (int) Pedido.class.getMethod("getCantPaquetes").invoke(p);
            } catch (Exception e2) {
                throw new IllegalStateException("Define campo cantidad en Pedido (getCantidad o getCantPaquetes).");
            }
        }
    }

    /** Representa un vuelo FLIGHT del TEG con capacidad fija. */
    record VueloFicha(VueloProgramadoId id, int capacidad) {}//datos del vuelo

    /** Índices de vuelos por origen y por destino. */
    static final class IndexVuelos {
        private final Map<String, List<VueloFicha>> porDestino = new HashMap<>();
        private final Map<String, List<VueloFicha>> porOrigen  = new HashMap<>();
        private final List<VueloFicha> all = new ArrayList<>();

        IndexVuelos(VuelosTEG teg) {
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
            porDestino.values().forEach(lst -> lst.sort(Comparator.comparing(v -> v.id.getLlegadaUtc())));
            porOrigen.values().forEach(lst  -> lst.sort(Comparator.comparing(v -> v.id.getSalidaUtc())));
        }

        List<VueloFicha> porDestino(String destino) { return porDestino.getOrDefault(destino, List.of()); }
        List<VueloFicha> porOrigen(String origen)   { return porOrigen.getOrDefault(origen, List.of()); }
        List<VueloFicha> todos() { return all; }
    }

    /**
     * Stock libre (no comprometido) por aeropuerto. Acumula arribos por instante
     * y permite calcular disponible estricto antes de un "hasta" (headMap).
     */
    static final class StockLibre {
        private final Map<String, TreeMap<Instant, Integer>> arribos = new HashMap<>();
        private final Map<String, TreeMap<Instant, Integer>> consumos = new HashMap<>();

        StockLibre(Map<String, List<ArriboExogeno>> libres) {
            if (libres != null) {
                for (var e : libres.entrySet()) {
                    String ap = e.getKey();
                    var map = arribos.computeIfAbsent(ap, k -> new TreeMap<>());
                    for (ArriboExogeno ax : e.getValue()) {
                        if (ax == null || ax.arriboUtc() == null || ax.cantidad() <= 0) continue;
                        map.merge(ax.arriboUtc(), ax.cantidad(), Integer::sum);
                    }
                }
            }
        }

        /** Cantidad disponible acumulada STRICTLY BEFORE 'hasta'. */
        int disponible(String ap, Instant hasta) {
            var a = arribos.get(ap);
            if (a == null) return 0;
            int sumA = sumaHasta(a, hasta, false);
            int sumC = sumaHasta(consumos.computeIfAbsent(ap, k -> new TreeMap<>()), hasta, false);
            return Math.max(0, sumA - sumC);
        }

        void consumir(String ap, Instant hasta, int q) {
            int disp = disponible(ap, hasta);
            if (q > disp) {
                throw new IllegalStateException("Consumo mayor que disponible en " + ap + " @ " + hasta + " (" + q + " > " + disp + ")");
            }
            consumos.computeIfAbsent(ap, k -> new TreeMap<>()).merge(hasta, q, Integer::sum);
        }

        private static int sumaHasta(TreeMap<Instant, Integer> m, Instant t, boolean inclusive) {
            Map<Instant, Integer> sub = inclusive ? m.headMap(t, true) : m.headMap(t, false);
            int s = 0;
            for (int v : sub.values()) s += v;
            return s;
        }
    }
}
