package pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.operators;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.alns.ALNS;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.model.*;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.service.IndexVuelos;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.service.VueloFicha;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

/**
 * RegretRepair paralelizado:
 * - Procesa planes en paralelo.
 * - Procesa rutas dentro de cada plan en paralelo.
 */
public class RegretRepair implements RepairOperator {

    private final int k;
    private final VuelosTEG teg;
    private final List<String> sedes;

    public RegretRepair(int k, List<String> sedes, VuelosTEG teg) {
        this.k = k;
        this.sedes = sedes;
        this.teg = teg;
    }

    @Override
    public void repair(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {

        //System.out.println("Repairing RegretRepair " + presenteUTC);

        List<PlanPedido> planos = new ArrayList<>(s.getPlanPorPedido().values());
        CargaPorVuelo cargaPorVuelo = s.getCargaPorVuelo();

        //Ya NO PARALELIZAMOS el procesamiento
        //Se eliminó la paralelización porque la reparación de rutas modifica estructuras compartidas (CargaPorVuelo, OcupacionPorAeropuerto) que deben mantenerse coherentes.
        // Procesarlas en paralelo podría generar inconsistencias o condiciones de carrera al reservar capacidad simultáneamente en los mismos vuelos o aeropuertos.


        for (PlanPedido plan : planos) {
            if (plan.getRutas() == null || plan.getRutas().isEmpty()) {

                //System.out.println("Vamos a intentar reconstruir el pedido id:" + plan.getIdPedido());

                if (plan.getIdPedido() == 3){
                    int x = 0;
                }

                List<List<RutaAsignada>> candidatos = new ArrayList<>();
                for (String sede : sedes) {
                    List<RutaAsignada> rutas = DijkstraMultiRutas(sede, plan, presenteUTC, cargaPorVuelo, journal);
                    if (rutas != null && !rutas.isEmpty()) candidatos.add(rutas);
                }

                if (candidatos.isEmpty()) {
                    //No se pudo reparar la ruta
                    continue;
                }

                //Elegimos a la mejor ruta con el comparador
                candidatos.sort(Comparator.comparingDouble(ruta -> newCostoRuta(ruta, cargaPorVuelo)));
                List<RutaAsignada> elegida = candidatos.get(0);

                /// Aca iría el helper para convertir a tramos. Pero ya no lo hacemos

                //Ahora que tenemos esta ruta, tenemos que consumir los recursos. Para ello, escribimos en el journal

                /*
                ///  Por ahora consideramos que asignamos todo0 a un vuelo. Por eso usamos la demanda.
                //int q = plan.getDemanda();
                 */

                /// Ya no asignamos todo0 a un solo vuelo. Ahora usamos la cantidad de cada RutaAsignada

                //1) Primero reservamos las esperas en todas las escalas
                for (RutaAsignada ruta : elegida) {
                    List<TramoAsignado> tramoAsignados = ruta.getTramos();
                    int q = ruta.getCantidad();

                    // (A) ORIGEN: [creado o llegada_prev, salida)
                    for (int j = 0; j < tramoAsignados.size(); j++) {
                        TramoAsignado t = tramoAsignados.get(j);
                        VueloProgramadoId v = t.getVuelo();
                        Instant iniOri = (j == 0) ? plan.getCreadoUtc() : tramoAsignados.get(j - 1).getVuelo().getLlegadaUtc();
                        Instant finOri = v.getSalidaUtc();
                        if (iniOri != null && finOri != null && !finOri.isBefore(iniOri)) {
                            journal.reservar(v.getOrigen(), iniOri, finOri, q);
                        }
                    }

                    // (B) ESCALAS: [llegada, salida_siguiente)
                    for (int j = 0; j < tramoAsignados.size() - 1; j++) {
                        TramoAsignado tramoPrev = tramoAsignados.get(j);
                        TramoAsignado tramoNext = tramoAsignados.get(j+1);
                        Instant arrPrev = tramoPrev.getVuelo().getLlegadaUtc();
                        Instant depNext = tramoNext.getVuelo().getSalidaUtc();
                        journal.reservar(tramoPrev.getVuelo().getDestino(), arrPrev, depNext, q);
                    }

                    // (C) +2h FINAL
                    Instant llegadaFinal = tramoAsignados.get(tramoAsignados.size() - 1).getLlegadaUtc();
                    journal.reservar(plan.getAeropuertoDestino(), llegadaFinal, llegadaFinal.plus(Duration.ofHours(2)), q);

                    // (D) CARGA EN VUELOS
                    for (TramoAsignado t : tramoAsignados) {
                        s.getCargaPorVuelo().asignar(t.getVuelo(), q);
                    }
                }

                //Construimos el plan y posteriormente actualizamos la solución
                PlanPedido nuevoPlan = PlanPedido.builder()
                        .idPedido(plan.getIdPedido())
                        .aeropuertoDestino(plan.getAeropuertoDestino())
                        .creadoUtc(plan.getCreadoUtc())
                        .demanda(plan.getDemanda())
                        .rutas(elegida)
                        .build();

                s.getPlanPorPedido().put(nuevoPlan.getIdPedido(), nuevoPlan);

                //System.out.println("Se reconstruyó el pedido id:" + plan.getIdPedido());
            }
        }

        /// Antes literalmente solo reparabas los planes de pedido que tenían rutas, y los que no tenían eran ignorados/////////////////////
        /// lo cual no tiene sentido obviamente. Además, dado que ahora tenemos que validar ocupaciones de vuelos y de /////////////////////
        /// almacenes, no podemos procesar todo0 en paralelo, ya que puede conllevar a un race condition/////////////////////
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
            TreeMap<Instant, Integer> evs = eventos.computeIfAbsent(idAeropuerto, k -> new TreeMap<>());
            evs.merge(inicio, q, Integer::sum);
            evs.merge(fin, -q, Integer::sum);
        }

    }

    private List<RutaAsignada> DijkstraMultiRutas(String origen, PlanPedido plan, Instant presenteUTC, CargaPorVuelo cargaPorVuelo, ALNS.Journal journal){
        List<RutaAsignada> rutas = new ArrayList<>();
        int rem = plan.getDemanda();

        //Es para mapear las ocupaciones temporales de lo que devuelve el dijkstra (sino siempre verá espacio el mismo
        // espacio en todos los vuelos/almacenes).
        Map<VueloProgramadoId, Integer> ocupacionEnVuelo = new HashMap<>();
        OcupacionEnAlmacen ocupacionEnAlmacen = new OcupacionEnAlmacen();

        /// Variable para depurar (no hace nada)
        int r;

        while (rem > 0){
            List<VueloProgramadoId> rutaActual = newDijkstraRuta(origen, plan, presenteUTC, cargaPorVuelo, journal, ocupacionEnVuelo, ocupacionEnAlmacen);

            if (rutaActual == null || rutaActual.isEmpty()) break;
            if (plan.getIdPedido() == 3)
                r = 0;


            int capacidadRuta = calcularCuelloDeBotella(rutaActual, cargaPorVuelo, journal, ocupacionEnVuelo, ocupacionEnAlmacen);
            int qAsignable = Math.min(rem, capacidadRuta);

            if (qAsignable <= 0) break;

            ///Tenemos que transformar a tramos y llenamos ocupacionEnVuelo (para que el Dijkstra sepa)

            List<TramoAsignado> tramos = new ArrayList<>();
            for (VueloProgramadoId idProg : rutaActual) {
                TramoAsignado tramo = new TramoAsignado(idProg, qAsignable, idProg.getLlegadaUtc());;
                tramos.add(tramo);

                ocupacionEnVuelo.merge(idProg, qAsignable, Integer::sum);
            }

            /// Ahora llenamos ocupacionEnAlmacen y el +2h (para que el Dijkstra sepa)
            for (int i = 0; i < rutaActual.size() - 1; i++) {
                var a = rutaActual.get(i);
                var b = rutaActual.get(i + 1);
                String destino = a.getDestino();
                Instant ini = a.getLlegadaUtc();
                Instant fin = b.getSalidaUtc();

                ocupacionEnAlmacen.reservar(destino, ini, fin, qAsignable);
            }
            VueloProgramadoId ultimo = rutaActual.get(rutaActual.size() - 1);
            ocupacionEnAlmacen.reservar(ultimo.getDestino(), ultimo.getLlegadaUtc(), ultimo.getLlegadaUtc().plus(Duration.ofHours(2)), qAsignable);

            rutas.add(new RutaAsignada(qAsignable, tramos));

            rem -= qAsignable;
        }

        return rutas;
    }

    private int calcularCuelloDeBotella (List<VueloProgramadoId> rutaActual, CargaPorVuelo cargaPorVuelo, ALNS.Journal journal,
                                         Map<VueloProgramadoId, Integer> ocupacionVuelo, OcupacionEnAlmacen ocupacionEnAlmacen){
        int qEfectivo = Integer.MAX_VALUE;

        //1. Verificamos carga de los vuelos
        for (VueloProgramadoId idProg : rutaActual) {
            int q = cargaPorVuelo.residual(idProg) - ocupacionVuelo.getOrDefault(idProg, 0);;
            qEfectivo = Math.min(q, qEfectivo);
        }

        //2. Verificamos espacio en los aeropuertos
        for (int i = 0; i < rutaActual.size() - 1; i++) {
            var a = rutaActual.get(i);
            var b = rutaActual.get(i + 1);
            String destino = a.getDestino();
            Instant ini = a.getLlegadaUtc();
            Instant fin = b.getSalidaUtc();

            int picoMaxPrev = ocupacionEnAlmacen.picoMaxIntervalo(destino, ini, fin);
            int q = journal.getOcc().maxReservable(destino, ini, fin) - picoMaxPrev;
            qEfectivo = Math.min(q, qEfectivo);
        }

        //3. Verificamos estancia de 2 horas en el aeropuerto destino
        VueloProgramadoId ultimo = rutaActual.get(rutaActual.size() - 1);
        int picoMaxPrev = ocupacionEnAlmacen.picoMaxIntervalo(ultimo.getDestino(), ultimo.getLlegadaUtc(), ultimo.getLlegadaUtc().plus(Duration.ofHours(2)));
        int q = journal.getOcc().maxReservable(ultimo.getDestino(), ultimo.getLlegadaUtc(), ultimo.getLlegadaUtc().plus(Duration.ofHours(2))) - picoMaxPrev;
        qEfectivo = Math.min(q, qEfectivo);

        return qEfectivo;
    }

    private List<VueloProgramadoId> newDijkstraRuta(String origen, PlanPedido plan, Instant presenteUTC, CargaPorVuelo cargaPorVuelo, ALNS.Journal journal,
                                                    Map<VueloProgramadoId, Integer> ocupacionVuelo, OcupacionEnAlmacen ocupacionEnAlmacen){
        String destino = plan.getAeropuertoDestino();
        IndexVuelos idx = new IndexVuelos(teg);

        Map<String, Instant> dist = new HashMap<>();
        Map<String, VueloProgramadoId> previo = new HashMap<>();
        PriorityQueue<String> pq = new PriorityQueue<>(Comparator.comparing(dist::get));

        for (String nodo : teg.getVuelosPorOrigen().keySet()){
            dist.put(nodo, Instant.MAX);
        }
        dist.put(origen, presenteUTC);
        pq.add(origen);

        boolean encontrado = false;

        while (!pq.isEmpty()){
            String actual = pq.poll();
            Instant llegadaActual = dist.getOrDefault(actual, Instant.MAX);
            if (llegadaActual.equals(Instant.MAX)) continue;
            //if (actual.equals(destino)) break;

            if (actual.equals(destino)){

                /// newDijkstraRuta!!!!!
                if (plan.getIdPedido() == 3){
                    int r = 3;
                }

                if (origen.equals("UBBB") && plan.getIdPedido() == 3){
                    int r = 3;
                }

                Instant arrPrev = llegadaActual;

                int picoEscalaPrev = ocupacionEnAlmacen.picoMaxIntervalo(destino, arrPrev, arrPrev.plus(Duration.ofHours(2)));
                int q2Horas = journal.getOcc().maxReservable(actual, arrPrev, arrPrev.plus(Duration.ofHours(2)))
                        - picoEscalaPrev;
                if (q2Horas >= 1){ //Encontramos una ruta válida para mínimo 1 unidad.
                    encontrado = true;
                    break;
                }
                else {
                    continue;
                }
            }

            List<VueloFicha> salidas = idx.porOrigen(actual);
            if (salidas == null || salidas.isEmpty()) continue;

            for (VueloFicha vf : salidas){
                Instant salidaUTC = vf.id().getSalidaUtc();
                Instant llegadaUTC = vf.id().getLlegadaUtc();

                //Como estamos usando Instants generados antes y anclados a una fecha y hora, ya no nos preocupamos por sumar +1 día
                if (salidaUTC.isBefore(llegadaActual)) continue;

                VueloProgramadoId idProg = vf.id();
                int residualVuelo = cargaPorVuelo.residual(idProg) - ocupacionVuelo.getOrDefault(idProg, 0);

                /// Como generamos varias rutas, ahora no comparamos por la demanda, sino por la mínima ocupación (1)
                if (residualVuelo < 1) continue;

                Instant arrPrev = llegadaActual;
                Instant depNext = idProg.getSalidaUtc();

                int picoMaxPrev = ocupacionEnAlmacen.picoMaxIntervalo(idProg.getOrigen(), arrPrev, depNext);

                int residualEscala = journal.getOcc().maxReservable(idProg.getOrigen(), arrPrev, depNext)
                        - picoMaxPrev;
                if (residualEscala < 1) continue;

                /// Si llegamos a este punto, la ruta parcial es válida.

                String apDestino = idProg.getDestino();
                Instant mejorDestino = dist.getOrDefault(apDestino, Instant.MAX);

                if (llegadaUTC.isBefore(mejorDestino)) {
                    dist.put(apDestino, llegadaUTC);
                    previo.put(apDestino, idProg);
                    pq.add(apDestino);

                    // ⚠️ evita volver al origen vía relajación
                    // if (apDestino.equals(origen)) continue;
                }
            }
        }

        if (plan.getIdPedido() == 3){
            int r = 3;
        }

        if (!previo.containsKey(destino)) return null;
        if (!encontrado) return null;
        //if (!dist.containsKey(destino) || Double.isInfinite(dist.get(destino))) return null;

        List<VueloProgramadoId> vuelosProgramadosId = new ArrayList<>();
        String nodo = destino;

        while (previo.containsKey(nodo)){
            VueloProgramadoId idProg = previo.get(nodo);
            vuelosProgramadosId.add(idProg);
            nodo = idProg.getOrigen();
        }
        Collections.reverse(vuelosProgramadosId);
        if (vuelosProgramadosId.isEmpty()) return null;

        return vuelosProgramadosId;
    }

    private double newCostoRuta(List<RutaAsignada> rutas, CargaPorVuelo cargaPorVuelo){
        double total = 0.0;

        for (RutaAsignada r : rutas) {
            for (TramoAsignado tramo : r.getTramos()) {
                VueloProgramadoId v = tramo.getVuelo();

                int capacidad = cargaPorVuelo.capacidad(v);
                double costo = v.getCostoCapacidad(capacidad);

                double ajuste = v.getLlegadaUtc().atZone(ZoneOffset.UTC).toLocalTime().toSecondOfDay() * 0.001;

                total += costo + ajuste;
            }
        }

        return total;
    }

}
