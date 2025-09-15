package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed;

import java.util.*;

/** Min-Cost Max-Flow (successive shortest path con potenciales de Johnson). */
public final class SSPMinCostFlow {

    public static final class Result {
        public int flowSent;
        public long totalCost;
        public List<List<TegResidualEdge>> usedPaths = new ArrayList<>();
    }

    public static Result send(TegResidualGraph G, int source, int sink, int demand) {
        int n = G.adj().size();
        long[] potential = new long[n]; // Johnson
        Result res = new Result();

        while (res.flowSent < demand) {
            // Dijkstra en grafo residual con costos re-pesados: cost’ = cost + pot[u] − pot[v]
            long INF = Long.MAX_VALUE / 4;
            long[] dist = new long[n];
            Arrays.fill(dist, INF);
            TegResidualEdge[] prev = new TegResidualEdge[n];
            dist[source] = 0;

            PriorityQueue<int[]> pq = new PriorityQueue<>(Comparator.comparingLong(a -> a[1]));
            pq.add(new int[]{source, 0});

            while (!pq.isEmpty()) {
                int[] cur = pq.poll();
                int u = cur[0];
                long d = cur[1];
                if (d != dist[u]) continue;

                for (var e : G.adj().get(u)) {
                    if (e.cap <= 0) continue;
                    long rcost = e.cost + potential[u] - potential[e.to];
                    if (dist[e.to] > d + rcost) {
                        dist[e.to] = d + rcost;
                        prev[e.to] = e;
                        pq.add(new int[]{e.to, (int)Math.min(dist[e.to], Integer.MAX_VALUE)});
                    }
                }
            }

            if (dist[sink] >= INF) break; // no hay más camino

            // actualiza potenciales
            for (int i = 0; i < n; i++) if (dist[i] < INF) potential[i] += dist[i];

            // bottleneck
            int add = demand - res.flowSent;
            for (int v = sink; v != source; ) {
                var e = prev[v];
                if (e == null) return res; // sin camino (safety)
                add = Math.min(add, e.cap);
                v = e.from;
            }
            // aplica flujo
            List<TegResidualEdge> path = new ArrayList<>();
            for (int v = sink; v != source; ) {
                var e = prev[v];
                e.cap -= add;
                e.flow += add;
                e.rev.cap += add;
                path.add(e);
                v = e.from;
            }
            Collections.reverse(path);
            res.usedPaths.add(path);
            res.flowSent += add;
            res.totalCost += potential[sink]; // costo del camino actual
        }
        return res;
    }
}