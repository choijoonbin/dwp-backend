package com.dwp.core.audit;

import com.dwp.audit.AuditEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.UUID;

/** Writes audit events to the service-local durable outbox in the caller transaction. */
public class AuditOutboxRecorder {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String serviceName;
    private final String serviceInstance;
    private final String environment;

    public AuditOutboxRecorder(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            String serviceName,
            String serviceInstance,
            String environment) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.serviceName = serviceName;
        this.serviceInstance = serviceInstance;
        this.environment = environment;
    }

    public UUID record(AuditEvent event) {
        AuditEvent normalized = event
                .withSource(serviceName, serviceInstance, environment)
                .sanitized();
        UUID outboxId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_audit_outbox (
                    outbox_id, event_id, tenant_id, payload, status,
                    attempt_count, available_at, created_at)
                VALUES (
                    :outboxId, :eventId, :tenantId, CAST(:payload AS jsonb),
                    'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (event_id) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("outboxId", outboxId)
                        .addValue("eventId", normalized.eventId())
                        .addValue("tenantId", normalized.tenantId())
                        .addValue("payload", json(normalized)));
        return normalized.eventId();
    }

    private String json(AuditEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Audit event serialization failed.", exception);
        }
    }
}
