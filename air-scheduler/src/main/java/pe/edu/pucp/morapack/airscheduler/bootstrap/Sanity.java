package pe.edu.pucp.morapack.airscheduler.bootstrap;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosMap;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosTEG;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosEdge;
import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosNode;
import pe.edu.pucp.morapack.airscheduler.orders.domain.model.Pedido;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

public final class Sanity {
    private Sanity(){}

    /** Sanity del grafo base (vuelos sin tiempo) + TEG */
    public static void run(AeropuertosMap aMap, VuelosMap vMap, VuelosTEG G, Set<String> sedesSiempreLlenas){
        // 1) tamaños
        int airports = aMap.size();
        int flights  = vMap.totalVuelos();
        int nodes    = G.nodeCount();
        int edges    = G.edgeCount();
        System.out.printf("AeropuertosBase=%d, VuelosBase=%d, TEG.Nodos=%d, TEG.Arcos=%d%n",
                airports, flights, nodes, edges);

        // 2) todos los códigos de vuelos existen en aeropuertos
        Set<String> faltantes = new HashSet<>();
        for (String orig : vMap.origenes()) {
            if (!aMap.contains(orig)) faltantes.add("ORIG:" + orig);
            for (var vue : vMap.vuelosDesde(orig)) {
                String dst = vue.getDestino();
                if (!aMap.contains(dst)) faltantes.add("DEST:" + dst);
            }
        }
        if (faltantes.isEmpty()) System.out.println("OK: todos los códigos de vuelos existen en aeropuertos.");
        else System.out.println("Faltan en aeropuertos: " + faltantes);

        // 3) conteo por tipo de arco
        int cFlight=0, cWait=0, cSupply=0, badTimes=0;
        for (var n : G.nodes()) {
            for (var e : G.out(n)) {
                switch (e.getType()) {
                    case FLIGHT -> {
                        cFlight++;
                        Instant dep = e.getFrom().getTimeUtc();
                        Instant arr = e.getTo().getTimeUtc();
                        if (dep == null || arr == null || !arr.isAfter(dep)) badTimes++;
                    }
                    case WAIT   -> cWait++;
                    case SUPPLY -> cSupply++;
                }
            }
        }
        System.out.printf("Arcos: FLIGHT=%d, WAIT=%d, SUPPLY=%d%n", cFlight, cWait, cSupply);
        if (badTimes > 0) {
            System.out.printf("WARN: %d arcos FLIGHT con tiempo no consistente (arr !> dep).%n", badTimes);
        }

        // 4) resumen por ICAO: nodos y out-arcos
        Map<String, Long> nodosPorIcao = G.nodes().stream()
                .filter(n -> !n.isSuperSource())
                .collect(Collectors.groupingBy(VuelosNode::getIcao, Collectors.counting()));
        Map<String, Long> outArcosPorIcao = new HashMap<>();
        for (var n : G.nodes()) {
            if (n.isSuperSource()) continue;
            outArcosPorIcao.merge(n.getIcao(), (long) G.out(n).size(), Long::sum);
        }
        // muestra algunos
        for (String o : List.of("SPIM","SKBO","SEQM","SVMI","SBBR","EBCI")) {
            long nCount = nodosPorIcao.getOrDefault(o, 0L);
            long eCount = outArcosPorIcao.getOrDefault(o, 0L);
            System.out.printf("%s -> nodos=%d, outArcos=%d%n", o, nCount, eCount);
        }

        // 5) validar: NO debe haber WAIT en sedes “siempre llenas”
        int waitsInSedes = 0;
        List<VuelosEdge> muestrasWaitSede = new ArrayList<>();
        for (var n : G.nodes()) {
            if (n.isSuperSource()) continue;
            if (!sedesSiempreLlenas.contains(n.getIcao())) continue;
            for (var e : G.out(n)) {
                if (e.getType() == VuelosEdge.Type.WAIT) {
                    waitsInSedes++;
                    if (muestrasWaitSede.size() < 5) muestrasWaitSede.add(e);
                }
            }
        }
        if (waitsInSedes == 0) {
            System.out.println("OK: sin WAIT en sedes (modelo JIT).");
        } else {
            System.out.printf("WARN: %d arcos WAIT en sedes. Ejemplos:%n", waitsInSedes);
            for (var e : muestrasWaitSede) System.out.println("  " + e);
        }

        // 6) SUPPLY por sede (cuántos y capacidad total)
        for (String sede : sedesSiempreLlenas) {
            long cnt = 0, capSum = 0;
            List<VuelosEdge> muestras = new ArrayList<>();
            for (var n : G.nodes()) {
                for (var e : G.out(n)) {
                    if (e.getType() == VuelosEdge.Type.SUPPLY && e.getTo().getIcao().equals(sede)) {
                        cnt++;
                        capSum += e.getCapacity();
                        if (muestras.size() < 3) muestras.add(e);
                    }
                }
            }
            System.out.printf("SUPPLY[%s]: arcos=%d, capTotal=%d%n", sede, cnt, capSum);
            for (var e : muestras) System.out.println("  " + e);
        }
    }

    /** Chequea orden temporal de pedidos leyendo CSV directamente (como ya tenías). */
    public static void runPedidosTiempo(AeropuertosMap aMap, Scanner sc) {
        int total = 0, rupturas = 0, invalid = 0, noAirport = 0;
        Instant first = null, last = null, prev = null;

        record Break(int lineNum, String line, Instant prevUtc, Instant currUtc) {}
        List<Break> primerasRupturas = new ArrayList<>();

        while (sc.hasNextLine()) {
            String line = sc.nextLine().trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            total++;

            String[] p = line.split(",", -1);
            if (p.length != 5) { invalid++; continue; }

            String destino = p[2].trim().toUpperCase();
            String fechaStr = p[3].trim();

            var a = aMap.obtener(destino);
            if (a == null) { noAirport++; continue; }

            try {
                ZoneOffset off = ZoneOffset.ofHours(a.getGMT());
                Instant utc = LocalDateTime.parse(fechaStr).atOffset(off).toInstant();

                if (first == null) first = utc;
                last = utc;

                if (prev != null && utc.isBefore(prev)) {
                    rupturas++;
                    if (primerasRupturas.size() < 10) {
                        primerasRupturas.add(new Break(total, line, prev, utc));
                    }
                }
                prev = utc;
            } catch (Exception ex) {
                invalid++;
            }
        }

        System.out.printf("PedidosCSV=%d, Rupturas=%d, Invalidas=%d, SinAeropuerto=%d%n",
                total, rupturas, invalid, noAirport);
        if (first != null && last != null) {
            System.out.printf("Primero(UTC)=%s  |  Último(UTC)=%s%n", first, last);
        }
        if (!primerasRupturas.isEmpty()) {
            System.out.println("Primeras rupturas (prev -> curr):");
            for (var b : primerasRupturas) {
                System.out.printf("  línea #%d: %s%n    %s  ->  %s%n",
                        b.lineNum(), b.line(), b.prevUtc(), b.currUtc());
            }
        } else if (total > 0) {
            System.out.println("OK: pedidos en orden no-decreciente por tiempo UTC (CSV).");
        }
    }

    /** Versión con lista de pedidos ya cargados; usa createdAtUtc si existe, si no convierte con GMT destino. */
    public static void runPedidosTiempo(AeropuertosMap aMap, List<Pedido> pedidos) {
        int total = 0, rupturas = 0, noAirport = 0;
        Instant first = null, last = null, prev = null;
        List<String> rupturasEj = new ArrayList<>();

        for (var p : pedidos) {
            total++;
            String dest = p.getDestino();
            var a = aMap.obtener(dest);
            if (a == null) { noAirport++; continue; }

            Instant utc;
            try {
                // Si el modelo ya trae createdAtUtc, úsalo; si no, convierte la fecha local del destino
                if (p.getCreatedAtUtc() != null) {
                    utc = p.getCreatedAtUtc();
                } else {
                    ZoneOffset off = ZoneOffset.ofHours(a.getGMT());
                    utc = p.getFecha().atOffset(off).toInstant();
                }

                if (first == null) first = utc;
                last = utc;

                if (prev != null && utc.isBefore(prev)) {
                    rupturas++;
                    if (rupturasEj.size() < 5) {
                        rupturasEj.add("Pedido " + p.getIdPedido() + " UTC=" + utc + " (prev=" + prev + ")");
                    }
                }
                prev = utc;
            } catch (Exception ex) {
                rupturas++;
                if (rupturasEj.size() < 5) rupturasEj.add("Error tiempo Pedido " + p.getIdPedido() + ": " + ex.getMessage());
            }
        }

        System.out.printf("PedidosList=%d, Rupturas=%d, SinAeropuerto=%d%n", total, rupturas, noAirport);
        if (first != null && last != null) {
            System.out.printf("Primero(UTC)=%s  |  Último(UTC)=%s%n", first, last);
        }
        if (!rupturasEj.isEmpty()) {
            System.out.println("Rupturas ejemplo:");
            for (var s : rupturasEj) System.out.println("  " + s);
        } else if (total > 0) {
            System.out.println("OK: pedidos en orden no-decreciente por tiempo UTC (lista).");
        }
    }
}
