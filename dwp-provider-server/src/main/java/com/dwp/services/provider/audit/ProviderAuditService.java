package com.dwp.services.provider.audit;

import com.dwp.services.provider.security.ProviderRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ProviderAuditService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ProviderAuditService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void success(
            String action,
            String targetType,
            String targetId,
            String correlationId,
            Object snapshot) {
        jdbc.update("""
                INSERT INTO prv_audit_events (
                    audit_event_id, actor_id, action, target_type, target_id,
                    outcome, correlation_id, redacted_snapshot)
                VALUES (?, ?, ?, ?, ?, 'SUCCESS', ?, CAST(? AS jsonb))
                """,
                UUID.randomUUID(), ProviderRequestContext.require().userId(), action,
                targetType, targetId, correlationId, json(snapshot));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Provider audit serialization failed.", exception);
        }
    }
}
