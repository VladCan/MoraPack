package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.service.IndexVuelos;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.service.VueloFicha;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.ssp.SSPGeneradorSeed;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.IntStream;

/**
 * RegretRepair paralelizado:
 * - Procesa planes en paralelo.
 * - Procesa rutas dentro de cada plan en paralelo.
 */
public class RegretRepair implements RepairOperator {

    private final int k;
    private final VuelosTEG teg;
    private final List<String> sedes;
    private final IndexVuelos indexVuelos;

    private final int N = 5;
    private final Duration slaLlegadaMax = Duration.ofHours(46);
    private final int H_MAX = 3;

    /// Esto es para el k-regret:
    private final double L_HOPS = 0.2;
    private final double L_BUFFER = 0.8;
    private final double L_CAP = 0.5;

    public RegretRepair(int k, List<String> sedes, VuelosTEG teg) {
        this.k = k;
        this.sedes = sedes;
        this.teg = teg;
        this.indexVuelos = new IndexVuelos(teg);
    }

    @Override
    public void repair(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {

        List<PlanPedido> planos = new ArrayList<>(s.getPlanPorPedido().values());
        CargaPorVuelo cargaPorVuelo = s.getCargaPorVuelo();

        System.out.println("Estamos dentro del repair.");

        ///Vamos a buscar todos los planes que no tengan rutas (osea, los destruidos)
        for (PlanPedido plan : planos) {
            /// Encontramos un plan sin rutas
            if (plan.getRutas() == null || plan.getRutas().isEmpty()) {
                int idPedido = plan.getIdPedido();
                int demanda = plan.getDemanda();
                String dest = plan.getAeropuertoDestino();
                Instant limite = plan.getCreadoUtc().plus(slaLlegadaMax);

                List<PlanPedido> planesCandidatos = new ArrayList<>();

                System.out.println("La demanda de este pedido es: " + demanda);

                /// Vamos a generar N planes para un mismo pedido
                for (int i = 0; i < N; i++) {
                    List<RutaAsignada> rutasAsignadas = new ArrayList<>();
                    Instant primeraLlegada = null;

                    /// Objetos/clases para modelar las ocupaciones locales dentro de la iteración i
                    Map<VueloProgramadoId, Integer> deltaCargaLocal = new HashMap<>();
                    OcupacionEnAlmacen ocupacionEnAlmacen = new OcupacionEnAlmacen();

                    ///Acá tendríamos que aplicar las perturbaciones

                    /// 1. Aleatorización de sedes (orden)
                    List<String> sedesI = ordenarSedesAleatorio(idPedido, i, sedes);
                    /// 2. Aleatorización de hops (orden)
                    List<Integer> hopsI = ordenarHopsAleatorio(idPedido, i, H_MAX);

                    int rem = demanda;

                    System.out.println("!!!!Comenzando plan: " + (i+1) + " de " + N);

                    /// Aca dentro va la lógica del SSP
                    while (rem > 0) {

                        Instant limiteLlegada = limite;

                        // 1) Encontrar la MEJOR ruta (mín #escalas, luego menor llegada) desde cualquier sede a dest
                        Ruta ruta = null;

                        for (int hops : hopsI) {
                            ruta = buscarRutaMinHops(indexVuelos, cargaPorVuelo, sedesI, dest, presenteUTC,
                                    limiteLlegada, hops, deltaCargaLocal, ocupacionEnAlmacen, journal);
                            if (ruta != null) break;
                        }

                        System.out.println("Salí de buscarRutaMinHops.");

                        //No se pudo asignar ni una sola ruta: break
                        if (ruta == null) break;

                        System.out.println("Tenemos una supuesta ruta factible (ruta != null).");

                        // 3.1) Determinar cantidad asignable: mínimo de residuales en los vuelos de la ruta
                        int capRuta = capacidadVuelosSim(ruta, cargaPorVuelo, deltaCargaLocal);
                        if (capRuta <= 0) {
                            // ruta inútil, evitamos bucle
                            break;
                            //continue;
                        }

                        //3.2) Determinar cantidad asignable: mínimo de residuales en las esperas/llegada+2h de la ruta
                        int capOcup = capacidadAeropuertoSim(ruta, ocupacionEnAlmacen, journal);
                        if (capOcup <= 0) {
                            // La ruta no “entra” por ocupación → intenta otra ruta
                            break;
                            //continue;
                        }

                        //Determinamos mínimo asignable
                        int q = Math.min(Math.min(capRuta, capOcup), rem);

                        System.out.println("Ruta factible. Vamos a reservar: " + q);

                        // 5.1) Reservar 'q' en los aeropuertos/almacenes
                        reservarOcupacionesRuta(ruta, ocupacionEnAlmacen, q);

                        // 5.2) Asignar 'q' en todos los vuelos de los tramos y construir la RutaAsignada
                        List<TramoAsignado> tramosRuta = reservarOcupacionesVuelos(ruta, deltaCargaLocal, q);
                        rutasAsignadas.add(new RutaAsignada(q, tramosRuta));

                        // 6) Actualizar remanente y ventana 2h
                        rem -= q;

                        System.out.println("Ahora mi rem es: " + rem);

                        if (primeraLlegada == null) primeraLlegada = ruta.arriboFinal;

                    }

                    System.out.println("Salí del bucle");

                    if (rem <= 0){
                        PlanPedido planCandidato = PlanPedido.builder()
                                .idPedido(idPedido)
                                .aeropuertoDestino(dest)
                                .creadoUtc(plan.getCreadoUtc())
                                .demanda(demanda)
                                .rutas(rutasAsignadas)   // <<<<<<<<<<<<<<<<<<<<<<<<<<<
                                .build();

                        planesCandidatos.add(planCandidato);
                    }

                }

                /// Sobre los planesCandidatos, aplicamos el regret y nos quedamos con 1.
                PlanPedido planElegido = obtenerPlanElegido(planesCandidatos, cargaPorVuelo);

                /// Con dicho plan, actualizamos las ocupaciones de solucionProgramacion
                aplicarPlanEnGlobal(planElegido, s, journal);

                /// Nueva cargaPorVuelo (de la solución actualizada)
                cargaPorVuelo = s.getCargaPorVuelo();


            }
        }

    }

    private class OcupacionEnAlmacen{
        private Map<String, TreeMap<Instant, Integer>> eventos = new HashMap<>();

        public OcupacionEnAlmacen() {}

        public Integer picoMaxIntervalo(String idAeropuerto, Instant inicio, Instant fin){
            TreeMap<Instant, Integer> evs = eventos.computeIfAbsent(idAeropuerto, k -> new TreeMap<>());
            if (evs.isEmpty()) return 0;

            int ocupacion = 0;
            /// Calculamos "ocupación" antes del inicio del intervalo (sumamos todos los eventos antes de inicio)
            for (var e : evs.headMap(inicio, false).values()) {
                ocupacion += e;
            }


            int pico = ocupacion;
            /// Calculamos pico de ocupación durante el intervalo
            for (var delta : evs.subMap(inicio, true, fin, false).values()) {
                ocupacion += delta;
                if (ocupacion > pico) pico = ocupacion;
            }
            return pico;
        }

        public void reservar (String idAeropuerto, Instant inicio, Instant fin, int q){
            if (inicio.equals(fin)){
                //System.out.println("Inicio y fin iguales.");
                return;
            }

            TreeMap<Instant, Integer> evs = eventos.computeIfAbsent(idAeropuerto, k -> new TreeMap<>());
            evs.merge(inicio, q, Integer::sum);
            evs.merge(fin, -q, Integer::sum);
        }

    }

    /// Misma clase Ruta que en SSPGeneratorSeed
    static final class Ruta {
        final List<VueloFicha> legs;
        final Instant arriboFinal;
        Ruta(List<VueloFicha> legs) {
            this.legs = legs;
            this.arriboFinal = legs.get(legs.size()-1).id().getLlegadaUtc();
        }
    }

    private List<String> ordenarSedesAleatorio(int idPedido, int i, List<String> sedes) {
        List<String> sedesI = new ArrayList<>(sedes);
        long seed = Objects.hash(idPedido, i, 13_37);
        Collections.shuffle(sedesI, new Random(seed));
        return sedesI;
    }

    private List<Integer> ordenarHopsAleatorio(int idPedido, int i, int h_MAX) {
        List<Integer> hopsI = new ArrayList<>();
        for (int h = 0; h <= h_MAX; h++) hopsI.add(h);
        long seed = Objects.hash(idPedido, i, 9_001);
        Collections.shuffle(hopsI, new Random(seed));

        for (int a : hopsI){
            System.out.print(a + " - ");
        }

        System.out.println(" ");

        return hopsI;
    }

    /// Ahora recibe un List<String> de sedes
    private Ruta buscarRutaMinHops(IndexVuelos idx,
                                                    CargaPorVuelo carga,
                                                    List<String> sedes,
                                                    String dest,
                                                    Instant earliest,
                                                    Instant latest,
                                                    int hops,
                                   Map<VueloProgramadoId, Integer> deltaCargaLocal,
                                   OcupacionEnAlmacen ocupacionEnAlmacen,
                                   ALNS.Journal journal) {
        Ruta mejor = null;

        // Por cada sede, DFS acotado por #hops y tiempos
        for (String sede : sedes) {
            Ruta r = dfsRutas(idx, carga, sede, dest, earliest, latest, hops, new ArrayList<>(),
                    deltaCargaLocal, ocupacionEnAlmacen, journal);
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
                                           List<VueloFicha> path,
                          Map<VueloProgramadoId, Integer> deltaCargaLocal,
                          OcupacionEnAlmacen ocupacionEnAlmacen,
                          ALNS.Journal journal) {

        // salidas desde 'origen' a partir de 'earliest'
        List<VueloFicha> salidas = idx.porOrigen(origen);
        if (salidas.isEmpty()) return null;

        Ruta mejor = null;

        // Iterar por vuelos que respeten el tiempo
        for (VueloFicha f : salidas) {
            if (f.id().getSalidaUtc().isBefore(earliest)) continue;
            if (f.id().getLlegadaUtc().isAfter(latest)) continue;

            // poda por capacidad: si no hay residual, no sigas
            if (residualSim(carga, deltaCargaLocal, f.id()) <= 0) continue;

            // poda por holgura/estancia para aeropuerto de llegada: si no hay espacio para 1 unidad, no sigas
            if (!path.isEmpty()) {
                VueloFicha prev = path.get(path.size() - 1);
                String apEscala = prev.id().getDestino();
                Instant inicioEspera = prev.id().getLlegadaUtc();
                Instant finEspera = f.id().getSalidaUtc();

                // Sólo si hay espera real (inicio < fin) verificamos holgura
                if (inicioEspera.isBefore(finEspera)) {
                    int holgura = maxReservableSim(apEscala, inicioEspera, finEspera, ocupacionEnAlmacen, journal);

                    if (holgura < 1) continue; // NO hay espacio ni para 1 → podar rama
                }
            }

            path.add(f);

            if (f.id().getDestino().equals(dest)) {
                // alcanzamos el destino: ruta válida solo si hopsRestantes == 0
                if (hopsRestantes == 0) {
                    String apDestino = f.id().getDestino();
                    Instant arrUTC = f.id().getLlegadaUtc();
                    Instant wait = arrUTC.plus(Duration.ofHours(2));

                    int holgura = maxReservableSim(apDestino, arrUTC, wait, ocupacionEnAlmacen, journal);

                    if (holgura >= 1){
                        Ruta cand = new Ruta(new ArrayList<>(path));
                        if (mejor == null || cand.arriboFinal.isBefore(mejor.arriboFinal)) mejor = cand;
                    }
                }
            } else if (hopsRestantes > 0) {
                // extender desde el nuevo aeropuerto, earliest = llegada del vuelo actual
                Ruta rec = dfsRutas(idx, carga, f.id().getDestino(), dest, f.id().getLlegadaUtc(),
                        latest, hopsRestantes - 1, path, deltaCargaLocal, ocupacionEnAlmacen, journal);
                if (rec != null) {
                    if (mejor == null || rec.arriboFinal.isBefore(mejor.arriboFinal)) mejor = rec;
                }
            }

            path.remove(path.size() - 1);
        }

        return mejor;
    }

    private int residualSim(CargaPorVuelo carga, Map<VueloProgramadoId,Integer> delta, VueloProgramadoId id){
        return carga.residual(id) - delta.getOrDefault(id, 0);
    }

    private int maxReservableSim(String ap, Instant inicio, Instant fin,
                                 OcupacionEnAlmacen ocupacionEnAlmacen, ALNS.Journal journal){
        int picoLocal = ocupacionEnAlmacen.picoMaxIntervalo(ap, inicio, fin);
        int maxBase = journal.getOcc().maxReservable(ap, inicio, fin);

        int maxReservable = maxBase - picoLocal;

        /*
        if (maxReservable < 0){
            System.out.println("Cuidado:");
        }
        */

        return Math.max(maxReservable, 0);
    }

    private int capacidadVuelosSim(Ruta ruta, CargaPorVuelo cargaPorVuelo, Map<VueloProgramadoId, Integer> deltaCargaLocal){
        int qMin = Integer.MAX_VALUE;
        for (VueloFicha f : ruta.legs){
            int q = residualSim(cargaPorVuelo, deltaCargaLocal, f.id());
            if (q <= 0) return 0;
            if (q < qMin) qMin = q;
        }

        //Double check porseaca
        return (qMin == Integer.MAX_VALUE) ? 0 : qMin;
    }

    private int capacidadAeropuertoSim(Ruta ruta, OcupacionEnAlmacen ocupacionEnAlmacen, ALNS.Journal journal){
        int qMin = Integer.MAX_VALUE;

        List<VueloFicha> legs = ruta.legs;

        /// 1. Primero, el 'q' de las reservas entre tramos
        for (int i = 0; i < legs.size() - 1; i++){
            var a = legs.get(i);
            var b = legs.get(i + 1);
            Instant ini = a.id().getLlegadaUtc();
            Instant fin = b.id().getSalidaUtc();
            String destino = a.id().getDestino();

            //En caso no hay espera real
            if (!ini.isBefore(fin)) continue;

            int q = maxReservableSim(destino, ini, fin, ocupacionEnAlmacen, journal);
            if (q <= 0) return 0;
            if (q < qMin) qMin = q;
        }

        /// 2. Segundo, el 'q' de las 2h en el destino final
        var last = legs.get(legs.size() - 1);
        Instant arr = last.id().getLlegadaUtc();
        Instant fin2h = arr.plus(Duration.ofHours(2));
        String destino = last.id().getDestino();
        int qDest = maxReservableSim(destino, arr, fin2h, ocupacionEnAlmacen, journal);
        if (qDest <= 0) return 0;
        if (qDest < qMin) qMin = qDest;

        return (qMin == Integer.MAX_VALUE) ? 0 : qMin;
    }

    /// Diferente al de SSPGeneratorSeed
    private void reservarOcupacionesRuta(Ruta ruta, OcupacionEnAlmacen ocupacionEnAlmacen, int q){
        List<VueloFicha> legs = ruta.legs;

        /// 1. Primero, reservamos 'q' en las escalas
        for (int i = 0; i < legs.size() - 1; i++){
            var a = legs.get(i);
            var b = legs.get(i + 1);
            Instant ini = a.id().getLlegadaUtc();
            Instant fin = b.id().getLlegadaUtc();
            String destino = a.id().getDestino();

            ocupacionEnAlmacen.reservar(destino, ini, fin, q);
        }

        /// 2. Segundo, el 'q' de las 2h en el destino final
        var last = legs.get(legs.size() - 1);
        Instant arr = last.id().getLlegadaUtc();
        Instant fin2h = arr.plus(Duration.ofHours(2));
        String destino = last.id().getDestino();

        ocupacionEnAlmacen.reservar(destino, arr, fin2h, q);
    }

    private List<TramoAsignado> reservarOcupacionesVuelos(Ruta ruta, Map<VueloProgramadoId, Integer> deltaCargaLocal, int q){
        List<VueloFicha> legs = ruta.legs;
        List<TramoAsignado> tramosRuta = new ArrayList<>(ruta.legs.size());

        for (VueloFicha f : legs){
            /// 1. Agregamos la ocupacion al local
            deltaCargaLocal.merge(f.id(), q, Integer::sum);

            /// 2. Creamos el tramo y lo asignamos
            tramosRuta.add(new TramoAsignado(f.id(), q, f.id().getLlegadaUtc()));
        }

        return tramosRuta;
    }

    /// Esto es del K-Regret:
    ///
    ///

    private PlanPedido obtenerPlanElegido(List<PlanPedido> planesCandidatos, CargaPorVuelo cargaPorVuelo){

        if (planesCandidatos == null || planesCandidatos.isEmpty()) return null;

        List<Double> costos = planesCandidatos.stream()
                .map(p -> costoPlan(p, cargaPorVuelo, slaLlegadaMax))
                .toList();

        //Calculamos el regret-K
        double rg = regretK(costos, k);

        //Elegimos aquel de menor costo
        int iBest = IntStream.range(0, costos.size())
                .boxed()
                .min(Comparator.comparingDouble(costos::get))
                .orElse(0);

        PlanPedido planElegido = planesCandidatos.get(iBest);

        return planElegido;
    }

    private double costoPlan(PlanPedido plan, CargaPorVuelo carga, Duration slaLlegadaMax){

        /// 1) Agregamos 'q' por vuelo dentro del plan
        Map<VueloProgramadoId, Integer> qPorVuelo = new HashMap<>();
        for (RutaAsignada r : plan.getRutas()){
            for (TramoAsignado t : r.getTramos()){
                qPorVuelo.merge(t.getVuelo(), t.getCantidad(), Integer::sum);
            }
        }

        double costo = 0;

        /// 2) Consideramos hops y presión de la capacidad
        for (RutaAsignada r : plan.getRutas()){
            int hops = Math.max(0, r.getTramos().size() - 1);

            costo += L_HOPS * hops * r.getCantidad();

            for (TramoAsignado t: r.getTramos()){
                int residualAfter = carga.residual(t.getVuelo()) - qPorVuelo.getOrDefault(t.getVuelo(), 0);
                double tensionCap = 1.0 / (1 + Math.max(0, residualAfter));
                costo += L_CAP * tensionCap * t.getCantidad();
            }

        }

        /// 3) Consideramos buffer al SLA (VAMOS A TENER QUE HACER REFACTOR, SLA NO PUEDE SER FIJO)
        Instant creado = plan.getCreadoUtc();
        Instant deadline = creado.plus(slaLlegadaMax);
        Instant llegadaMax = plan.getRutas().stream()
                .map(RutaAsignada::ultimaLlegada)
                .max(Comparator.naturalOrder())
                .orElse(creado);
        long bufferHoras = Math.max(0, Duration.between(llegadaMax, deadline).toHours());
        double penBuffer = Math.max(0, 6 - bufferHoras);
        costo += L_BUFFER * penBuffer;

        return costo;
    }

    private double regretK(List<Double> costos, int k){
        if (costos.isEmpty()) return 0;
        List<Double> xs = new ArrayList<>(costos);
        Collections.sort(xs);

        double c1 = xs.get(0);
        double sum = 0;

        for (int i = 1; i < Math.min(k, xs.size()); i++) sum += (xs.get(i) - c1);

        return sum;
    }

    private void aplicarPlanEnGlobal(PlanPedido planElegido, SolucionProgramacion s, ALNS.Journal journal){

        /// 1) Reservas globales (almacenes y vuelos)
        for (RutaAsignada r : planElegido.getRutas()){
            List<TramoAsignado> tramos = r.getTramos();
            int qRuta = r.getCantidad();

            // (A) ESCALAS: [llegada, salida_siguiente)
            for (int j = 0; j < tramos.size() - 1; j++) {
                TramoAsignado tPrev = tramos.get(j);
                TramoAsignado tNext = tramos.get(j + 1);
                Instant ini = tPrev.getLlegadaUtc();
                Instant fin = tNext.getVuelo().getSalidaUtc();
                if (ini.isBefore(fin)) {
                    journal.getOcc().reservar(tPrev.getVuelo().getDestino(), ini, fin, qRuta);
                }
            }

            // (B) +2h en destino final
            TramoAsignado last = tramos.get(tramos.size() - 1);
            Instant arr = last.getLlegadaUtc();
            journal.getOcc().reservar(last.getVuelo().getDestino(), arr, arr.plus(Duration.ofHours(2)), qRuta);

            // (C) Asignación global en vuelos
            for (TramoAsignado t : tramos) {
                s.getCargaPorVuelo().asignar(t.getVuelo(), t.getCantidad()); // usa la cantidad del tramo
            }

        }

        /// 2) Actualizar el PlanPedido de la solución
        s.getPlanPorPedido().put(planElegido.getIdPedido(), planElegido);
    }
    

}
