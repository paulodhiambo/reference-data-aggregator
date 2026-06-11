package com.ncbaloop.rdas.snapshot;

import com.ncbaloop.rdas.model.Continent;
import com.ncbaloop.rdas.model.Country;
import com.ncbaloop.rdas.model.Currency;
import com.ncbaloop.rdas.model.Language;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable in-memory snapshot of the full reference dataset.
 *
 * <p>Built off to the side by the Refresh Scheduler and atomically swapped into
 * the Reference Data Store on success. Readers always see a complete, consistent
 * version of the data — never a partially-built state.
 *
 * <p>Source markers (architecture §3.1 Reference Data Store):
 * <ul>
 *   <li>{@code LIVE}             — loaded freshly from the SOAP provider.</li>
 *   <li>{@code DISK_RESTORE}    — restored from the local persisted JSON file.</li>
 *   <li>{@code BASELINE_FALLBACK} — loaded from the classpath-bundled baseline.</li>
 * </ul>
 */
public record Snapshot(
        List<Country> countries,
        Map<String, Country> byIsoCode,           // uppercase ISO → Country
        Map<String, List<Country>> byCurrencyCode, // uppercase ISO → Countries
        List<Continent> continents,
        List<Currency> currencies,
        List<Language> languages,
        Instant loadedAt,
        String contentHash,          // SHA-256 for ETag & delta audit
        Source source
) {
    public enum Source {LIVE, DISK_RESTORE, BASELINE_FALLBACK}

    /**
     * True when the snapshot is older than the configured staleness threshold.
     */
    public boolean isStale(long thresholdHours) {
        return loadedAt.isBefore(Instant.now().minusSeconds(thresholdHours * 3600));
    }

    /**
     * Weak ETag — derived from content hash + staleness so a client is never
     * served a 304 after the stale flag would have flipped.
     */
    public String eTag(long staleThresholdHours) {
        String staleSuffix = isStale(staleThresholdHours) ? "-stale" : "-fresh";
        return "W/\"" + contentHash.substring(0, 8) + staleSuffix + "\"";
    }
}
