package com.dwp.services.provider.audit;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void denied(
            String action,
            String targetType,
            String targetId,
            UUID tenantId,
            UUID organizationId,
            String correlationId,
            Object snapshot) {
        record("DENIED", action, targetType, targetId, tenantId, organizationId, correlationId, snapshot);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deniedSupportRequest(
            String action,
            UUID requestId,
            UUID tenantId,
            String correlationId,
            String reasonCode,
            String lifecycleState,
            long requestVersion) {
        record("DENIED", action, "SUPPORT_ACCESS_REQUEST", requestId.toString(), tenantId, null,
                correlationId, Map.of(
                        "decision", "DENY",
                        "policyId", "PROVIDER_SUPPORT_REQUEST_BOUNDARY_V1",
                        "reasonCode", reasonCode,
                        "lifecycleState", lifecycleState,
                        "requestVersion", requestVersion));
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
        try {
            ProviderRequestContext.Actor actor = ProviderRequestContext.require();
            jdbc.update("""
                    INSERT INTO prv_audit_events (
                        audit_event_id, actor_id, action, target_type, target_id,
                        outcome, correlation_id, redacted_snapshot, provider_operator_id,
                        provider_tenant_id, organization_id, event_category)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(), actor.userId(), action, targetType, targetId,
                    outcome, canonicalCorrelationId(correlationId), json(snapshot), actor.operatorId(), tenantId,
                    organizationId, category(action));
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Privileged audit evidence is temporarily unavailable.",
                    exception);
        }
    }

    public String canonicalCorrelationId(String externalCorrelationId) {
        String traceId = normalized(MDC.get("traceId"));
        if (canonicalTraceId(traceId)) return traceId.toLowerCase(java.util.Locale.ROOT);
        String external = normalized(externalCorrelationId);
        if (canonicalTraceId(external)) return external.toLowerCase(java.util.Locale.ROOT);
        if (external.isEmpty()) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(external.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public String opaqueReference(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private boolean canonicalTraceId(String value) {
        return value.matches("[0-9a-fA-F]{32}") && !value.matches("0{32}");
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private String category(String action) {
        if (action.startsWith("provider.support-")) return "PRIVILEGED_ACCESS";
        if (action.startsWith("provider.incident.")) return "SERVICE_HEALTH";
        if (action.startsWith("provider.operation") || action.startsWith("provider.maintenance")) {
            return "CHANGE_MANAGEMENT";
        }
        if (action.startsWith("provider.tenant")) return "TENANT_LIFECYCLE";
        if (action.startsWith("provider.data-governance.")) return "DATA_GOVERNANCE";
        if (action.startsWith("provider.feature-")) return "FEATURE_ROLLOUT";
        if (action.startsWith("provider.subscription-renewal.")) return "COMMERCIAL_GOVERNANCE";
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
