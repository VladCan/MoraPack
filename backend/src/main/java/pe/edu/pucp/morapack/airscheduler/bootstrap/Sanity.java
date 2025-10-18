package pe.edu.pucp.morapack.airscheduler.bootstrap;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import pe.edu.pucp.morapack.airscheduler.engine.flights.model.AereopuertoNode;
import pe.edu.pucp.morapack.airscheduler.engine.flights.model.VuelosEdge;
import pe.edu.pucp.morapack.airscheduler.engine.orders.model.Pedido;
import pe.edu.pucp.morapack.airscheduler.infra.io.CargarPedidos;
import pe.edu.pucp.morapack.airscheduler.infra.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.infra.memory.VuelosTEG;

@Slf4j
@UtilityClass
public final class Sanity {

    // ===========================
    // Entrada principal sugerida
    // ===========================
    public static boolean todoOk(
            Set<String> sedes,
            AeropuertosMap aeropuertosMap,
            CargarPedidos.VentanaPedidos ventana,
            VuelosTEG teg
    ) {
        boolean ok = true;
        ok &= sedesMapeadas(sedes, aeropuertosMap);
        ok &= pedidosVentanaOk(ventana);
        ok &= tegOk(teg, sedes);

        resumen(ventana, teg);
        return ok;
    }

    // ===========================
    // 1) Chequeo de SEDES
    // ===========================
    public static boolean sedesMapeadas(Set<String> sedes, AeropuertosMap aeropuertosMap) {
        System.out.println("=== Chequeo de SEDES ===");
        boolean ok = true;

        for (String sede : sedes) {
            var a = aeropuertosMap.obtener(sede);
            if (a == null) {
                System.out.println("❌ Sede no encontrada en catálogo: " + sede);
                ok = false;
                continue;
            }
            System.out.printf("✅ Sede %s encontrada | GMT=%d | Capacidad=%d | Ciudad=%s, País=%s%n",
                    a.getCodigo(), a.getGMT(), a.getCapacidad(), a.getCiudad(), a.getPais());
        }
        System.out.println("========================\n");
        return ok;
    }

    // ===========================
    // 2) Chequeo de VENTANA DE PEDIDOS (backlog + <= presenteUTC)
    // ===========================
    public static boolean pedidosVentanaOk(CargarPedidos.VentanaPedidos ventana) {
        System.out.println("=== Chequeo de VENTANA DE PEDIDOS ===");
        if (ventana == null || ventana.pedidos() == null) {
            System.out.println("❌ Ventana nula");
            return false;
        }
        Instant presente = ventana.presenteUTC();
        List<Pedido> lista = ventana.pedidos();

        System.out.printf("Ventana: hasta=%s | totalPedidos=%d%n", presente, lista.size());

        if (presente == null) {
            if (lista.isEmpty()) {
                System.out.println("ℹ️ Ventana vacía (sin pedidos).");
                System.out.println("=====================================\n");
                return true;
            } else {
                System.out.println("❌ Ventana inconsistente (presente nulo con pedidos).");
                System.out.println("=====================================\n");
                return false;
            }
        }

        boolean ok = true;

        // a) Todos con createdAtUtc <= presente y no nulo
        long fuera = lista.stream().filter(p ->
                p.getCreatedAtUtc() == null || p.getCreatedAtUtc().isAfter(presente)
        ).count();
        if (fuera > 0) {
            System.out.println("❌ Hay pedidos fuera de la ventana (createdAtUtc > presente) o con fecha nula: " + fuera);
            ok = false;
        } else {
            System.out.println("✅ Todos los pedidos están ≤ presenteUTC.");
        }

        // b) Orden ascendente por createdAtUtc (respeta orden de llegada)
        boolean ordenado = estaOrdenadoPorUtcAsc(lista);
        if (!ordenado) {
            System.out.println("❌ La lista de pedidos NO está en orden ascendente por createdAtUtc.");
            imprimirPrimerDesorden(lista);
            ok = false;
        } else {
            System.out.println("✅ Pedidos en orden ascendente por createdAtUtc.");
        }

        // c) Muestra un pequeño sample
        lista.stream().limit(5).forEach(p ->
                System.out.printf(" • Pedido id=%d | origen=%s | destino=%s | utc=%s%n",
                        p.getIdPedido(), p.getOrigen(), p.getDestino(), p.getCreatedAtUtc())
        );

        System.out.println("=====================================\n");
        return ok;
    }

    private static boolean estaOrdenadoPorUtcAsc(List<Pedido> lista) {
        Instant prev = null;
        for (Pedido p : lista) {
            Instant t = p.getCreatedAtUtc();
            if (t == null) return false;
            if (prev != null && t.isBefore(prev)) return false;
            prev = t;
        }
        return true;
    }

    private static void imprimirPrimerDesorden(List<Pedido> lista) {
        Instant prev = null;
        for (Pedido p : lista) {
            if (prev != null && p.getCreatedAtUtc().isBefore(prev)) {
                System.out.printf("   -> Desorden: prev=%s, actual=%s (pedido id=%d)%n",
                        prev, p.getCreatedAtUtc(), p.getIdPedido());
                break;
            }
            prev = p.getCreatedAtUtc();
        }
    }

    // ===========================
    // 3) Chequeo del TEG
    // ===========================
    public static boolean tegOk(VuelosTEG teg, Set<String> sedes) {
        System.out.println("=== Chequeo de TEG (event-based) ===");
        if (teg == null) {
            System.out.println("❌ TEG nulo");
            return false;
        }

        System.out.printf("Nodos=%d | Arcos=%d%n", teg.cantidadNodos(), teg.cantidadArcos());

        boolean ok = true;
        ok &= checkArcosFlight(teg);
        ok &= checkArcosWait(teg);
        ok &= checkSupplyOmega(teg, sedes);

        System.out.println("====================================\n");
        return ok;
    }

    /** FLIGHT: llegada > salida y nodos no nulos. */
    private static boolean checkArcosFlight(VuelosTEG teg) {
        List<VuelosEdge> flights = teg.arcos().stream()
                .filter(e -> e.tipo() == VuelosEdge.Type.FLIGHT)
                .collect(Collectors.toList());

        boolean ok = true;
        for (VuelosEdge e : flights) {
            AereopuertoNode a = e.salida();
            AereopuertoNode b = e.destino();
            if (a == null || b == null || a.getTiempoUTC() == null || b.getTiempoUTC() == null) {
                System.out.println("❌ FLIGHT con nodo nulo o tiempo nulo: " + e);
                ok = false;
                continue;
            }
            if (!b.getTiempoUTC().isAfter(a.getTiempoUTC())) {
                System.out.println("❌ FLIGHT con llegada <= salida: " + e);
                ok = false;
            }
        }
        if (ok) System.out.println("✅ FLIGHT: tiempos consistentes y nodos válidos (" + flights.size() + ").");
        return ok;
    }

    /** WAIT: mismo aeropuerto y tiempo no decreciente. */
    private static boolean checkArcosWait(VuelosTEG teg) {
        List<VuelosEdge> waits = teg.arcos().stream()
                .filter(e -> e.tipo() == VuelosEdge.Type.WAIT)
                .collect(Collectors.toList());

        boolean ok = true;
        for (VuelosEdge e : waits) {
            AereopuertoNode a = e.salida();
            AereopuertoNode b = e.destino();
            if (a == null || b == null || a.getTiempoUTC() == null || b.getTiempoUTC() == null) {
                System.out.println("❌ WAIT con nodo nulo o tiempo nulo: " + e);
                ok = false;
                continue;
            }
            if (!Objects.equals(a.getCodigoAP(), b.getCodigoAP())) {
                System.out.println("❌ WAIT entre aeropuertos distintos: " + e);
                ok = false;
            }
            if (b.getTiempoUTC().isBefore(a.getTiempoUTC())) {
                System.out.println("❌ WAIT con tiempo destino < tiempo salida: " + e);
                ok = false;
            }
        }
        if (ok) System.out.println("✅ WAIT: mismo aeropuerto y tiempo creciente (" + waits.size() + ").");
        return ok;
    }

    /** SUPPLY: debe existir Ω-<sede> y al menos un arco SUPPLY saliendo hacia su primer evento. */
    private static boolean checkSupplyOmega(VuelosTEG teg, Set<String> sedes) {
        List<VuelosEdge> supply = teg.arcos().stream()
                .filter(e -> e.tipo() == VuelosEdge.Type.SUPPLY)
                .collect(Collectors.toList());

        boolean ok = true;
        for (String sede : sedes) {
            AereopuertoNode omega = teg.nodoOmega("OMEGA-" + sede);
            if (omega == null) {
                System.out.println("❌ No existe nodo Ω para sede: " + sede);
                ok = false;
                continue;
            }
            boolean tieneSupply = supply.stream().anyMatch(e -> e.salida() == omega);
            if (!tieneSupply) {
                System.out.println("❌ Sede " + sede + " no tiene arco SUPPLY saliendo de Ω.");
                ok = false;
            }
        }
        if (ok) System.out.println("✅ SUPPLY: Ω-sede presentes y con arcos de suministro (" + supply.size() + ").");
        return ok;
    }

    // ===========================
    // 4) Resumen + dumps
    // ===========================
    public static void resumen(CargarPedidos.VentanaPedidos ventana, VuelosTEG teg) {
        System.out.println("=== RESUMEN ===");
        if (ventana != null) {
            System.out.printf("Pedidos en ventana: %d | hasta=%s%n",
                    ventana.pedidos() == null ? 0 : ventana.pedidos().size(),
                    ventana.presenteUTC());
        }
        if (teg != null) {
            long flights = teg.arcos().stream().filter(a -> a.tipo() == VuelosEdge.Type.FLIGHT).count();
            long waits   = teg.arcos().stream().filter(a -> a.tipo() == VuelosEdge.Type.WAIT).count();
            long supply  = teg.arcos().stream().filter(a -> a.tipo() == VuelosEdge.Type.SUPPLY).count();
            System.out.printf("TEG: nodos=%d | arcos=%d (FLIGHT=%d, WAIT=%d, SUPPLY=%d)%n",
                    teg.cantidadNodos(), teg.cantidadArcos(), flights, waits, supply);
        }
        System.out.println("==============\n");
    }

    /** Muestra algunas salidas por aeropuerto (útil para depurar). */
    public static void dumpTEGSample(VuelosTEG teg, int maxPorAeropuerto) {
        System.out.println("=== DUMP TEG (sample) ===");
        Map<String, List<VuelosEdge>> porAP = new HashMap<>();
        for (AereopuertoNode n : teg.nodos()) {
            porAP.computeIfAbsent(n.getCodigoAP(), k -> new ArrayList<>()).addAll(teg.out(n));
        }
        for (var e : porAP.entrySet()) {
            System.out.println("Aeropuerto: " + e.getKey());
            e.getValue().stream().limit(maxPorAeropuerto).forEach(arco -> System.out.println("  " + arco));
        }
        System.out.println("=========================\n");
    }

    public static void chequearDuplicados(VuelosTEG teg) {
        System.out.println("\n=== Chequeo de DUPLICADOS ===");
        int dups = 0;
        for (var n : teg.nodos()) {
            var lista = teg.out(n);
            var set = new java.util.HashSet<>(lista);
            if (set.size() != lista.size()) {
                dups += (lista.size() - set.size());
                System.out.printf("⚠️  Duplicados en %s: lista=%d set=%d%n", n, lista.size(), set.size());
            }
        }
        if (dups == 0) System.out.println("✅ Sin arcos duplicados.");
        else System.out.println("❌ Arcos duplicados totales: " + dups);
    }
}
