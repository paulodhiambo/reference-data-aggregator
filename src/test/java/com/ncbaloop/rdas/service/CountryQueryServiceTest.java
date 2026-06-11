package com.ncbaloop.rdas.service;

import com.ncbaloop.rdas.model.Continent;
import com.ncbaloop.rdas.model.Country;
import com.ncbaloop.rdas.model.Currency;
import com.ncbaloop.rdas.model.Language;
import com.ncbaloop.rdas.model.PagedResponse;
import com.ncbaloop.rdas.snapshot.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CountryQueryServiceTest {

    private CountryQueryService queryService;
    private AtomicReference<Snapshot> store;

    @BeforeEach
    void setUp() {
        store = new AtomicReference<>();
        queryService = new CountryQueryService(store);

        // Setup mock countries
        Country kenya = new Country("KE", "Kenya", "Nairobi", "254",
                new Continent("AF", "Africa"),
                new Currency("KES", "Shillings"),
                "flagUrl",
                List.of(new Language("swa", "Swahili"))
        );

        Country germany = new Country("DE", "Germany", "Berlin", "49",
                new Continent("EU", "Europe"),
                new Currency("EUR", "Euro"),
                "flagUrl",
                List.of(new Language("deu", "German"))
        );

        Country uganda = new Country("UG", "Uganda", "Kampala", "256",
                new Continent("AF", "Africa"),
                new Currency("UGX", "Shillings"),
                "flagUrl",
                List.of(new Language("eng", "English"), new Language("swa", "Swahili"))
        );

        List<Country> countries = List.of(kenya, germany, uganda);
        Map<String, Country> byIso = Map.of("KE", kenya, "DE", germany, "UG", uganda);
        Map<String, List<Country>> byCurrency = Map.of(
                "KES", List.of(kenya),
                "EUR", List.of(germany),
                "UGX", List.of(uganda)
        );

        Snapshot snapshot = new Snapshot(
                countries, byIso, byCurrency,
                List.of(new Continent("AF", "Africa"), new Continent("EU", "Europe")),
                List.of(new Currency("KES", "Shillings"), new Currency("EUR", "Euro")),
                List.of(new Language("swa", "Swahili"), new Language("deu", "German")),
                Instant.now(),
                "dummyHash",
                Snapshot.Source.LIVE
        );

        store.set(snapshot);
    }

    @Test
    void search_FilterByName() {
        PagedResponse<Country> res = queryService.search("ya", null, null, null, "name", "asc", 0, 10);
        assertEquals(1, res.content().size());
        assertEquals("KE", res.content().get(0).isoCode());
    }

    @Test
    void search_FilterByContinent() {
        PagedResponse<Country> res = queryService.search(null, "AF", null, null, "name", "asc", 0, 10);
        assertEquals(2, res.content().size()); // Kenya and Uganda
    }

    @Test
    void search_FilterByLanguage() {
        PagedResponse<Country> res = queryService.search(null, null, null, "swa", "name", "asc", 0, 10);
        assertEquals(2, res.content().size()); // Kenya and Uganda speak Swahili
    }

    @Test
    void search_SortByNameDesc() {
        PagedResponse<Country> res = queryService.search(null, null, null, null, "name", "desc", 0, 10);
        assertEquals(3, res.content().size());
        assertEquals("UG", res.content().get(0).isoCode()); // Uganda (U) comes before Kenya (K) desc
    }

    @Test
    void search_Pagination() {
        PagedResponse<Country> res = queryService.search(null, null, null, null, "name", "asc", 0, 2);
        assertEquals(2, res.content().size());
        assertEquals("DE", res.content().get(0).isoCode()); // Germany first (G)
        assertEquals("KE", res.content().get(1).isoCode()); // Kenya second (K)

        PagedResponse<Country> nextPage = queryService.search(null, null, null, null, "name", "asc", 1, 2);
        assertEquals(1, nextPage.content().size());
        assertEquals("UG", nextPage.content().get(0).isoCode()); // Uganda third (U)
    }

    @Test
    void findByIsoCode_NormalizesCase() {
        Optional<Country> c = queryService.findByIsoCode("ke");
        assertTrue(c.isPresent());
        assertEquals("Kenya", c.get().name());
    }
}
