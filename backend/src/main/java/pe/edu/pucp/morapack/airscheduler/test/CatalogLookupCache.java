package pe.edu.pucp.morapack.airscheduler.test;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Vuelo;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Cachea validaciones (ORI, DST, depTOD, arrTOD) contra el catálogo original.
 * Evita falsos negativos por búsquedas repetidas y acelera la enumeración.
 */
public class CatalogLookupCache {

    // clave: ORI -> DST -> depTOD -> set<arrTOD>
    private final Map<String, Map<String, Map<LocalTime, Set<LocalTime>>>> index = new HashMap<>();

    public CatalogLookupCache(Map<String, List<Vuelo>> catalogoPorOrigen) {
        if (catalogoPorOrigen == null) return;
        for (Map.Entry<String, List<Vuelo>> e : catalogoPorOrigen.entrySet()) {
            String ori = e.getKey();
            Map<String, Map<LocalTime, Set<LocalTime>>> byDst =
                    index.computeIfAbsent(ori, k -> new HashMap<>());

            for (Vuelo v : e.getValue()) {
                String dst = v.getDestino();
                LocalTime dep = v.getHoraGMTOrigen();
                LocalTime arr = v.getHoraGMTDestino();
                if (dst == null || dep == null || arr == null) continue;

                Map<LocalTime, Set<LocalTime>> byDep =
                        byDst.computeIfAbsent(dst, k -> new HashMap<>());
                Set<LocalTime> arrs =
                        byDep.computeIfAbsent(dep, k -> new HashSet<>());
                arrs.add(arr);
            }
        }
    }

    /** Verifica existencia de un tramo por horario UTC (time-of-day) exacto. */
    public boolean exists(String origen, String destino, Instant depUtc, Instant arrUtc) {
        if (origen == null || destino == null || depUtc == null || arrUtc == null) return false;
        Map<String, Map<LocalTime, Set<LocalTime>>> byDst = index.get(origen);
        if (byDst == null) return false;
        Map<LocalTime, Set<LocalTime>> byDep = byDst.get(destino);
        if (byDep == null) return false;

        LocalTime depTOD = depUtc.atZone(ZoneOffset.UTC).toLocalTime();
        LocalTime arrTOD = arrUtc.atZone(ZoneOffset.UTC).toLocalTime();

        Set<LocalTime> arrs = byDep.get(depTOD);
        return arrs != null && arrs.contains(arrTOD);
        // Si hubiese minúsculos desfases de segundos, se podría tolerar ±60s, pero aquí usamos igualdad estricta.
    }
}
