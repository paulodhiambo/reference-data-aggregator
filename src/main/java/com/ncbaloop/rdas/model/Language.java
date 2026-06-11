package com.ncbaloop.rdas.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Language reference — ISO 639 code (e.g. "eng") and display name.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Language(String code, String name) {}
