// src/main/java/pe/edu/pucp/morapack/airscheduler/infrastructure/config/TimeConfig.java
package pe.edu.pucp.morapack.airscheduler.infra.config;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.time.ZoneOffset;

/** Provee un Clock UTC inyectable para toda la app. */
public class TimeConfig {

    @Produces
    @Singleton
    public Clock clockUtc() {
        return Clock.system(ZoneOffset.UTC);
    }
}
