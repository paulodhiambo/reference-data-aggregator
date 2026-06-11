package com.ncbaloop.rdas.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ncbaloop.rdas.client.CountrySoapClient;
import com.ncbaloop.rdas.model.Country;
import com.ncbaloop.rdas.model.Language;
import com.ncbaloop.rdas.snapshot.Snapshot;
import com.ncbaloop.rdas.snapshot.SnapshotBuilder;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates snapshot lifecycle: startup warm-up, scheduled refresh,
 * fallback chain, delta audit, and Prometheus metrics.
 *
 */
@Service
public class RefreshService {
    private static final Logger log = LoggerFactory.getLogger(RefreshService.class);
    private final AtomicReference<Snapshot> store;
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private final AtomicBoolean pendingRetry = new AtomicBoolean(false);
    private final CountrySoapClient soapClient;
    private final SnapshotBuilder snapshotBuilder;
    private final DeltaAuditService deltaAudit;
    private final CountryQueryService queryService;
    private final ObjectMapper mapper;
    private final String snapshotPath;
    private final Counter refreshSuccess;
    private final Counter refreshFailure;

    public RefreshService(
            AtomicReference<Snapshot> store,
            CountrySoapClient soapClient,
            SnapshotBuilder snapshotBuilder,
            DeltaAuditService deltaAudit,
            CountryQueryService queryService,
            MeterRegistry meterRegistry,
            @Value("${rdas.cache.snapshot-path}") String snapshotPath,
            @Value("${rdas.cache.stale-threshold-hours}") long staleThresholdHours) {

        this.store = store;
        this.soapClient = soapClient;
        this.snapshotBuilder = snapshotBuilder;
        this.deltaAudit = deltaAudit;
        this.queryService = queryService;
        this.snapshotPath = snapshotPath;

        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        this.refreshSuccess = Counter.builder("rdas.snapshot.refresh")
                .tag("outcome", "success").register(meterRegistry);
        this.refreshFailure = Counter.builder("rdas.snapshot.refresh")
                .tag("outcome", "failure").register(meterRegistry);

        // Expose snapshot-age gauge
        Gauge.builder("rdas.snapshot.age.seconds", store,
                        s -> s.get() == null ? -1 :
                                Instant.now().getEpochSecond() - s.get().loadedAt().getEpochSecond())
                .register(meterRegistry);
    }

    @PostConstruct
    @Async
    public void warmUp() {
        log.info("Starting asynchronous snapshot warm-up …");
        if (!doRefresh(false)) {
            loadFromFallbackChain();
        }
    }

    @Scheduled(cron = "${rdas.cache.refresh-cron}")
    public void scheduledRefresh() {
        if (!doRefresh(true)) {
            pendingRetry.set(true);
        }
    }

    @Scheduled(cron = "${rdas.cache.retry-cron}")
    public void retryRefresh() {
        if (pendingRetry.get()) {
            log.info("Retry cadence: attempting snapshot refresh …");
            if (doRefresh(true)) {
                pendingRetry.set(false);
            }
        }
    }

    /**
     * Attempts to fetch a fresh snapshot from SOAP. Returns {@code true} on success.
     */
    @CircuitBreaker(name = "soap-client")
    @RateLimiter(name = "soap-client")
    @Retry(name = "soap-client")
    private boolean doRefresh(boolean isScheduled) {
        if (!refreshing.compareAndSet(false, true)) {
            log.debug("Refresh skipped — another refresh is already in progress.");
            return true;
        }
        try {
            log.info("Fetching snapshot from SOAP provider (scheduled={}) …", isScheduled);

            List<Country> rawCountries = soapClient.fetchAllCountries();
            Map<String, String> continentNames = soapClient.fetchContinentNames();
            Map<String, String> currencyNames = soapClient.fetchCurrencyNames();
            List<Language> languages = soapClient.fetchLanguages();

            sanityCheck(rawCountries);

            Snapshot candidate = snapshotBuilder.build(
                    rawCountries, continentNames, currencyNames, languages, Snapshot.Source.LIVE);

            Snapshot previous = store.getAndSet(candidate);
            persistToDisk(candidate);
            deltaAudit.audit(previous, candidate);
            queryService.evictQueryCache();  // invalidate Caffeine L2 after swap

            refreshSuccess.increment();
            log.info("Snapshot refreshed: {} countries, hash={}", candidate.countries().size(),
                    candidate.contentHash().substring(0, 8));
            return true;

        } catch (Exception e) {
            refreshFailure.increment();
            log.error("Snapshot refresh failed: {}", e.getMessage(), e);
            return false;
        } finally {
            refreshing.set(false);
        }
    }

    /**
     * Tries disk restore → classpath baseline. Sets the source marker accordingly.
     */
    private void loadFromFallbackChain() {
        if (tryDiskRestore()) return;
        tryBaselineFallback();
    }

    private boolean tryDiskRestore() {
        try {
            Path p = Path.of(snapshotPath);
            if (!Files.exists(p)) return false;
            String json = Files.readString(p);
            Snapshot s = mapper.readValue(json, Snapshot.class);
            Snapshot restored = rebuildWithSource(s);
            store.set(restored);
            log.warn("Snapshot loaded from local disk (DISK_RESTORE): {} countries, loadedAt={}",
                    restored.countries().size(), restored.loadedAt());
            return true;
        } catch (Exception e) {
            log.warn("Disk restore failed ({}), falling back to baseline.", e.getMessage());
            return false;
        }
    }

    private void tryBaselineFallback() {
        try {
            ClassPathResource res = new ClassPathResource("baseline-countries.json");
            try (InputStream is = res.getInputStream()) {
                List<Country> baseline = mapper.readValue(is, new TypeReference<>() {
                });
                Snapshot s = snapshotBuilder.build(baseline, Map.of(), Map.of(), List.of(),
                        Snapshot.Source.BASELINE_FALLBACK);
                store.set(s);
                log.warn("Snapshot loaded from classpath baseline (BASELINE_FALLBACK): {} countries", s.countries().size());
            }
        } catch (Exception e) {
            log.error("All fallback mechanisms exhausted — snapshot is empty. Service will be NOT READY.", e);
        }
    }

    private void persistToDisk(Snapshot s) {
        try {
            Path p = Path.of(snapshotPath);
            Files.createDirectories(p.getParent() == null ? Path.of(".") : p.getParent());
            mapper.writeValue(p.toFile(), s);
            log.debug("Snapshot persisted to disk: {}", snapshotPath);
        } catch (IOException e) {
            log.warn("Failed to persist snapshot to disk ({}): {}", snapshotPath, e.getMessage());
        }
    }

    private void sanityCheck(List<Country> countries) {
        if (countries == null || countries.isEmpty()) {
            throw new IllegalStateException("SOAP returned empty country list — rejecting candidate snapshot.");
        }
        Snapshot current = store.get();
        if (current != null && !current.countries().isEmpty()) {
            double ratio = (double) countries.size() / current.countries().size();
            if (ratio < 0.8 || ratio > 1.2) {
                throw new IllegalStateException(
                        "Country count drift too large: was %d, now %d — rejecting.".formatted(
                                current.countries().size(), countries.size()));
            }
        }
        // Spot-check well-known ISO codes
        List<String> knownCodes = List.of("KE", "US", "DE", "GB");
        List<String> isoCodes = countries.stream()
                .map(Country::isoCode).filter(Objects::nonNull).map(String::toUpperCase).toList();
        for (String code : knownCodes) {
            if (!isoCodes.contains(code)) {
                log.warn("Sanity check: well-known country {} not found in response", code);
            }
        }
    }

    /**
     * Rebuild a snapshot replacing only the source marker (preserves all data).
     */
    private Snapshot rebuildWithSource(Snapshot s) {
        return new Snapshot(s.countries(), s.byIsoCode(), s.byCurrencyCode(),
                s.continents(), s.currencies(), s.languages(),
                s.loadedAt(), s.contentHash(), Snapshot.Source.DISK_RESTORE);
    }
}
