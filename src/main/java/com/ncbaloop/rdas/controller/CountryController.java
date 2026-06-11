package com.ncbaloop.rdas.controller;

import com.ncbaloop.rdas.model.Continent;
import com.ncbaloop.rdas.model.Country;
import com.ncbaloop.rdas.model.Currency;
import com.ncbaloop.rdas.model.Language;
import com.ncbaloop.rdas.model.PagedResponse;
import com.ncbaloop.rdas.service.CountryQueryService;
import com.ncbaloop.rdas.snapshot.Snapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Reference Data", description = "Country, currency, continent, and language reference data")
public class CountryController {
    private final CountryQueryService queryService;
    @Value("${rdas.cache.stale-threshold-hours}")
    private long staleThresholdHours;

    public CountryController(CountryQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/countries")
    @Operation(summary = "Search, filter, sort, and paginate countries")
    public ResponseEntity<?> listCountries(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String continent,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String language,
            @RequestParam(defaultValue = "name")
            @Pattern(regexp = "name|isoCode|capital|phoneCode|continentCode|currencyCode",
                    message = "sortBy must be one of: name, isoCode, capital, phoneCode, continentCode, currencyCode")
            String sortBy,
            @RequestParam(defaultValue = "asc")
            @Pattern(regexp = "asc|desc", message = "sortDir must be 'asc' or 'desc'")
            String sortDir,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {

        Snapshot snapshot = queryService.currentSnapshot();
        String eTag = snapshot.eTag(staleThresholdHours);

        if (eTag.equals(ifNoneMatch)) {
            return ResponseEntity.status(304).eTag(eTag).build();
        }

        PagedResponse<Country> result = queryService.search(
                name, continent, currency, language, sortBy, sortDir, page, size);

        return buildResponse(result, snapshot, eTag);
    }

    @GetMapping("/countries/{isoCode}")
    @Operation(summary = "Get full details for a single country by ISO code")
    public ResponseEntity<?> getCountry(
            @PathVariable @Pattern(regexp = "^[A-Za-z]{2}$", message = "isoCode must be exactly 2 letters")
            String isoCode,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {

        Snapshot snapshot = queryService.currentSnapshot();
        String eTag = snapshot.eTag(staleThresholdHours);

        if (eTag.equals(ifNoneMatch)) return ResponseEntity.status(304).eTag(eTag).build();

        Country country = queryService.findByIsoCode(isoCode)
                .orElseThrow(() -> new CountryNotFoundException(isoCode));

        return buildResponse(country, snapshot, eTag);
    }


    @GetMapping("/currencies")
    @Operation(summary = "List all currencies (for filter discovery)")
    public ResponseEntity<List<Currency>> listCurrencies() {
        return ResponseEntity.ok(queryService.allCurrencies());
    }

    @GetMapping("/currencies/{code}/countries")
    @Operation(summary = "List all countries using a given currency")
    public ResponseEntity<List<Country>> countriesByCurrency(
            @PathVariable @Pattern(regexp = "^[A-Za-z]{3}$", message = "currency code must be exactly 3 letters")
            String code) {
        return ResponseEntity.ok(queryService.findByCurrency(code));
    }

    @GetMapping("/continents")
    @Operation(summary = "List all continents (for filter discovery)")
    public ResponseEntity<List<Continent>> listContinents() {
        return ResponseEntity.ok(queryService.allContinents());
    }

    @GetMapping("/languages")
    @Operation(summary = "List all languages (for filter discovery)")
    public ResponseEntity<List<Language>> listLanguages() {
        return ResponseEntity.ok(queryService.allLanguages());
    }

    private <T> ResponseEntity<Map<String, Object>> buildResponse(T data, Snapshot snapshot, String eTag) {
        boolean stale = snapshot.isStale(staleThresholdHours);
        var body = Map.<String, Object>of(
                "data", data,
                "dataAsOf", snapshot.loadedAt(),
                "stale", stale
        );
        var builder = ResponseEntity.ok().eTag(eTag);
        if (stale) builder.header("X-RDAS-Stale", "true");
        return builder.body(body);
    }

    public static class CountryNotFoundException extends RuntimeException {
        public CountryNotFoundException(String code) {
            super("Country with ISO code '" + code.toUpperCase() + "' not found.");
        }
    }
}
