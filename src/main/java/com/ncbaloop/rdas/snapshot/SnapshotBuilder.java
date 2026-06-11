package com.ncbaloop.rdas.snapshot;

import com.ncbaloop.rdas.model.Continent;
import com.ncbaloop.rdas.model.Country;
import com.ncbaloop.rdas.model.Currency;
import com.ncbaloop.rdas.model.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds an immutable {@link Snapshot} from raw parsed data.
 * Computes the SHA-256 content hash, builds indexes, and enriches continents
 * and currencies on the country objects.
 */
@Component
public class SnapshotBuilder {
    private static final Logger log = LoggerFactory.getLogger(SnapshotBuilder.class);

    /**
     * Assembles a complete snapshot. Called by the Refresh Scheduler after all
     * 4 SOAP operations complete successfully. Throws if sanity checks fail.
     */
    public Snapshot build(
            List<Country> rawCountries,
            Map<String, String> continentNames,  // code → name
            Map<String, String> currencyNames,   // code → name
            List<Language> languages,
            Snapshot.Source source
    ) {
        // ── 1. Enrich continent and currency display names ──────────────────
        List<Country> enriched = rawCountries.stream().map(c -> {
            var continent = (c.continent() != null)
                    ? new Continent(
                    c.continent().code(),
                    continentNames.getOrDefault(c.continent().code(), c.continent().code()))
                    : null;
            var currency = (c.currency() != null)
                    ? new Currency(
                    c.currency().code(),
                    currencyNames.getOrDefault(c.currency().code(), c.currency().code()))
                    : null;
            return Country.builder()
                    .isoCode(c.isoCode())
                    .name(c.name())
                    .capital(c.capital())
                    .phoneCode(c.phoneCode())
                    .continent(continent)
                    .currency(currency)
                    .flagUrl(c.flagUrl())
                    .languages(c.languages() == null ? List.of() : List.copyOf(c.languages()))
                    .build();
        }).collect(Collectors.toList());

        // ── 2. Build indexes ────────────────────────────────────────────────
        Map<String, Country> byIsoCode = new HashMap<>();
        Map<String, List<Country>> byCurrencyCode = new HashMap<>();

        for (Country c : enriched) {
            if (c.isoCode() != null) byIsoCode.put(c.isoCode().toUpperCase(), c);
            if (c.currency() != null && c.currency().code() != null) {
                byCurrencyCode
                        .computeIfAbsent(c.currency().code().toUpperCase(), k -> new ArrayList<>())
                        .add(c);
            }
        }

        // ── 3. Master continent list from code→name map ─────────────────────
        List<Continent> continents = continentNames.entrySet().stream()
                .map(e -> new Continent(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(Continent::name))
                .toList();

        // ── 4. Master currency list from code→name map ──────────────────────
        List<Currency> currencies = currencyNames.entrySet().stream()
                .map(e -> new Currency(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(Currency::name))
                .toList();

        // ── 5. Compute SHA-256 content hash (sorted deterministically) ──────
        String hash = computeHash(enriched);

        log.info("Snapshot built: {} countries, hash={}, source={}", enriched.size(), hash.substring(0, 8), source);

        return new Snapshot(
                Collections.unmodifiableList(enriched),
                Collections.unmodifiableMap(byIsoCode),
                byCurrencyCode.entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                e -> Collections.unmodifiableList(e.getValue()))),
                continents,
                currencies,
                languages == null ? List.of() : List.copyOf(languages),
                Instant.now(),
                hash,
                source
        );
    }

    /**
     * SHA-256 of sorted country ISO codes + names (stable across JVM runs).
     */
    public static String computeHash(List<Country> countries) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            countries.stream()
                    .sorted(Comparator.comparing(Country::isoCode, Comparator.nullsLast(Comparator.naturalOrder())))
                    .forEach(c -> md.update((c.isoCode() + "|" + c.name()).getBytes(StandardCharsets.UTF_8)));
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
