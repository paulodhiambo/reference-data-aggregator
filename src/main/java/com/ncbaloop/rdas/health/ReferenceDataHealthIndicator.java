package com.ncbaloop.rdas.health;

import com.ncbaloop.rdas.snapshot.Snapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Custom Actuator health indicator — used by the readiness probe.
 *
 * <p>Liveness ({@code /actuator/health/liveness}) uses only the built-in
 * {@code ping} indicator and is independent of SOAP / cache state.
 * This indicator is wired into the <b>readiness group</b> only, so a missing
 * snapshot prevents traffic routing without triggering a container restart loop
 */
@Component("referenceData")
public class ReferenceDataHealthIndicator implements HealthIndicator {
    private final AtomicReference<Snapshot> store;
    private final long staleThresholdHours;

    public ReferenceDataHealthIndicator(
            AtomicReference<Snapshot> store,
            @Value("${rdas.cache.stale-threshold-hours}") long staleThresholdHours) {
        this.store = store;
        this.staleThresholdHours = staleThresholdHours;
    }

    @Override
    public Health health() {
        Snapshot s = store.get();

        if (s == null) {
            return Health.down()
                    .withDetail("reason", "Snapshot not yet loaded")
                    .build();
        }

        long ageSeconds = Duration.between(s.loadedAt(), Instant.now()).getSeconds();
        boolean stale = s.isStale(staleThresholdHours);

        Health.Builder builder = stale ? Health.down() : Health.up();
        return builder
                .withDetail("source", s.source())
                .withDetail("loadedAt", s.loadedAt())
                .withDetail("ageSeconds", ageSeconds)
                .withDetail("countries", s.countries().size())
                .withDetail("stale", stale)
                .withDetail("contentHash", s.contentHash().substring(0, 8))
                .build();
    }
}
