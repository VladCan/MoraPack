    package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.alns.operators;

    import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.PlanPedido;
    import pe.edu.pucp.morapack.airscheduler.scheduling.domain.model.SolucionProgramacion;

    import java.util.ArrayList;
    import java.util.Comparator;
    import java.util.List;
    import java.util.Map;
    import java.util.Random;

    public class WorstRemoval implements DestructionOperator {
        private final int porcentaje;
        private final Random rnd = new Random();

        public WorstRemoval(int porcentaje) {
            this.porcentaje = porcentaje;
        }

        @Override
        public void destroy(SolucionProgramacion s) {
            Map<Integer, PlanPedido> planes = s.getPlanPorPedido();
            if (planes.isEmpty()) return;

            // Calcular "costo" de cada plan como suma de cantidades asignadas * cantidad de tramos (heurística simple)
            List<PlanPedido> listaPlanes = new ArrayList<>(planes.values());
            listaPlanes.sort(Comparator.comparingInt(p -> -p.getTramos().size())); // ordenar descendente por #tramos

            int n = listaPlanes.size() * porcentaje / 100;

            for (int i = 0; i < n && i < listaPlanes.size(); i++) {
                PlanPedido plan = listaPlanes.get(i);
                // Vaciar tramos para "destruir" la asignación
                plan.getTramosMutable().clear();
            }
        }
    }
