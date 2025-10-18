package pe.edu.pucp.morapack.airscheduler.engine.orders.adapters.io;

import lombok.Getter;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import lombok.Setter;
import pe.edu.pucp.morapack.airscheduler.engine.flights.adapters.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.engine.orders.domain.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.domain.model.PlanPedido;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.domain.model.RutaAsignada;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.domain.model.SolucionProgramacion;
import pe.edu.pucp.morapack.airscheduler.engine.scheduling.domain.model.TramoAsignado;

@Getter
@Setter
public class CargarPedidos {

    private final Queue<Pedido> colaPedidos = new LinkedList<>();
    private boolean utcNormalizada = false; // evita doble normalización

    // DTO simple de la ventana
    public record VentanaPedidos(Instant presenteUTC, List<Pedido> pedidos) {
    }

    // ========== Operaciones básicas ==========
    public void agregar(Pedido pedido) {
        colaPedidos.add(pedido);
    }

    public void ordenarPorUTC() {
        List<Pedido> tmp = new ArrayList<>(colaPedidos);
        // Elige el getter correcto según tu modelo:
        // 1) Si tienes un Instant:
        // tmp.sort(Comparator.comparing(Pedido::getUtcInstant));

        // 2) Si guardas el ISO como String:
        // tmp.sort(Comparator.comparing(p -> Instant.parse(p.getUtcIso())));

        // 3) Si el método se llama getFechaUTC():
        tmp.sort(Comparator.comparing(Pedido::getCreatedAtUtc));

        colaPedidos.clear();
        colaPedidos.addAll(tmp);
    }

    /** Copia defensiva en lista. */
    public List<Pedido> getLista() {
        return new ArrayList<>(colaPedidos);
    }

    public void leerDatos(Scanner sc) {
        while (sc.hasNextLine()) {
            Pedido pedido = new Pedido();
            pedido.leer(sc);
            agregar(pedido);
        }
    }

    public void leerDatosProfe(Scanner sc) {
        while (sc.hasNextLine()) {
            Pedido pedido = new Pedido();
            int idPedido=colaPedidos.size()+1;
            pedido.leerProfe(sc,idPedido);
            agregar(pedido);
        }
    }

    /**
     * Paso 2 (luego de leer): establece createdAtUtc en cada pedido
     * usando el GMT del aeropuerto DESTINO. No altera el orden de la cola.
     */
    public void normalizarUtc(AeropuertosMap aeropuertosMap) {
        if (utcNormalizada)
            return; // idempotente

        for (Pedido pedido : colaPedidos) {
            var aeropuertoDestino = aeropuertosMap.obtener(pedido.getDestino());
            int gmt = (aeropuertoDestino != null) ? aeropuertoDestino.getGMT() : 0; // fallback seguro
            pedido.computeUtcFromGmt(gmt);
        }
        utcNormalizada = true;
    }

    /** Útil para asserts/sanity checks. */
    public boolean isUtcNormalizada() {
        return utcNormalizada;
    }

    public Instant primerInstanteUTC() {
        if (!utcNormalizada)
            throw new IllegalStateException("Primero llama a normalizarUtc(aeropuertosMap).");
        for (Pedido p : colaPedidos) {
            if (p.getCreatedAtUtc() != null)
                return p.getCreatedAtUtc();
        }
        return null;
    }

    /********** NUEVO: ventana desde un inicio dado y duración en horas **********/
    public VentanaPedidos acumuladoHasta(Instant presenteUTC) {
        if (!utcNormalizada)
            throw new IllegalStateException("Primero llama a normalizarUtc(aeropuertosMap).");
        if (presenteUTC == null || colaPedidos.isEmpty())
            return new VentanaPedidos(null, List.of());

        List<Pedido> candidatos = new ArrayList<>();
        for (Pedido p : colaPedidos) {
            Instant t = p.getCreatedAtUtc();
            if (t == null)
                continue;
            if (!t.isAfter(presenteUTC))
                candidatos.add(p);
            else
                break; // la cola está ordenada temporalmente: podemos cortar
        }
        return new VentanaPedidos(presenteUTC, candidatos);
    }

    // --- NUEVO: listar sin remover ---
    /**
     * Lista (NO remueve) los pedidos con createdAtUtc <= hastaIncl, respetando el
     * orden de la cola.
     */
    public List<Pedido> listarHasta(Instant hastaIncl) {
        if (!utcNormalizada)
            throw new IllegalStateException("Primero llama a normalizarUtc(aeropuertosMap).");
        List<Pedido> res = new ArrayList<>();
        for (Pedido p : colaPedidos) {
            Instant t = p.getCreatedAtUtc();
            if (t == null || t.isAfter(hastaIncl))
                break; // la cola está ordenada por tiempo
            res.add(p);
        }
        return res;
    }

    // --- NUEVO: remover solo los ya completados ---
    /** Elimina de la cola únicamente los pedidos cuyo id esté en idsCompletados. */
    public void eliminarPedidosCompletados(Set<Integer> idsCompletados) {
        if (idsCompletados == null || idsCompletados.isEmpty())
            return;
        colaPedidos.removeIf(p -> idsCompletados.contains(p.getIdPedido()));
    }

    // --- NUEVO: saber si aún hay pedidos “futuros” (para saber si seguimos
    // simulando) ---
    /** ¿Quedan pedidos con createdAtUtc > t? */
    public boolean hayPedidosDespuesDe(Instant t) {
        for (Pedido p : colaPedidos) {
            Instant c = p.getCreatedAtUtc();
            if (c != null && c.isAfter(t))
                return true;
        }
        return false;
    }

    // ========== Consumo / utilidades ==========
    /** Elimina de la cola los pedidos planificados en la tanda (por id). */
    public void consumir(List<Pedido> tanda) {
        if (tanda == null || tanda.isEmpty())
            return;
        Set<Integer> ids = tanda.stream().map(Pedido::getIdPedido).collect(Collectors.toSet());
        colaPedidos.removeIf(p -> ids.contains(p.getIdPedido()));
    }

    public void mostrar() {
        for (Pedido p : colaPedidos)
            System.out.println(p);
    }

    public boolean isEmpty() {
        return colaPedidos.isEmpty();
    }

    public void eliminarYActualizarCumplidosHasta(Instant presenteUTC, SolucionProgramacion solucionAnterior) {
        if (solucionAnterior == null || presenteUTC == null) return;

        Map<Integer, PlanPedido> planes = solucionAnterior.getPlanPorPedido();
        if (planes == null || planes.isEmpty()) return;

        for (Iterator<Pedido> it = colaPedidos.iterator(); it.hasNext();) {
            Pedido pedido = it.next();
            PlanPedido plan = planes.get(pedido.getIdPedido());
            if (plan == null || plan.getRutas() == null || plan.getRutas().isEmpty()) {
                // sin asignaciones previas para este pedido
                continue;
            }

            int entregado = 0;   // rutas ya 100% llegadas al destino ≤ corte
            int enProgreso = 0;  // rutas que ya iniciaron (primer tramo despegó < corte) pero aún no llegaron

            for (RutaAsignada ruta : plan.getRutas()) {
                if (ruta == null || ruta.getTramos() == null || ruta.getTramos().isEmpty()) continue;

                var tramos = ruta.getTramos();
                // por convenio los tramos de una ruta están en orden cronológico origen→destino
                TramoAsignado first = tramos.get(0);
                TramoAsignado last  = tramos.get(tramos.size() - 1);

                Instant salidaPrimera = (first.getVuelo()   != null) ? first.getVuelo().getSalidaUtc()  : null;
                Instant llegadaFinal  = (last.getLlegadaUtc() != null) ? last.getLlegadaUtc()            : null;

                int q = ruta.getCantidad(); // cantidad que viaja por ESTA ruta

                // 1) Ruta completamente entregada hasta el corte
                if (llegadaFinal != null && !llegadaFinal.isAfter(presenteUTC)) {
                    entregado += q;
                    continue;
                }

                // 2) Ruta ya iniciada (comprometida) pero no entregada aún:
                //    primer tramo despegó antes del corte (aunque esté en vuelo o esperando conexión)
                if (salidaPrimera != null && salidaPrimera.isBefore(presenteUTC)) {
                    enProgreso += q;
                }
                // 3) Si primer tramo sale ≥ corte => futuro, no suma a irrevocable
            }

            int demandaTotal = pedido.getCantidad();
            int cubiertoIrrevocable = entregado + enProgreso;
            int remanente = Math.max(0, demandaTotal - cubiertoIrrevocable);

            if (remanente == 0) {
                // totalmente cubierto por lo ya entregado + en progreso
                it.remove();
            } else {
                // mantenemos en cola sólo lo replanificable
                pedido.setCantidad(remanente);
            }
        }
    }



    
    // Añade estos formatters dentro de la clase CargarPedidos (como campos
    // estáticos)
    private static final java.time.format.DateTimeFormatter FMT_LOCAL = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final java.time.format.DateTimeFormatter FMT_UTC = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(java.time.ZoneOffset.UTC);

    /** Utilidad: imprime "-" si es null o vacío */
    private static String nvl(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }

    /**
     * Imprime la cola en una tabla bonita, ordenada por tiempo (solo para mostrar).
     */
    public void imprimrPedidos() {
        if (colaPedidos.isEmpty()) {
            System.out.println("No hay pedidos en la cola.");
            return;
        }

        // Copia y orden para impresión (no altera la cola):
        // 1) createdAtUtc (si existe), 2) fecha local, 3) idPedido
        java.util.List<Pedido> lista = new java.util.ArrayList<>(colaPedidos);
        lista.sort(
                java.util.Comparator
                        .comparing(Pedido::getCreatedAtUtc,
                                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                        .thenComparing(Pedido::getFecha,
                                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                        .thenComparingInt(Pedido::getIdPedido));

        // Cabecera
        System.out.println("================================= PEDIDOS =================================");
        System.out.printf(
                "%-5s %-8s %-7s %-7s %-15s %7s  %-19s  %-19s%n",
                "ID", "Cliente", "Destino", "Origen", "Cont.Dest", "Cant.", "Fecha(Local)", "UTC");
        System.out.println("----------------------------------------------------------------------------"
                + "------------------------------");

        // Filas
        for (Pedido p : lista) {
            String fechaLocalStr = (p.getFecha() != null) ? FMT_LOCAL.format(p.getFecha()) : "-";
            String utcStr = (p.getCreatedAtUtc() != null) ? FMT_UTC.format(p.getCreatedAtUtc()) : "-";

            System.out.printf(
                    "%-5d %-8d %-7s %-7s %-15s %7d  %-19s  %-19s%n",
                    p.getIdPedido(),
                    p.getIdCliente(),
                    nvl(p.getDestino()),
                    nvl(p.getOrigen()),
                    nvl(p.getContinenteDestino()),
                    p.getCantidad(),
                    fechaLocalStr,
                    utcStr);
        }

        System.out.println("============================================================================"
                + "==============================");
    }
    /** Imprime una VentanaPedidos (hasta presenteUTC) en una tabla bonita. */
    public void imprimirVentanaDePedidos(VentanaPedidos ventana) {
        if (ventana == null) {
            System.out.println("VentanaPedidos: null");
            return;
        }

        Instant corte = ventana.presenteUTC();
        List<Pedido> pedidos = ventana.pedidos();

        String corteStr = (corte != null) ? FMT_UTC.format(corte) : "-";

        System.out.println("================================ VENTANA DE PEDIDOS ================================");
        System.out.println("Hasta (corte UTC): " + corteStr);
        System.out.println("Cantidad de pedidos en la ventana: " + (pedidos == null ? 0 : pedidos.size()));
        System.out.println("-----------------------------------------------------------------------------------");

        if (pedidos == null || pedidos.isEmpty()) {
            System.out.println("(sin pedidos en la ventana)");
            System.out.println("===================================================================================");
            return;
        }

        // Orden solo para impresión (no modifica la cola)
        List<Pedido> lista = new ArrayList<>(pedidos);
        lista.sort(
            Comparator
                .comparing(Pedido::getCreatedAtUtc,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Pedido::getFecha,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(Pedido::getIdPedido)
        );

        // Cabecera de tabla (mismo layout que imprimrPedidos)
        System.out.printf(
            "%-5s %-8s %-7s %-7s %-15s %7s  %-19s  %-19s%n",
            "ID", "Cliente", "Destino", "Origen", "Cont.Dest", "Cant.", "Fecha(Local)", "UTC"
        );
        System.out.println("-----------------------------------------------------------------------------------"
                        + "-----------------------");

        int totalCant = 0;

        for (Pedido p : lista) {
            String fechaLocalStr = (p.getFecha() != null) ? FMT_LOCAL.format(p.getFecha()) : "-";
            String utcStr        = (p.getCreatedAtUtc() != null) ? FMT_UTC.format(p.getCreatedAtUtc()) : "-";

            System.out.printf(
                "%-5d %-8d %-7s %-7s %-15s %7d  %-19s  %-19s%n",
                p.getIdPedido(),
                p.getIdCliente(),
                nvl(p.getDestino()),
                nvl(p.getOrigen()),
                nvl(p.getContinenteDestino()),
                p.getCantidad(),
                fechaLocalStr,
                utcStr
            );
            totalCant += p.getCantidad();
        }

        System.out.println("-----------------------------------------------------------------------------------"
                        + "-----------------------");
        System.out.println("Totales -> pedidos: " + lista.size() + " | cantidad acumulada: " + totalCant);
        System.out.println("===================================================================================");
    }

}
