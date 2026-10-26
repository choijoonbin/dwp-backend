package com.dwp.services.platform.hrisconfiguration.integration;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;

/** Stable request and evidence digest helper. */
final class IntegrationCanonicalJson {

    private final ObjectMapper mapper;

    IntegrationCanonicalJson(ObjectMapper source) {
        mapper = source.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    String digest(Object value) {
        try {
            return IntegrationDomainPolicy.sha256(mapper.writeValueAsBytes(value));
        } catch (Exception invalid) {
            throw new IllegalArgumentException(
                    "Unable to canonicalize SYS-008 evidence", invalid);
        }
    }

    String digest(String... values) {
        return IntegrationDomainPolicy.sha256(String.join("\u001f", values)
                .getBytes(StandardCharsets.UTF_8));
    }
}
