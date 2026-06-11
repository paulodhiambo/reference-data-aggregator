package com.ncbaloop.rdas.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;


@JsonInclude(JsonInclude.Include.NON_NULL)
public record Country(
        String isoCode,
        String name,
        String capital,
        String phoneCode,
        Continent continent,
        Currency currency,
        String flagUrl,
        List<Language> languages
) {
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String isoCode, name, capital, phoneCode, flagUrl;
        private Continent continent;
        private Currency currency;
        private List<Language> languages;

        public Builder isoCode(String v) {
            this.isoCode = v;
            return this;
        }

        public Builder name(String v) {
            this.name = v;
            return this;
        }

        public Builder capital(String v) {
            this.capital = v;
            return this;
        }

        public Builder phoneCode(String v) {
            this.phoneCode = v;
            return this;
        }

        public Builder continent(Continent v) {
            this.continent = v;
            return this;
        }

        public Builder currency(Currency v) {
            this.currency = v;
            return this;
        }

        public Builder flagUrl(String v) {
            this.flagUrl = v;
            return this;
        }

        public Builder languages(List<Language> v) {
            this.languages = v;
            return this;
        }

        public Country build() {
            return new Country(isoCode, name, capital, phoneCode, continent, currency, flagUrl, languages);
        }
    }
}

