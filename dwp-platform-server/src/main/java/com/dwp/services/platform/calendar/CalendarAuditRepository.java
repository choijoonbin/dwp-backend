package com.dwp.services.platform.calendar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

/** Persists calendar audit snapshots without coupling aggregate writes to JSON encoding. */
final class CalendarAuditRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    CalendarAuditRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    void audit(
            Long tenantId,
            Long actorId,
            UUID eventId,
            String action,
            String correlationId,
            Map<String, Object> before,
            Map<String, Object> after) {
        jdbc.update(
                CalendarSql01.AUDIT_INSERT_CAL_AUDIT_EVENTS,
                tenantId,
                eventId,
                action,
                actorId,
                correlationId,
                json(before),
                json(after));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Calendar data could not be serialized.", exception);
        }
    }
}
