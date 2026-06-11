package com.ncbaloop.rdas.client;

import com.ncbaloop.rdas.model.Continent;
import com.ncbaloop.rdas.model.Country;
import com.ncbaloop.rdas.model.Currency;
import com.ncbaloop.rdas.model.Language;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anti-Corruption Layer (ACL) — the only class in the codebase that knows SOAP exists.
 *
 * <p>Hand-builds document/literal SOAP 1.1 envelopes, posts them over HTTP,
 * detects {@code soap:Fault} responses, and parses results with a hardened,
 * XXE-safe DOM parser.  The provider's naming quirks (sISOCode, sCapitalCity, …)
 * never escape this layer — all outbound types are clean internal records.
 */
@Component
public class CountrySoapClient {
    private static final Logger log = LoggerFactory.getLogger(CountrySoapClient.class);
    private static final String NS = "http://www.oorsprong.org/websamples.countryinfo";
    private static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    private final HttpClient http;
    private final String endpoint;
    private final Duration readTimeout;
    private final DocumentBuilderFactory dbf;

    public CountrySoapClient(
            @Value("${rdas.soap.endpoint}") String endpoint,
            @Value("${rdas.soap.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${rdas.soap.read-timeout-ms}") long readTimeoutMs) {
        this.endpoint = endpoint;
        this.readTimeout = Duration.ofMillis(readTimeoutMs);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();

        // ── XXE-safe DocumentBuilderFactory ────────────────────────────────
        this.dbf = DocumentBuilderFactory.newInstance();
        this.dbf.setNamespaceAware(true);
        try {
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "");
            dbf.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "");
        } catch (Exception e) {
            log.warn("Some XXE hardening features not supported by this XML parser: {}", e.getMessage());
        }
    }

    /**
     * SOAP Operation 1: FullCountryInfoAllCountries
     */
    public List<Country> fetchAllCountries() throws Exception {
        String body = soapEnvelope("FullCountryInfoAllCountries");
        Document doc = post(body);
        return parseCountries(doc);
    }

    /**
     * SOAP Operation 2: ListOfContinentsByName → code→name map
     */
    public Map<String, String> fetchContinentNames() throws Exception {
        String body = soapEnvelope("ListOfContinentsByName");
        Document doc = post(body);
        return parseContinents(doc);
    }

    /**
     * SOAP Operation 3: ListOfCurrenciesByName → code→name map
     */
    public Map<String, String> fetchCurrencyNames() throws Exception {
        String body = soapEnvelope("ListOfCurrenciesByName");
        Document doc = post(body);
        return parseCurrencies(doc);
    }

    /**
     * SOAP Operation 4: ListOfLanguagesByName
     */
    public List<Language> fetchLanguages() throws Exception {
        String body = soapEnvelope("ListOfLanguagesByName");
        Document doc = post(body);
        return parseLanguages(doc);
    }

    private Document post(String soapBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(readTimeout)
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", "")
                .POST(HttpRequest.BodyPublishers.ofString(soapBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SoapException("SOAP call failed with HTTP " + response.statusCode());
        }

        Document doc = parseXml(response.body());
        checkFault(doc);
        return doc;
    }

    private Document parseXml(String xml) throws Exception {
        DocumentBuilder db = dbf.newDocumentBuilder();
        db.setErrorHandler(null);
        return db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private void checkFault(Document doc) {
        NodeList faults = doc.getElementsByTagNameNS(SOAP_NS, "Fault");
        if (faults.getLength() > 0) {
            Element fault = (Element) faults.item(0);
            String msg = text(fault, "faultstring");
            throw new SoapException("SOAP Fault: " + msg);
        }
    }

    private List<Country> parseCountries(Document doc) {
        NodeList nodes = doc.getElementsByTagNameNS(NS, "tCountryInfo");
        List<Country> result = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String isoCode = text(el, "sISOCode");
            String name = text(el, "sName");
            String capital = text(el, "sCapitalCity");
            String phone = text(el, "sPhoneCode");
            String contCode = text(el, "sContinentCode");
            String currCode = text(el, "sCurrencyISOCode");
            String flagUrl = text(el, "sCountryFlag");

            List<Language> langs = new ArrayList<>();
            NodeList langNodes = el.getElementsByTagNameNS(NS, "tLanguage");
            for (int j = 0; j < langNodes.getLength(); j++) {
                Element lEl = (Element) langNodes.item(j);
                langs.add(new Language(text(lEl, "sISOCode"), text(lEl, "sName")));
            }

            result.add(Country.builder()
                    .isoCode(isoCode)
                    .name(name)
                    .capital(capital)
                    .phoneCode(phone)
                    .continent(new Continent(contCode, null))
                    .currency(new Currency(currCode, null))
                    .flagUrl(flagUrl)
                    .languages(langs)
                    .build());
        }
        return result;
    }

    private Map<String, String> parseContinents(Document doc) {
        NodeList nodes = doc.getElementsByTagNameNS(NS, "tContinent");
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            map.put(text(el, "sCode"), text(el, "sName"));
        }
        return map;
    }

    private Map<String, String> parseCurrencies(Document doc) {
        NodeList nodes = doc.getElementsByTagNameNS(NS, "tCurrency");
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            map.put(text(el, "sISOCode"), text(el, "sName"));
        }
        return map;
    }

    private List<Language> parseLanguages(Document doc) {
        NodeList nodes = doc.getElementsByTagNameNS(NS, "tLanguage");
        List<Language> result = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            result.add(new Language(text(el, "sISOCode"), text(el, "sName")));
        }
        return result;
    }

    private String text(Element parent, String localName) {
        NodeList nl = parent.getElementsByTagNameNS(NS, localName);
        if (nl.getLength() == 0) return null;
        return nl.item(0).getTextContent();
    }

    private String soapEnvelope(String operation) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <%s xmlns="%s">%s</%s>
                  </soap:Body>
                </soap:Envelope>
                """.formatted(operation, NS, "", operation);
    }

    /**
     * Checked exception for SOAP-level errors.
     */
    public static class SoapException extends RuntimeException {
        public SoapException(String msg) {
            super(msg);
        }
    }
}
