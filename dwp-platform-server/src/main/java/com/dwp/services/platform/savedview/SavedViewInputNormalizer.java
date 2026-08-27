package com.dwp.services.platform.savedview;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class SavedViewInputNormalizer {

    private static final int MAX_CONFIGURATION_BYTES = 16_384;
    private static final Pattern SURFACE = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,79}$");
    private static final Pattern IDEMPOTENCY =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{7,119}$");
    private static final Set<String> SCOPES = Set.of("PERSONAL", "TEAM", "TENANT");

    private final ObjectMapper objectMapper;

    SavedViewInputNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String name(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw invalid("Invalid saved-view name.");
        }
        return normalized;
    }

    Map<String, Object> configuration(Map<String, Object> value) {
        Map<String, Object> normalized = value == null ? Map.of() : new LinkedHashMap<>(value);
        try {
            if (objectMapper.writeValueAsBytes(normalized).length > MAX_CONFIGURATION_BYTES) {
                throw invalid("Saved-view configuration exceeds the 16 KiB limit.");
            }
            return normalized;
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Saved-view configuration is not valid JSON.",
                    exception);
        }
    }

    String surface(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!SURFACE.matcher(normalized).matches()) {
            throw invalid("Invalid saved-view surface key.");
        }
        return normalized;
    }

    String scope(String value) {
        String normalized = code(value);
        if (!SCOPES.contains(normalized)) throw invalid("Invalid saved-view scope.");
        return normalized;
    }

    String fingerprint(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[0-9a-f]{64}$")) {
            throw invalid("A valid ownership preview fingerprint is required.");
        }
        return normalized;
    }

    String idempotencyKey(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!IDEMPOTENCY.matcher(normalized).matches()) {
            throw invalid("Invalid ownership transfer idempotency key.");
        }
        return normalized;
    }

    String required(String value, int min, int max, String message) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < min || normalized.length() > max) throw invalid(message);
        return normalized;
    }

    String code(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    BaseException nameConflict(DataIntegrityViolationException exception) {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "A saved view with this name already exists in the selected scope.",
                exception);
    }
}
