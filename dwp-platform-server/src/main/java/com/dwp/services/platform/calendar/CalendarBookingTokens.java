package com.dwp.services.platform.calendar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

final class CalendarBookingTokens {

    private static final int TOKEN_BYTES = 32;
    private final SecureRandom random;
    private final ObjectMapper canonicalJson;

    CalendarBookingTokens(ObjectMapper json) {
        this(new SecureRandom(), json.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true));
    }

    CalendarBookingTokens(SecureRandom random, ObjectMapper canonicalJson) {
        this.random = random;
        this.canonicalJson = canonicalJson;
    }

    Material create() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new Material(raw, sha256(raw));
    }

    String fingerprint(Object value) {
        try {
            return sha256(canonicalJson.writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Calendar booking request is invalid.", exception);
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    record Material(String raw, String hash) { }
}
