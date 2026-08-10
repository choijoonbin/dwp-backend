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
        record("SUCCESS", action, targetType, targetId, null, null, correlationId, snapshot);
    }

    public void success(
            String action,
            String targetType,
            String targetId,
            UUID tenantId,
            UUID organizationId,
            String correlationId,
            Object snapshot) {
        record("SUCCESS", action, targetType, targetId, tenantId, organizationId, correlationId, snapshot);
    }

    public void failed(
            String action,
            String targetType,
            String targetId,
            UUID tenantId,
            UUID organizationId,
            String correlationId,
            Object snapshot) {
        record("FAILED", action, targetType, targetId, tenantId, organizationId, correlationId, snapshot);
    }

    private void record(
            String outcome,
            String action,
            String targetType,
            String targetId,
            UUID tenantId,
            UUID organizationId,
            String correlationId,
            Object snapshot) {
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        jdbc.update("""
                INSERT INTO prv_audit_events (
                    audit_event_id, actor_id, action, target_type, target_id,
                    outcome, correlation_id, redacted_snapshot, provider_operator_id,
                    provider_tenant_id, organization_id, event_category)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)
                """,
                UUID.randomUUID(), actor.userId(), action, targetType, targetId,
                outcome, correlationId, json(snapshot), actor.operatorId(), tenantId, organizationId,
                category(action));
    }

    private String category(String action) {
        if (action.startsWith("provider.support-")) return "PRIVILEGED_ACCESS";
        if (action.startsWith("provider.incident.")) return "SERVICE_HEALTH";
        if (action.startsWith("provider.operation") || action.startsWith("provider.maintenance")) {
            return "CHANGE_MANAGEMENT";
        }
        if (action.startsWith("provider.tenant")) return "TENANT_LIFECYCLE";
        return "ADMINISTRATION";
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Provider audit serialization failed.", exception);
        }
    }
}
