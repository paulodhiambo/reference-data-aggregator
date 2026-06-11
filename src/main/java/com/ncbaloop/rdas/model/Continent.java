package com.ncbaloop.rdas.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Continent reference — code (e.g. "AF") and display name (e.g. "Africa").
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Continent(String code, String name) {}
