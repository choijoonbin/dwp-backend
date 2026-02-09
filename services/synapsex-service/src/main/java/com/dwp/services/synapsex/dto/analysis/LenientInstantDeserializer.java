package com.dwp.services.synapsex.dto.analysis;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * ISO-8601 문자열을 Instant로 역직렬화. 타임존 없을 때 UTC로 가정.
 * Aura/Python에서 "2026-02-09T12:15:45.155528" 형태로 전송해도 수용.
 */
public class LenientInstantDeserializer extends JsonDeserializer<Instant> {

    @Override
    public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String value = p.getText();
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException e) {
            // 타임존 없음: LocalDateTime으로 파싱 후 UTC Instant로 변환
            try {
                return LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException e2) {
                throw new IllegalArgumentException("Invalid instant format: " + trimmed, e2);
            }
        }
    }
}
