package com.dwp.services.payroll.statutory.pay005;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.security.MessageDigest;

final class Pay005Canonical {

    private final ObjectMapper mapper;

    Pay005Canonical(ObjectMapper mapper) {
        this.mapper = mapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    String digest(Object value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(mapper.writeValueAsBytes(value)));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "PAY-005 canonicalization failed", exception);
        }
    }
}
