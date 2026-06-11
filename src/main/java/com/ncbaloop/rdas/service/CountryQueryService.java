package com.ncbaloop.rdas.service;

import com.ncbaloop.rdas.model.Continent;
import com.ncbaloop.rdas.model.Country;
import com.ncbaloop.rdas.model.Currency;
import com.ncbaloop.rdas.model.Language;
import com.ncbaloop.rdas.model.PagedResponse;
import com.ncbaloop.rdas.snapshot.Snapshot;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Pure in-memory query engine over the current snapshot.
 *
 * <p>All filtering, sorting, and pagination is applied in memory against the
 * immutable snapshot — no SOAP calls on the request path.
 * Results are memoized in Caffeine (L2 cache) keyed by the full criteria tuple.
 */
@Service
public class CountryQueryService {
    private final AtomicReference<Snapshot> store;

    public CountryQueryService(AtomicReference<Snapshot> store) {
        this.store = store;
    }

    /**
     * Search, filter, sort, and paginate countries.
     *
     * @param name      case-insensitive *contains* on country name (optional)
     * @param continent continent code or display name (optional)
     * @param currency  currency code or display name (optional)
     * @param language  language code or display name (optional)
     * @param sortBy    field name (whitelisted in controller)
     * @param sortDir   "asc" or "desc"
     * @param page      zero-based page index
     * @param size      page size (1–100)
     */
    @Cacheable(value = "countryQuery",
            key = "#name + '|' + #continent + '|' + #currency + '|' + #language + '|' + #sortBy + '|' + #sortDir + '|' + #page + '|' + #size")
    public PagedResponse<Country> search(
            String name, String continent, String currency, String language,
            String sortBy, String sortDir, int page, int size) {

        List<Country> filtered = currentSnapshot().countries().stream()
                .filter(byName(name))
                .filter(byContinent(continent))
                .filter(byCurrency(currency))
                .filter(byLanguage(language))
                .sorted(comparator(sortBy, sortDir))
                .toList();

        return PagedResponse.of(filtered, page, size);
    }

    /**
     * Fetch a single country by ISO code (case-insensitive).
     */
    public Optional<Country> findByIsoCode(String isoCode) {
        return Optional.ofNullable(currentSnapshot().byIsoCode().get(isoCode.toUpperCase()));
    }

    /**
     * All countries that use the given currency code.
     */
    public List<Country> findByCurrency(String currencyCode) {
        return currentSnapshot().byCurrencyCode()
                .getOrDefault(currencyCode.toUpperCase(), List.of());
    }

    public List<Continent> allContinents() {
        return currentSnapshot().continents();
    }

    public List<Currency> allCurrencies() {
        return currentSnapshot().currencies();
    }

    public List<Language> allLanguages() {
        return currentSnapshot().languages();
    }

    public Snapshot currentSnapshot() {
        Snapshot s = store.get();
        if (s == null) throw new SnapshotNotReadyException();
        return s;
    }

    /**
     * Called by RefreshService after a successful snapshot swap.
     */
    @CacheEvict(value = "countryQuery", allEntries = true)
    public void evictQueryCache() {
    }

    private Predicate<Country> byName(String name) {
        if (name == null || name.isBlank()) return c -> true;
        String lower = name.toLowerCase();
        return c -> c.name() != null && c.name().toLowerCase().contains(lower);
    }

    private Predicate<Country> byContinent(String continent) {
        if (continent == null || continent.isBlank()) return c -> true;
        String upper = continent.toUpperCase();
        return c -> c.continent() != null && (
                upper.equals(c.continent().code()) ||
                        (c.continent().name() != null && c.continent().name().toUpperCase().contains(upper)));
    }

    private Predicate<Country> byCurrency(String currency) {
        if (currency == null || currency.isBlank()) return c -> true;
        String upper = currency.toUpperCase();
        return c -> c.currency() != null && (
                upper.equals(c.currency().code()) ||
                        (c.currency().name() != null && c.currency().name().toUpperCase().contains(upper)));
    }

    private Predicate<Country> byLanguage(String language) {
        if (language == null || language.isBlank()) return c -> true;
        String upper = language.toUpperCase();
        return c -> c.languages() != null && c.languages().stream().anyMatch(l ->
                upper.equals(l.code() != null ? l.code().toUpperCase() : null) ||
                        (l.name() != null && l.name().toUpperCase().contains(upper)));
    }

    private Comparator<Country> comparator(String sortBy, String sortDir) {
        Comparator<Country> base = switch (sortBy == null ? "name" : sortBy.toLowerCase()) {
            case "isocode" -> Comparator.comparing(Country::isoCode, nullSafe());
            case "capital" -> Comparator.comparing(Country::capital, nullSafe());
            case "phonecode" -> Comparator.comparing(Country::phoneCode, nullSafe());
            case "continentcode" ->
                    Comparator.comparing(c -> c.continent() == null ? "" : c.continent().code(), nullSafe());
            case "currencycode" ->
                    Comparator.comparing(c -> c.currency() == null ? "" : c.currency().code(), nullSafe());
            default -> Comparator.comparing(Country::name, nullSafe());
        };
        return "desc".equalsIgnoreCase(sortDir) ? base.reversed() : base;
    }

    private static Comparator<String> nullSafe() {
        return Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);
    }

    public static class SnapshotNotReadyException extends RuntimeException {
        public SnapshotNotReadyException() {
            super("Reference data snapshot is not yet available. Please retry shortly.");
        }
    }
}
