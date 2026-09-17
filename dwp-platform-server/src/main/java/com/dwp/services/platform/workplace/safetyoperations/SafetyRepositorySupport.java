package com.dwp.services.platform.workplace.safetyoperations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

abstract class SafetyRepositorySupport {
    protected final JdbcTemplate jdbc;
    protected final ObjectMapper mapper;

    protected SafetyRepositorySupport(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    protected String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Safety operations value cannot be serialized.", exception);
        }
    }

    protected <T> T value(String json, Class<T> type) {
        try { return mapper.readValue(json, type); }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted safety operations value is invalid.", exception);
        }
    }

    protected <T> List<T> list(String json, Class<T> type) {
        try {
            return mapper.readValue(json,
                    mapper.getTypeFactory().constructCollectionType(List.class, type));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted safety operations list is invalid.", exception);
        }
    }

    protected java.util.Map<String, Object> map(String json) {
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted safety report is invalid.", exception);
        }
    }

    protected static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    protected static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    protected static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    protected static String correlation(String value) {
        if (value == null || value.isBlank()) return UUID.randomUUID().toString();
        String result = value.trim();
        if (result.length() > 160) throw new IllegalArgumentException("correlationId");
        return result;
    }
}
