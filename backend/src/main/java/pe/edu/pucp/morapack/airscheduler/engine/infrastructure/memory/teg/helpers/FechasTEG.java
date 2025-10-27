package pe.edu.pucp.morapack.airscheduler.engine.infrastructure.memory.teg.helpers;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Utilidades de fecha/hora para el TEG (todo en UTC). */
public final class FechasTEG {
    private FechasTEG() {}

    /** Instantes diarios en [inicio, fin) con la hora UTC indicada. */
    static List<Instant> instantesDiariosEnVentana(Instant inicio, Instant fin, LocalTime horaGMT) {// inicio: dd/mm/yyyy hh:mm:ss fin: dd/mm/yyyy hh:mm:ss horaGMT: hh:mm
        List<Instant> res = new ArrayList<>();
        ZonedDateTime z0  = inicio.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime zFn = fin.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);
        for (ZonedDateTime z = z0; !z.isAfter(zFn); z = z.plusDays(1)) {
            Instant t = z.withHour(horaGMT.getHour())
                         .withMinute(horaGMT.getMinute())
                         .withSecond(0).withNano(0)
                         .toInstant();
            if (!t.isBefore(inicio) && t.isBefore(fin)) res.add(t);
        }
        return res;
    }

    /** Combina la fecha de un Instant con una hora GMT (sin fecha). */
    static Instant combinarFechaYHora(Instant baseDia, LocalTime horaGMT) {
        ZonedDateTime z = baseDia.atZone(ZoneOffset.UTC);
        return z.withHour(horaGMT.getHour())
                .withMinute(horaGMT.getMinute())
                .withSecond(0).withNano(0)
                .toInstant();
    }
}