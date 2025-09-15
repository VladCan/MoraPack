package pe.edu.pucp.morapack.airscheduler.bootstrap;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.AeropuertosMap;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosGraph;
import pe.edu.pucp.morapack.airscheduler.flights.adapters.memory.VuelosMap;

public final class Sanity {
    private Sanity(){}

    public static void run(AeropuertosMap aMap, VuelosMap vMap, VuelosGraph G){
        int airports = aMap.size();
        int flights  = vMap.totalVuelos();
        int nodes    = G.nodeCount();
        int edges    = G.edgeCount();

        System.out.printf("Aeropuertos=%d, Vuelos=%d, Nodos=%d, Arcos=%d%n",
                airports, flights, nodes, edges);

        java.util.Set<String> faltantes = new java.util.HashSet<>();
        for (String orig : vMap.origenes()) {
            if (!aMap.contains(orig)) faltantes.add("ORIG:" + orig);
            for (var vue : vMap.vuelosDesde(orig)) {
                String dst = vue.getDestino();
                if (!aMap.contains(dst)) faltantes.add("DEST:" + dst);
            }
        }
        if (faltantes.isEmpty()) {
            System.out.println("OK: todos los códigos de vuelos existen en aeropuertos.");
        } else {
            System.out.println("Faltan en aeropuertos: " + faltantes);
        }

        for (String o : java.util.List.of("SPIM","SKBO","SEQM","SVMI","SBBR","EBCI")) {
            var n = G.node(o);
            System.out.println(o + " -> " + (n == null ? "NO_NODE" : (G.out(n).size() + " arcos")));
        }
    }
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
                // fecha local -> UTC con GMT del destino
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

        System.out.printf("Pedidos=%d, Rupturas=%d, Invalidas=%d, SinAeropuerto=%d%n",
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
            System.out.println("OK: pedidos en orden no-decreciente por tiempo UTC.");
        }
    }
}