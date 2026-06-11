package com.ncbaloop.rdas.service;

import com.ncbaloop.rdas.model.Country;
import com.ncbaloop.rdas.snapshot.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class DeltaAuditService {
    private static final Logger log = LoggerFactory.getLogger(DeltaAuditService.class);

    /**
     * Compares {@code previous} and {@code candidate} snapshots and logs
     * structured audit events for any reference-data changes detected.
     */
    public void audit(Snapshot previous, Snapshot candidate) {
        if (previous == null) {
            log.info("audit: first snapshot load, no delta comparison.");
            return;
        }

        if (previous.contentHash().equals(candidate.contentHash())) {
            log.debug("audit: hashes match ({}), no reference-data changes.",
                    candidate.contentHash().substring(0, 8));
            return;
        }

        if (previous.source() != Snapshot.Source.LIVE) {
            log.info("audit: skipping delta emission — previous source was {}, not LIVE.", previous.source());
            return;
        }

        log.info("audit: hash changed {} → {}; computing deltas …",
                previous.contentHash().substring(0, 8),
                candidate.contentHash().substring(0, 8));

        Map<String, Country> prevMap = new HashMap<>(previous.byIsoCode());
        Map<String, Country> newMap = new HashMap<>(candidate.byIsoCode());

        // Added countries
        for (Map.Entry<String, Country> e : newMap.entrySet()) {
            if (!prevMap.containsKey(e.getKey())) {
                emitEvent("ADD", e.getValue().isoCode(), null, null, null,
                        previous.contentHash(), candidate.contentHash());
            }
        }

        // Removed countries
        for (Map.Entry<String, Country> e : prevMap.entrySet()) {
            if (!newMap.containsKey(e.getKey())) {
                emitEvent("REMOVE", e.getValue().isoCode(), null, null, null,
                        previous.contentHash(), candidate.contentHash());
            }
        }

        // Changed fields
        for (Map.Entry<String, Country> e : newMap.entrySet()) {
            Country prev = prevMap.get(e.getKey());
            if (prev == null) continue;
            Country curr = e.getValue();
            compareField(e.getKey(), "name", prev.name(), curr.name(), previous.contentHash(), candidate.contentHash());
            compareField(e.getKey(), "capital", prev.capital(), curr.capital(), previous.contentHash(), candidate.contentHash());
            compareField(e.getKey(), "phoneCode", prev.phoneCode(), curr.phoneCode(), previous.contentHash(), candidate.contentHash());
            compareField(e.getKey(), "flagUrl", prev.flagUrl(), curr.flagUrl(), previous.contentHash(), candidate.contentHash());
            if (prev.currency() != null && curr.currency() != null)
                compareField(e.getKey(), "currency.code",
                        prev.currency().code(), curr.currency().code(),
                        previous.contentHash(), candidate.contentHash());
            if (prev.continent() != null && curr.continent() != null)
                compareField(e.getKey(), "continent.code",
                        prev.continent().code(), curr.continent().code(),
                        previous.contentHash(), candidate.contentHash());
        }
    }

    private void compareField(String iso, String field, String oldVal, String newVal,
                              String oldHash, String newHash) {
        if (oldVal == null && newVal == null) return;
        if (oldVal != null && oldVal.equals(newVal)) return;
        emitEvent("UPDATE", iso, field, oldVal, newVal, oldHash, newHash);
    }

    private void emitEvent(String action, String country, String field,
                           String oldVal, String newVal, String oldHash, String newHash) {
        if (field != null) {
            log.info("AUDIT action={} country={} field={} old=\"{}\" new=\"{}\" oldHash={} newHash={}",
                    action, country, field, oldVal, newVal,
                    oldHash.substring(0, 8), newHash.substring(0, 8));
        } else {
            log.info("AUDIT action={} country={} oldHash={} newHash={}",
                    action, country, oldHash.substring(0, 8), newHash.substring(0, 8));
        }
    }
}
