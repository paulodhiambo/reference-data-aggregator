package com.ncbaloop.rdas.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Currency reference — 3-letter ISO code (e.g. "KES") and display name.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Currency(String code, String name) {}
