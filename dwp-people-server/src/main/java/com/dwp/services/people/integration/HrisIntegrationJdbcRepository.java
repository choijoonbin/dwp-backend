package com.dwp.services.people.integration;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;

abstract class HrisIntegrationJdbcRepository {
    protected final NamedParameterJdbcTemplate jdbc;

    HrisIntegrationJdbcRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    protected MapSqlParameterSource params(Long tenantId, Long actorId) {
        return new MapSqlParameterSource("tenantId", tenantId).addValue("actorId", actorId);
    }

    protected long requiredLong(String sql, MapSqlParameterSource parameters) {
        Long value = jdbc.queryForObject(sql, parameters, Long.class);
        if (value == null) throw new IllegalStateException("Database did not return an identifier.");
        return value;
    }

    protected Date date(LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }

    protected Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    protected String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    protected String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    protected String canonicalOrganizationType(String value) {
        if (value == null || value.isBlank()) return "CUSTOM";
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9._-]+", "_");
        if (normalized.isEmpty()) return "CUSTOM";
        if (!Character.isLetter(normalized.charAt(0))) normalized = "TYPE_" + normalized;
        return normalized.length() > 100 ? normalized.substring(0, 100) : normalized;
    }

    protected String humanizeType(String typeKey) {
        String normalized = typeKey.toLowerCase(Locale.ROOT).replaceAll("[._-]+", " ");
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

}
