package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ApprovalGovernanceQuerySupport {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    ApprovalGovernanceQuerySupport(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    Map<String, Object> decisionPayload(long tenantId, UUID taskId) {
        List<String> payloads = jdbc.query(
                ApprovalGovernanceQuerySql.DECISION_PAYLOAD,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("taskId", taskId),
                (result, rowNumber) -> result.getString(1));
        if (payloads.size() != 1) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Approval decision payload evidence is unavailable or inconsistent.");
        }
        return json(payloads.getFirst());
    }

    List<ApprovalDtos.SignatureProviderSummary> signatureProviders(
            long tenantId,
            String managementScope) {
        return jdbc.query(
                ApprovalQuerySql02.SIGNATURE_PROVIDERS_SELECT_APR_SIGNATURE_PROVIDERS,
                managementParams(tenantId, managementScope),
                (result, rowNumber) -> {
                    String providerType = result.getString("provider_type");
                    String lifecycleState = result.getString("lifecycle_state");
                    boolean credentialConfigured = result.getBoolean("credential_configured");
                    Instant lastHealthCheckedAt = instant(result, "last_health_checked_at");
                    return new ApprovalDtos.SignatureProviderSummary(
                            result.getObject("provider_id", UUID.class),
                            result.getString("provider_key"),
                            result.getString("display_name"),
                            providerType,
                            lifecycleState,
                            signatureCapabilities(
                                    providerType, lifecycleState,
                                    json(result.getString("capability_metadata")),
                                    credentialConfigured, lastHealthCheckedAt),
                            credentialConfigured,
                            lastHealthCheckedAt,
                            result.getLong("version"));
                });
    }

    List<String> workflowCandidateRoles(
            long tenantId,
            UUID workflowId,
            String managementScope) {
        return candidateRoles(jdbc.query(
                ApprovalQuerySql02.WORKFLOW_CANDIDATE_ROLES_SELECT_APR_WORKFLOW_VERSIONS,
                managementParams(tenantId, managementScope).addValue("workflowId", workflowId),
                (result, rowNumber) -> result.getString("candidate_role")));
    }

    List<String> requestCandidateRoles(
            long tenantId,
            UUID requestId,
            String managementScope) {
        return candidateRoles(jdbc.query(
                ApprovalQuerySql02.REQUEST_CANDIDATE_ROLES_SELECT_APR_REQUESTS,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("requestId", requestId)
                        .addValue("managementScope", managementScope),
                (result, rowNumber) -> result.getString("candidate_role")));
    }

    List<String> ownedRequestCandidateRoles(
            ApprovalRequestContext.Actor actor,
            UUID requestId) {
        return candidateRoles(jdbc.query(
                ApprovalQuerySql02.OWNED_REQUEST_CANDIDATE_ROLES_SELECT_APR_REQUESTS,
                new MapSqlParameterSource()
                        .addValue("tenantId", actor.tenantId())
                        .addValue("requestId", requestId)
                        .addValue("userId", actor.userId()),
                (result, rowNumber) -> result.getString("candidate_role")));
    }

    List<ApprovalDtos.IntegrationDeliverySummary> integrationDeliveries(
            ApprovalRequestContext.Actor actor,
            String selectedScope,
            int limit) {
        return jdbc.query(
                ApprovalQuerySql02.INTEGRATION_DELIVERIES_SELECT_APR_INTEGRATION_OUTBOX,
                new MapSqlParameterSource()
                        .addValue("tenantId", actor.tenantId())
                        .addValue("managementScope", selectedScope, Types.VARCHAR)
                        .addValue("limit", Math.max(1, Math.min(limit, 100))),
                (result, rowNumber) -> {
                    long version = result.getLong("version");
                    return new ApprovalDtos.IntegrationDeliverySummary(
                            result.getObject("outbox_id", UUID.class),
                            result.getObject("event_id", UUID.class),
                            result.getObject("request_id", UUID.class),
                            result.getString("event_type"),
                            result.getString("status"),
                            result.getInt("attempt_count"),
                            result.getInt("manual_retry_count"),
                            instant(result, "available_at"),
                            instant(result, "published_at"),
                            result.getString("last_error"),
                            instant(result, "created_at"),
                            instant(result, "last_retried_at"),
                            version,
                            retryEligibility(result, actor, selectedScope, version));
                });
    }

    private List<String> candidateRoles(List<String> values) {
        if (values.isEmpty()) return List.of();
        if (values.stream().anyMatch(value -> value == null
                || !value.strip().matches("[A-Z][A-Z0-9_]{1,79}"))) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Stored approval candidate-role evidence is invalid.");
        }
        return values.stream()
                .map(String::strip)
                .distinct()
                .sorted()
                .toList();
    }

    private ApprovalDtos.RetryEligibility retryEligibility(
            ResultSet result,
            ApprovalRequestContext.Actor actor,
            String selectedScope,
            long version) throws SQLException {
        String status = result.getString("status");
        String assignmentState = result.getString("recovery_auditor_assignment_state");
        String resourceSetKey = result.getString("recovery_auditor_resource_set_key");
        String assignmentRevision = result.getString("recovery_auditor_assignment_revision");
        String managementScope = result.getString("management_resource_set_key");
        Long originator = nullableLong(result, "event_originator_user_id");
        Long auditor = nullableLong(result, "assigned_auditor_user_id");
        Instant assignedAt = instant(result, "recovery_auditor_assigned_at");
        String reason;
        if (!Set.of("FAILED", "DEAD").contains(status)) {
            reason = "STATUS_NOT_RETRYABLE";
        } else if (!"ASSIGNED".equals(assignmentState)) {
            reason = "AUDITOR_ASSIGNMENT_NOT_READY";
        } else if (!selectedScope.equals(managementScope)
                || !selectedScope.equals(resourceSetKey)) {
            reason = "SCOPE_EVIDENCE_MISMATCH";
        } else if (originator == null || auditor == null || assignedAt == null
                || assignmentRevision == null || assignmentRevision.isBlank()
                || originator.equals(auditor)) {
            reason = "RECOVERY_EVIDENCE_INCOMPLETE";
        } else if (actor.userId().equals(originator) || actor.userId().equals(auditor)) {
            reason = "SEPARATION_OF_DUTIES";
        } else {
            reason = "ELIGIBLE";
        }
        return new ApprovalDtos.RetryEligibility(
                "ELIGIBLE".equals(reason), reason, version, Instant.now());
    }

    private ApprovalDtos.SignatureCapabilities signatureCapabilities(
            String providerType,
            String lifecycleState,
            Map<String, Object> metadata,
            boolean credentialConfigured,
            Instant lastHealthCheckedAt) {
        boolean internal = "INTERNAL_ATTESTATION".equals(providerType);
        boolean auditEvidence = internal && "audit-event".equals(metadata.get("evidence"));
        boolean verifiedIdentity = internal
                && "verified-session".equals(metadata.get("identity"));
        String readiness;
        if ("DISABLED".equals(lifecycleState)) {
            readiness = "DISABLED";
        } else if ("DEGRADED".equals(lifecycleState)) {
            readiness = "DEGRADED";
        } else if (!internal && (!credentialConfigured
                || "CONFIGURATION_REQUIRED".equals(lifecycleState))) {
            readiness = "CONFIGURATION_REQUIRED";
        } else if (!internal && lastHealthCheckedAt == null) {
            readiness = "NOT_VERIFIED";
        } else if (internal && "ACTIVE".equals(lifecycleState)
                && auditEvidence && verifiedIdentity) {
            readiness = "READY";
        } else if ("ACTIVE".equals(lifecycleState)) {
            readiness = "EXTERNAL_VERIFICATION_REQUIRED";
        } else {
            readiness = "NOT_VERIFIED";
        }
        return new ApprovalDtos.SignatureCapabilities(
                internal,
                auditEvidence,
                verifiedIdentity,
                Set.of("DOCUSIGN", "ADOBE_SIGN").contains(providerType),
                readiness);
    }

    private MapSqlParameterSource managementParams(long tenantId, String managementScope) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("managementScope", managementScope, Types.VARCHAR);
    }

    Map<String, Object> json(String value) {
        try {
            return value == null
                    ? Map.of()
                    : objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored approval JSON is invalid.", exception);
        }
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }
}
