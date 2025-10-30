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

    private static class RutaCandidata {
        private final List<VueloProgramadoId> vuelos;
        private final double costo;
        private final int cuello; // cantidad máxima que cabe

        // getters

        public List<TramoAsignado> toTramos(int q) {
            return vuelos.stream()
                    .map(v -> new TramoAsignado(v, q, v.getLlegadaUtc()))
                    .collect(Collectors.toList());
        }
    }

    @Override
    public void repair(SolucionProgramacion s, ALNS.Journal journal, Instant presenteUTC) {
        CargaPorVuelo cargaPorVuelo = s.getCargaPorVuelo();
        List<PlanPedido> pendientes = pedidosPendientes(s);

        while (!pendientes.isEmpty()) {

            //Para cada pedido vamos a generar 'm' rutas candidatas (con multi-source)
            Map<PlanPedido, List<RutaCandidata>> candidatos = new HashMap<>();
            for (PlanPedido p : pendientes) {
                List<RutaCandidata> rutas = generarCandidatas(p, cargaPorVuelo, journal, presenteUTC);
                if (!rutas.isEmpty()) candidatos.put(p, rutas);
            }

            if (candidatos.isEmpty()) break;

            PlanPedido elegido = seleccionarPorRegret(candidatos);

            RutaCandidata mejorRuta = candidatos.get(elegido).get(0);
            confirmarRuta(s, elegido, mejorRuta, journal);

            //Si el pedido

        }

    }

    List<PlanPedido> pedidosPendientes(SolucionProgramacion s) {
        return s.getPlanPorPedido().values().stream()
                .filter(p -> p.getRutas() == null || p.getRutas().isEmpty() || p.demandaRestante() > 0)
                .collect(Collectors.toList());
    }

    private List<RutaCandidata> generarCandidatas (PlanPedido plan, CargaPorVuelo carga, ALNS.Journal journal, Instant presenteUTC){

        List<RutaCandidata> mejores = new ArrayList<>();

        for (String sede : sedes){
            List<RutaCandidata> desdeSede = buscarRutasDesdeSede(sede, plan, carga, journal, presenteUTC);
            mejores.addAll(desdeSede);
        }

        mejores.sort(Comparator.comparingDouble(RutaCandidata::costo));
        return mejores.stream().limit(5).collect(Collectors.toList());

    }

    private PlanPedido seleccionarPorRegret(Map<PlanPedido, List<RutaCandidata>> cand){
        double maxRegret = -1;
        PlanPedido elegido = null;

        for (var e : cand.entrySet()) {
            List<RutaCandidata> rutas = e.getValue();
            if (rutas.size() < 2) continue;

            double regret = 0;

            for (int i = 0; i < Math.min(k, rutas.size()); i++) {
                regret += rutas.get(i).costo - rutas.get(0).costo;
            }

            if (regret > maxRegret){
                maxRegret = regret;
                elegido = e.getKey();
            }

        }

        //En caso todos tengan una sola ruta...
        if (elegido == null){
            elegido = cand.keySet().iterator().next();
        };

        return elegido;
    }

    private void confirmarRuta(SolucionProgramacion s, PlanPedido plan, RutaCandidata cand, ALNS.Journal journal){

    }


}
