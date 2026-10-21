package com.dwp.services.time.absence.employment;

import com.dwp.audit.AuditEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Map;
import java.util.UUID;

/** Appends the structured evidence in the same transaction as projection/ledger CAS. */
public final class JdbcEmploymentAbsenceAuditSink implements EmploymentAbsenceAuditSink {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcEmploymentAbsenceAuditSink(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void append(
            long tenantId,
            UUID relationshipPublicId,
            UUID correlationId,
            String action,
            String outcome,
            Map<String, Object> evidence) {
        AuditEvent event = AuditEvent.builder()
                .tenantId(tenantId)
                .category("SYSTEM_EVENT")
                .action(action)
                .outcome(outcome)
                .severity("MEDIUM")
                .riskScore("DENIED".equals(outcome) ? 75 : 30)
                .actorType("SERVICE")
                .actorId(EmploymentAbsenceContract.CONSUMER)
                .sourceService("dwp-time-server")
                .sourceModule("TIM003")
                .targetType("WORK_RELATIONSHIP")
                .targetId(relationshipPublicId.toString())
                .reason(action)
                .correlationId(correlationId.toString())
                .metadata(Map.copyOf(evidence))
                .retentionClass("EXTENDED")
                .build()
                .sanitized();
        int inserted = jdbc.update("""
                INSERT INTO sys_audit_outbox (
                    outbox_id, event_id, tenant_id, payload, status,
                    attempt_count, available_at, created_at)
                VALUES (
                    :outboxId, :eventId, :tenantId, CAST(:payload AS jsonb),
                    'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, new MapSqlParameterSource()
                .addValue("outboxId", UUID.randomUUID())
                .addValue("eventId", event.eventId())
                .addValue("tenantId", tenantId)
                .addValue("payload", json(event)));
        if (inserted != 1) throw new IllegalStateException("audit append failed");
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("audit serialization failed", exception);
        }
    }
}
