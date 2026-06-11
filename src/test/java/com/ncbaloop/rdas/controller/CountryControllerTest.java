package com.ncbaloop.rdas.controller;

import com.ncbaloop.rdas.model.Continent;
import com.ncbaloop.rdas.model.Country;
import com.ncbaloop.rdas.model.Currency;
import com.ncbaloop.rdas.model.Language;
import com.ncbaloop.rdas.model.PagedResponse;
import com.ncbaloop.rdas.service.CountryQueryService;
import com.ncbaloop.rdas.snapshot.Snapshot;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CountryControllerTest {

    private MockMvc mockMvc;
    private CountryQueryService queryService;
    private Snapshot mockSnapshot;

    @BeforeEach
    void setUp() {
        queryService = Mockito.mock(CountryQueryService.class);
        
        mockSnapshot = new Snapshot(
                List.of(), Map.of(), Map.of(), List.of(), List.of(), List.of(),
                Instant.parse("2026-06-11T12:00:00Z"),
                "mockHash",
                Snapshot.Source.LIVE
        );
        Mockito.when(queryService.currentSnapshot()).thenReturn(mockSnapshot);

        // Standalone MockMvc setup bypassing @WebMvcTest auto-configurations
        mockMvc = MockMvcBuilders.standaloneSetup(new CountryController(queryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listCountries_Success() throws Exception {
        Country mockCountry = new Country("KE", "Kenya", "Nairobi", "254",
                new Continent("AF", "Africa"), new Currency("KES", "Shilling"),
                "flagUrl", List.of());
        PagedResponse<Country> mockResponse = PagedResponse.of(List.of(mockCountry), 0, 20);

        Mockito.when(queryService.search(any(), any(), any(), any(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(mockResponse);

        mockMvc.perform(get("/api/v1/countries")
                        .param("page", "0")
                        .param("size", "10")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataAsOf").value("2026-06-11T12:00:00Z"))
                .andExpect(jsonPath("$.data.content[0].isoCode").value("KE"))
                .andExpect(jsonPath("$.data.content[0].name").value("Kenya"));
    }

    @Test
    void getCountry_NotFound() throws Exception {
        Mockito.when(queryService.findByIsoCode("ZZ")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/countries/ZZ")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.title").value("Country Not Found"))
                .andExpect(jsonPath("$.detail").value("Country with ISO code 'ZZ' not found."));
    }

    @Test
    void listCurrencies_Success() throws Exception {
        Mockito.when(queryService.allCurrencies()).thenReturn(List.of(new Currency("USD", "Dollar")));

        mockMvc.perform(get("/api/v1/currencies")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("USD"));
    }

    @Test
    void getCountry_IsoCode_ValidationAnnotation() throws Exception {
        Method method = CountryController.class.getMethod("getCountry", String.class, String.class);
        Parameter parameter = method.getParameters()[0]; // isoCode
        Pattern pattern = parameter.getAnnotation(Pattern.class);
        
        assertNotNull(pattern, "isoCode must have @Pattern annotation");
        assertEquals("^[A-Za-z]{2}$", pattern.regexp());
        assertEquals("isoCode must be exactly 2 letters", pattern.message());
    }

    @Test
    void listCountries_SortBy_ValidationAnnotation() throws Exception {
        Method method = CountryController.class.getMethod("listCountries", 
                String.class, String.class, String.class, String.class, String.class, String.class, int.class, int.class, String.class);
        Parameter parameter = method.getParameters()[4]; // sortBy
        Pattern pattern = parameter.getAnnotation(Pattern.class);
        
        assertNotNull(pattern, "sortBy must have @Pattern annotation");
        assertEquals("name|isoCode|capital|phoneCode|continentCode|currencyCode", pattern.regexp());
    }

    @Test
    void listCountries_SortDir_ValidationAnnotation() throws Exception {
        Method method = CountryController.class.getMethod("listCountries", 
                String.class, String.class, String.class, String.class, String.class, String.class, int.class, int.class, String.class);
        Parameter parameter = method.getParameters()[5]; // sortDir
        Pattern pattern = parameter.getAnnotation(Pattern.class);
        
        assertNotNull(pattern, "sortDir must have @Pattern annotation");
        assertEquals("asc|desc", pattern.regexp());
    }

    @Test
    void countriesByCurrency_Code_ValidationAnnotation() throws Exception {
        Method method = CountryController.class.getMethod("countriesByCurrency", String.class);
        Parameter parameter = method.getParameters()[0]; // code
        Pattern pattern = parameter.getAnnotation(Pattern.class);
        
        assertNotNull(pattern, "currency code must have @Pattern annotation");
        assertEquals("^[A-Za-z]{3}$", pattern.regexp());
    }
}
