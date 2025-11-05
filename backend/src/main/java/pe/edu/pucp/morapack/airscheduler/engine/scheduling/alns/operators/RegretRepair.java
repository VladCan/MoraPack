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
    private final IndexVuelos indexVuelos;

    private final int N = 5;
    private final Duration slaLlegadaMax = Duration.ofHours(46);

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

        ///Vamos a buscar todos los planes que no tengan rutas (osea, los destruidos)
        for (PlanPedido plan : planos) {
            /// Encontramos un plan sin rutas
            if (plan.getRutas() == null || plan.getRutas().isEmpty()) {
                int idPedido = plan.getIdPedido();
                int demanda = plan.getDemanda();
                String dest = plan.getAeropuertoDestino();
                Instant limite = plan.getCreadoUtc().plus(slaLlegadaMax);

                List<PlanPedido> planesCandidatos = new ArrayList<>();

                /// Vamos a generar N planes para un mismo pedido
                for (int i = 0; i < N; i++) {
                    List<RutaAsignada> rutasAsignadas = new ArrayList<>();
                    Instant primeraLlegada = null;

                    ///Acá tendríamos que aplicar las perturbaciones

                    int rem = demanda;

                    while (rem > 0) {
                        /// Aca debería de ir la lógica del SSP
                    }

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

                    /*PlanPedido planCandidato = PlanPedido.builder()
                            .idPedido(idPedido)
                            .aeropuertoDestino(dest)
                            .creadoUtc(plan.getCreadoUtc())
                            .demanda(demanda)
                            .rutas(rutasAsignadas)   // <<<<<<<<<<<<<<<<<<<<<<<<<<<
                            .build();

                    planesCandidatos.add(planCandidato);
                    */


                    rem --;
                }

                /// Sobre los planesCandidatos, aplicamos el regret.

                /// Nos quedamos con el mejor plan.

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
            TreeMap<Instant, Integer> evs = eventos.computeIfAbsent(idAeropuerto, k -> new TreeMap<>());
            evs.merge(inicio, q, Integer::sum);
            evs.merge(fin, -q, Integer::sum);
        }

    }



}
