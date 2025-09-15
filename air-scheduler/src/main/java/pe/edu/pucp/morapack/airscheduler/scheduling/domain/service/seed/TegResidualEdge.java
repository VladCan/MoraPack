package pe.edu.pucp.morapack.airscheduler.scheduling.domain.service.seed;

import pe.edu.pucp.morapack.airscheduler.flights.domain.model.VuelosEdge;

final class TegResidualEdge {
    final int from;         // índice entero interno
    final int to;
    final VuelosEdge backing; // puede ser null para “arcos auxiliares” (p.ej. slot pickup)
    int cap;                // residual capacity
    int flow;               // usado para debug
    long cost;              // costo no negativo
    TegResidualEdge rev;    // arco inverso

    TegResidualEdge(int from, int to, VuelosEdge backing, int cap, long cost) {
        this.from = from; this.to = to; this.backing = backing; this.cap = cap; this.cost = cost;
    }
}