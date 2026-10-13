package com.dwp.services.payroll.configuration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Content-addressing for formula artifacts, validation, simulation and commands. */
final class PayrollConfigurationCanonical {

    private final ObjectMapper mapper;

    PayrollConfigurationCanonical(ObjectMapper objectMapper) {
        this.mapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    String digest(Object value) {
        try {
            return sha256(mapper.writeValueAsBytes(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("value cannot be canonicalized", exception);
        }
    }

    static String textDigest(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
