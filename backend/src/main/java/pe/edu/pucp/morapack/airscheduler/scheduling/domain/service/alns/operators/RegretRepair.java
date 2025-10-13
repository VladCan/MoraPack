package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.Vuelo;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.*;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.IndexVuelos;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.VueloFicha;
import pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.ALNS;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

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


        int x=0;
        //System.out.println("Holi:" + x);

        for (PlanPedido plan : planos) {
            if (plan.getRutas() == null || plan.getRutas().isEmpty()) {

                List<List<RutaAsignada>> candidatos = new ArrayList<>();
                for (String sede : sedes) {
                    List<RutaAsignada> rutas = newDijkstraRuta(sede, plan, presenteUTC, cargaPorVuelo, journal);
                    if (rutas != null && !rutas.isEmpty()) candidatos.add(rutas);
                }
                //System.out.println("Salí del dijkstra:" + x);

                if (candidatos.isEmpty()) {
                    //No se pudo reparar la ruta
                    continue;
                }

                //Elegimos a la mejor ruta con el comparador
                candidatos.sort(Comparator.comparingDouble(ruta -> newCostoRuta(ruta, cargaPorVuelo)));
                List<RutaAsignada> elegida = candidatos.get(0);

                /// Aca iría el helper para convertir a tramos. Pero ya no lo hacemos

                //Ahora que tenemos esta ruta, tenemos que consumir los recursos. Para ello, escribimos en el journal

                ///  Por ahora consideramos que asignamos todo0 a un vuelo. Por eso usamos la demanda.
                int q = plan.getDemanda();

                //1) Primero reservamos las esperas en todas las escalas
                for (RutaAsignada ruta : elegida) {
                    List<TramoAsignado> tramoAsignados = ruta.getTramos();

                    //1) Primero reservamos las esperas en todas las escalas
                    for (int j = 0; j < tramoAsignados.size() - 1; j++) {
                        TramoAsignado tramoPrev = tramoAsignados.get(j);
                        TramoAsignado tramoNext = tramoAsignados.get(j+1);

                        Instant arrPrev = tramoPrev.getVuelo().getLlegadaUtc();
                        Instant depNext = tramoNext.getVuelo().getSalidaUtc();

                        journal.reservar(tramoPrev.getVuelo().getDestino(), arrPrev, depNext, q);
                    }

                    //2) Reservamos las 2h de ocupación final
                    Instant llegadaFinal = tramoAsignados.get(tramoAsignados.size() - 1).getLlegadaUtc();
                    journal.reservar(plan.getAeropuertoDestino(), llegadaFinal, llegadaFinal.plus(Duration.ofHours(2)), q);

                    // 3) Asignamos carga a los vuelos en la solución
                    for (TramoAsignado t : tramoAsignados) {
                        ///%%%%%Failing aquí
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


                /*
                /// //////////////////////////////////

                List<List<Vuelo>> candidatos = new ArrayList<>();
                for (String sede : sedes) {
                    List<Vuelo> camino = dijkstraRuta(sede, plan, presenteUTC, cargaPorVuelo, journal);
                    if (camino != null && !camino.isEmpty()) candidatos.add(camino);
                }
                if (candidatos.isEmpty()) {
                    //No se pudo reparar la ruta
                    continue;
                }

                //Elegimos a la mejor ruta
                candidatos.sort(Comparator.comparingDouble(this::costoRuta));
                List<Vuelo> elegida = candidatos.get(0);

                //Usamos el helper para convertir a tramos
                List<TramoAsignado> tramos = elegida.stream()
                        .map(v -> vueloToTramoAsignado(v, plan.getDemanda(), presenteUTC))
                        .collect(Collectors.toList());

                //Ahora que tenemos esta ruta, tenemos que consumir los recursos. Para ello, escribimos en el journal

                int q = plan.getDemanda();

                //1) Primero reservamos las esperas en todas las escalas
                for (int i = 0; i < elegida.size() - 1; i++) {
                    Vuelo vPrev = elegida.get(i);
                    Vuelo vNext = elegida.get(i + 1);

                    Instant arrPrev = vPrev.getHoraGMTDestino()
                            .atDate(plan.getCreadoUtc().atZone(ZoneOffset.UTC).toLocalDate())
                            .toInstant(ZoneOffset.UTC);
                    Instant depNext = vNext.getHoraGMTOrigen()
                            .atDate(plan.getCreadoUtc().atZone(ZoneOffset.UTC).toLocalDate())
                            .toInstant(ZoneOffset.UTC);

                    journal.reservar(vPrev.getDestino(), arrPrev, depNext, q);
                }

                // 2) Reservamos 2h en destino final
                Instant llegadaFinal = tramos.get(tramos.size() - 1).getLlegadaUtc();
                journal.reservar(plan.getAeropuertoDestino(), llegadaFinal, llegadaFinal.plus(Duration.ofHours(2)), q);

                // 3) Asignamos carga a los vuelos en la solución
                for (TramoAsignado t : tramos) {
                    ///%%%%%Failing aquí
                    s.getCargaPorVuelo().asignar(t.getVuelo(), q);
                }

                //Construimos el plan y posteriormente actualizamos la solución
                PlanPedido nuevoPlan = PlanPedido.builder()
                        .idPedido(plan.getIdPedido())
                        .aeropuertoDestino(plan.getAeropuertoDestino())
                        .creadoUtc(plan.getCreadoUtc())
                        .demanda(plan.getDemanda())
                        .rutas(List.of(new RutaAsignada(plan.getDemanda(), tramos)))
                        .build();

                s.getPlanPorPedido().put(nuevoPlan.getIdPedido(), nuevoPlan);*/
            }
            x++;
        }

        /// Antes literalmente solo reparabas los planes de pedido que tenían rutas, y los que no tenían eran ignorados/////////////////////
        /// lo cual no tiene sentido obviamente. Además, dado que ahora tenemos que validar ocupaciones de vuelos y de /////////////////////
        /// almacenes, no podemos procesar todo0 en paralelo, ya que puede conllevar a un race condition/////////////////////
        /// /////////////////////
        /// /////////////////////

    }

    private List<RutaAsignada> newDijkstraRuta(String origen, PlanPedido plan, Instant presenteUTC, CargaPorVuelo cargaPorVuelo, ALNS.Journal journal){
        String destino = plan.getAeropuertoDestino();
        int demanda = plan.getDemanda();

        IndexVuelos idx = new IndexVuelos(teg);

        Map<String, Instant> dist = new HashMap<>();
        Map<String, VueloProgramadoId> previo = new HashMap<>();
        PriorityQueue<String> pq = new PriorityQueue<>(Comparator.comparing(dist::get));

        for (String nodo : teg.getVuelosPorOrigen().keySet()){
            dist.put(nodo, Instant.MAX);
        }
        dist.put(origen, presenteUTC);
        pq.add(origen);

        while (!pq.isEmpty()){
            String actual = pq.poll();
            Instant llegadaActual = dist.getOrDefault(actual, Instant.MAX);
            if (llegadaActual.equals(Instant.MAX)) continue;
            if (actual.equals(destino)) break;

            List<VueloFicha> salidas = idx.porOrigen(actual);
            if (salidas == null || salidas.isEmpty()) continue;

            for (VueloFicha vf : salidas){
                Instant salidaUTC = vf.id().getSalidaUtc();
                Instant llegadaUTC = vf.id().getLlegadaUtc();

                //Como estamos usando Instants generados antes y anclados a una fecha y hora, ya no nos preocupamos por sumar +1 día

                if (salidaUTC.isBefore(llegadaActual)) continue;

                VueloProgramadoId idProg = vf.id();
                int residual = cargaPorVuelo.residual(idProg);

                //La demanda no entra en el espacio disponible del vuelo
                if (residual < demanda) continue;

                int capacidad = cargaPorVuelo.capacidad(idProg);

                //Colocamos una nueva función de costo por TIEMPO
                /// Considerar que puede no haber desempate

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

        if (!previo.containsKey(destino)) return null;

        //if (!dist.containsKey(destino) || Double.isInfinite(dist.get(destino))) return null;

        List<VueloProgramadoId> vuelosProgramadosId = new ArrayList<>();
        String nodo = destino;

        int x=0;
        while (previo.containsKey(nodo)){
            VueloProgramadoId idProg = previo.get(nodo);
            vuelosProgramadosId.add(idProg);
            nodo = idProg.getOrigen();
            //System.out.println("x:" +  x);
            x++;
        }
        Collections.reverse(vuelosProgramadosId);
        if (vuelosProgramadosId.isEmpty()) return null;

        // 1) Validamos las ocupaciones en las escalas

        int qOccEscalas = Integer.MAX_VALUE;

        for (int i = 0; i < vuelosProgramadosId.size() - 1; i++) {
            VueloProgramadoId vPrev = vuelosProgramadosId.get(i);
            VueloProgramadoId vNext = vuelosProgramadosId.get(i + 1);

            String apEscala = vPrev.getDestino();

            // Llegada del vuelo i
            Instant arrPrev = vPrev.getLlegadaUtc();

            // Salida del vuelo i+1
            Instant depNext = vNext.getSalidaUtc();

            if (!depNext.isAfter(arrPrev)) {
                return null; // conexión inválida (sin tiempo o en el pasado)
            }

            int qEscala = journal.getOcc().maxReservable(apEscala, arrPrev, depNext);
            if (qEscala <= 0) {
                return null;
            }
            qOccEscalas = Math.min(qOccEscalas, qEscala);
        }

        if (qOccEscalas < demanda){
            return null;
        }

        // 2) Validamos las 2h de ocupación en el destino.
        VueloProgramadoId ultimo = vuelosProgramadosId.get(vuelosProgramadosId.size() - 1);
        Instant llegadaFinal = ultimo.getLlegadaUtc();

        int qOccDest = journal.getOcc().maxReservable(destino, llegadaFinal, llegadaFinal.plus(Duration.ofHours(2)));
        if (qOccDest < demanda) {
            return null; // no hay espacio suficiente para toda la demanda del plan
        }

        //Si llegamos a este punto, la ruta es totalmente válida. Vamos a construir
        //un objeto List<RutaAsignada>

        List<TramoAsignado> tramos = new ArrayList<>();
        for (VueloProgramadoId idProg : vuelosProgramadosId) {
            TramoAsignado tramo = new TramoAsignado(idProg, demanda, idProg.getLlegadaUtc());;
            tramos.add(tramo);
        }

        RutaAsignada ruta = new RutaAsignada(demanda, tramos);

        return List.of(ruta);
    }

    private List<Vuelo> dijkstraRuta(String origen, PlanPedido plan, Instant presenteUTC, CargaPorVuelo cargaPorVuelo, ALNS.Journal journal) {
        String destino = plan.getAeropuertoDestino();
        int demanda = plan.getDemanda();

        Map<String, Double> dist = new HashMap<>();
        Map<String, Vuelo> previo = new HashMap<>();
        PriorityQueue<String> pq = new PriorityQueue<>(Comparator.comparingDouble(n -> dist.getOrDefault(n, Double.POSITIVE_INFINITY)));

        for (String nodo : teg.getVuelosPorOrigen().keySet()) {
            dist.put(nodo, Double.POSITIVE_INFINITY);
        }
        dist.put(origen, 0.0);
        pq.add(origen);

        while (!pq.isEmpty()) {

            String actual = pq.poll();
            if (Double.isInfinite(dist.getOrDefault(actual, Double.POSITIVE_INFINITY))) continue;
            if (actual.equals(destino)) break;

            List<Vuelo> salidas = teg.getVuelosPorOrigen().get(actual);
            if (salidas == null) continue;

            for (Vuelo v : salidas) {

                //Convertimos el HH-MM de los vuelos a HH-MM DD/MM/AA (no es exactamente ese formato, pero se entiende la idea de lo que hacemos)
                Instant salidaUtc = v.getHoraGMTOrigen() .atDate(presenteUTC.atZone(ZoneOffset.UTC).toLocalDate()) .toInstant(ZoneOffset.UTC);
                Instant llegadaUtc = v.getHoraGMTDestino() .atDate(presenteUTC.atZone(ZoneOffset.UTC).toLocalDate()) .toInstant(ZoneOffset.UTC);

                //Vuelo salió ayer y llega hoy, sumamos 1.
                if (!llegadaUtc.isAfter(salidaUtc)) {
                    llegadaUtc = llegadaUtc.plus(1, ChronoUnit.DAYS);
                }

                //El vuelo ya salió, lo ignoramos
                if (salidaUtc.isBefore(presenteUTC)) continue;

                //Creamos un objeto de VueloProgramadoId para obtener el residual de la capacidad del vuelo
                VueloProgramadoId idProg = new VueloProgramadoId(v.getOrigen(), v.getDestino(), salidaUtc, llegadaUtc);
                int residual = cargaPorVuelo.residual(idProg);

                //La demanda no entra en el espacio disponible del vuelo
                if (residual < demanda) continue;

                //Antes solo validabamos la ocupación del vuelo (lo que esta abajo)... estaba mal.
                //if (v.getCapacidad() < demanda) continue;

                double peso = v.getCosto() + v.getHoraGMTDestino().toSecondOfDay() * 0.001;
                double nuevoDist = dist.getOrDefault(actual, Double.POSITIVE_INFINITY) + peso;

                if (nuevoDist < dist.getOrDefault(v.getDestino(), Double.POSITIVE_INFINITY)) {
                    dist.put(v.getDestino(), nuevoDist);
                    previo.put(v.getDestino(), v);
                    pq.add(v.getDestino());
                }
            }
        }

        if (!previo.containsKey(destino)) return null;

        List<Vuelo> ruta = new ArrayList<>();
        String nodo = destino;
        while (previo.containsKey(nodo)) {
            Vuelo v = previo.get(nodo);
            ruta.add(v);
            nodo = v.getOrigen();
        }
        Collections.reverse(ruta);
        if (ruta.isEmpty()) return null;

        //Con la ruta armada, vamos a pasar a las validaciones finales 1) y 2)

        if (plan.getIdPedido() == 3){
            System.out.println("");
        }


        // 1) Validamos las ocupaciones en las escalas
        int qOccEscalas = Integer.MAX_VALUE;

        // “cursor” lleva la última llegada ajustada (para encadenar días correctamente)
        // A diferencia de las otras veces donde solo sumamos +1 al dia si hay desfase, no hemos contemplado
        // el caso en el que se cruce más de 1 vez la medianoche (lo cual sí podría pasar, porque el SLA máx
        // es de 3 días)
        Instant cursor = null;
        //Lo dejaremos en un TODO

        for (int i = 0; i < ruta.size() - 1; i++) {
            Vuelo vPrev = ruta.get(i);
            Vuelo vNext = ruta.get(i + 1);

            String apEscala = vPrev.getDestino();

            // Llegada del vuelo i
            Instant arrPrev = vPrev.getHoraGMTDestino()
                    .atDate(plan.getCreadoUtc().atZone(ZoneOffset.UTC).toLocalDate())
                    .toInstant(ZoneOffset.UTC);

            // Salida del vuelo i+1
            Instant depNext = vNext.getHoraGMTOrigen()
                    .atDate(plan.getCreadoUtc().atZone(ZoneOffset.UTC).toLocalDate())
                    .toInstant(ZoneOffset.UTC);

            if (!depNext.isAfter(arrPrev)) {
                depNext = depNext.plus(1, ChronoUnit.DAYS);
            }

            /*
            Instant arrNext = vNext.getHoraGMTDestino()
                    .atDate(plan.getCreadoUtc().atZone(ZoneOffset.UTC).toLocalDate())
                    .toInstant(ZoneOffset.UTC);

            // Aseguramos también que la llegada sea después de la salida
            if (!arrNext.isAfter(depNext)) {
                arrNext = arrNext.plus(1, ChronoUnit.DAYS);
            }
             */

            // La conexión debe ser cronológica y con intervalo positivo
            if (!depNext.isAfter(arrPrev)) {
                return null; // conexión inválida (sin tiempo o en el pasado)
            }

            int qEscala = journal.getOcc().maxReservable(apEscala, arrPrev, depNext);
            //Ruta inválida
            if (qEscala <= 0) {
                return null;
            }
            qOccEscalas = Math.min(qOccEscalas, qEscala);
        }

        // Si la holgura de escalas no alcanza la demanda, descartar
        if (qOccEscalas < demanda) {
            return null;
        }

        // 2) Validamos las 2h de ocupación en el destino.
        Vuelo ultimo = ruta.get(ruta.size() - 1);
        Instant llegadaFinal = ultimo.getHoraGMTDestino()
                .atDate(presenteUTC.atZone(ZoneOffset.UTC).toLocalDate())
                .toInstant(ZoneOffset.UTC);



        int qOccDest = journal.getOcc().maxReservable(destino, llegadaFinal, llegadaFinal.plus(Duration.ofHours(2)));
        if (qOccDest < demanda) {
            return null; // no hay espacio suficiente para toda la demanda del plan
        }

        return ruta;
    }

    private double costoRuta(List<Vuelo> ruta) {
        return ruta.stream()
                .mapToDouble(v -> v.getCosto() + v.getHoraGMTDestino().toSecondOfDay() * 0.001)
                .sum();
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

    private TramoAsignado vueloToTramoAsignado(Vuelo v, int cantidad, Instant referencia) {
        // misma base de fecha que usas en Dijkstra
        LocalDate base = referencia.atZone(ZoneOffset.UTC).toLocalDate();

        // salida y llegada en UTC, truncadas a segundos
        Instant salidaUtc = v.getHoraGMTOrigen()
                .atDate(base)
                .toInstant(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.SECONDS);

        Instant llegadaUtc = v.getHoraGMTDestino()
                .atDate(base)
                .toInstant(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.SECONDS);

        // mismo ajuste de cruce de medianoche que en Dijkstra
        if (!llegadaUtc.isAfter(salidaUtc)) {
            llegadaUtc = llegadaUtc.plus(1, ChronoUnit.DAYS);
        }

        VueloProgramadoId id = new VueloProgramadoId(
                v.getOrigen(),
                v.getDestino(),
                salidaUtc,
                llegadaUtc
        );

        // usa la llegada ajustada
        return new TramoAsignado(id, cantidad, llegadaUtc);
    }
}
