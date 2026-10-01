package com.dwp.services.provider.resourcegovernance;

import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateTenantLifecycleRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.TenantLifecycleDecisionRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.TenantLifecycleRequestRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TenantLifecycleGovernanceJdbcRepository {
    private static final String LIFECYCLE_REQUEST_SELECT = """
            SELECT request.lifecycle_request_id, request.provider_tenant_id,
                   tenant.tenant_key, tenant.display_name AS tenant_display_name,
                   request.requested_action, request.lifecycle_state,
                   request.hold_evaluation_state, request.hold_evidence,
                   request.execution_state, request.justification, request.requested_by,
                   request.submitted_by, request.approved_by, request.submitted_at,
                   request.approved_at, request.decision_reason, request.version,
                   request.created_at, request.updated_at
              FROM prv_tenant_lifecycle_requests request
              JOIN prv_tenants tenant
                ON tenant.provider_tenant_id = request.provider_tenant_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TenantLifecycleGovernanceJdbcRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<TenantLifecycleRequestRow> lifecycleRequests(UUID tenantId, int fetchLimit) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("fetchLimit", fetchLimit);
        String where = "";
        if (tenantId != null) {
            where = " WHERE request.provider_tenant_id = :tenantId";
            parameters.addValue("tenantId", tenantId);
        }
        return jdbc.query(LIFECYCLE_REQUEST_SELECT + where
                + " ORDER BY request.updated_at DESC, request.created_at DESC,"
                + " request.lifecycle_request_id DESC"
                + " LIMIT :fetchLimit",
                parameters, this::tenantLifecycleRequestRow);
    }

    public Optional<TenantLifecycleRequestRow> lifecycleRequest(UUID requestId) {
        return jdbc.query(LIFECYCLE_REQUEST_SELECT
                        + " WHERE request.lifecycle_request_id = :requestId",
                new MapSqlParameterSource("requestId", requestId), this::tenantLifecycleRequestRow)
                .stream().findFirst();
    }

    public Optional<TenantLifecycleRequestRow> lockLifecycleRequest(UUID requestId) {
        return jdbc.query(LIFECYCLE_REQUEST_SELECT
                        + " WHERE request.lifecycle_request_id = :requestId FOR UPDATE",
                new MapSqlParameterSource("requestId", requestId), this::tenantLifecycleRequestRow)
                .stream().findFirst();
    }

    public TenantLifecycleRequestRow createLifecycleRequest(
            UUID requestId,
            UUID tenantId,
            CreateTenantLifecycleRequest request,
            String lifecycleState,
            String holdEvaluationState,
            List<String> holdEvidenceRefs,
            Long actorId) {
        jdbc.update("""
                INSERT INTO prv_tenant_lifecycle_requests (
                    lifecycle_request_id, provider_tenant_id, requested_action, lifecycle_state,
                    hold_evaluation_state, hold_evidence, justification, requested_by)
                VALUES (
                    :requestId, :tenantId, :requestedAction, :lifecycleState,
                    :holdEvaluationState, CAST(:holdEvidence AS JSONB), :justification, :actorId)
                """, new MapSqlParameterSource("requestId", requestId)
                .addValue("tenantId", tenantId)
                .addValue("requestedAction", request.requestedAction())
                .addValue("lifecycleState", lifecycleState)
                .addValue("holdEvaluationState", holdEvaluationState)
                .addValue("holdEvidence", json(objectMapper.valueToTree(holdEvidenceRefs)))
                .addValue("justification", request.justification())
                .addValue("actorId", actorId));
        return lifecycleRequest(requestId).orElseThrow();
    }

    public boolean refreshLifecycleHold(
            UUID requestId,
            long version,
            String lifecycleState,
            String holdEvaluationState,
            List<String> holdEvidenceRefs) {
        return jdbc.update("""
                UPDATE prv_tenant_lifecycle_requests
                   SET lifecycle_state = :lifecycleState,
                       hold_evaluation_state = :holdEvaluationState,
                       hold_evidence = CAST(:holdEvidence AS JSONB),
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE lifecycle_request_id = :requestId
                   AND lifecycle_state IN ('DRAFT', 'BLOCKED_BY_HOLD')
                   AND version = :version
                """, new MapSqlParameterSource("requestId", requestId)
                .addValue("version", version)
                .addValue("lifecycleState", lifecycleState)
                .addValue("holdEvaluationState", holdEvaluationState)
                .addValue("holdEvidence", json(objectMapper.valueToTree(holdEvidenceRefs)))
                ) == 1;
    }

    public boolean submitLifecycleRequest(UUID requestId, long version, Long actorId) {
        return jdbc.update("""
                UPDATE prv_tenant_lifecycle_requests
                   SET lifecycle_state = 'PENDING_APPROVAL', submitted_by = :actorId,
                       submitted_at = CURRENT_TIMESTAMP, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE lifecycle_request_id = :requestId
                   AND lifecycle_state = 'DRAFT'
                   AND hold_evaluation_state = 'OWNER_VERIFICATION_REQUIRED'
                   AND version = :version
                """, new MapSqlParameterSource("requestId", requestId)
                .addValue("version", version)
                .addValue("actorId", actorId)) == 1;
    }

    public boolean cancelLifecycleRequest(
            UUID requestId,
            long version,
            Long actorId,
            String reason) {
        return jdbc.update("""
                UPDATE prv_tenant_lifecycle_requests
                   SET lifecycle_state = 'CANCELLED', execution_state = 'NOT_REQUIRED',
                       decision_reason = :reason,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE lifecycle_request_id = :requestId
                   AND requested_by = :actorId
                   AND lifecycle_state IN ('DRAFT', 'BLOCKED_BY_HOLD', 'PENDING_APPROVAL')
                   AND version = :version
                """, new MapSqlParameterSource("requestId", requestId)
                .addValue("version", version)
                .addValue("actorId", actorId)
                .addValue("reason", reason)) == 1;
    }

    public boolean decideLifecycleRequest(
            UUID requestId,
            TenantLifecycleDecisionRequest request,
            Long actorId) {
        String nextState = "APPROVED".equals(request.decision())
                ? "APPROVED_FOR_HANDOFF" : "REJECTED";
        return jdbc.update("""
                UPDATE prv_tenant_lifecycle_requests
                   SET lifecycle_state = :nextState, approved_by = :actorId,
                       approved_at = CURRENT_TIMESTAMP, decision_reason = :reason,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE lifecycle_request_id = :requestId
                   AND lifecycle_state = 'PENDING_APPROVAL'
                   AND requested_by <> :actorId
                   AND submitted_by <> :actorId
                   AND version = :version
                """, new MapSqlParameterSource("requestId", requestId)
                .addValue("version", request.version())
                .addValue("nextState", nextState)
                .addValue("reason", request.reason())
                .addValue("actorId", actorId)) == 1;
    }
    private TenantLifecycleRequestRow tenantLifecycleRequestRow(
            ResultSet result,
            int ignored) throws SQLException {
        JsonNode evidence = node(result.getString("hold_evidence"));
        List<String> evidenceRefs = new java.util.ArrayList<>();
        if (evidence.isArray()) evidence.forEach(value -> evidenceRefs.add(value.asText()));
        return new TenantLifecycleRequestRow(
                result.getObject("lifecycle_request_id", UUID.class),
                result.getObject("provider_tenant_id", UUID.class),
                result.getString("tenant_key"),
                result.getString("tenant_display_name"),
                result.getString("requested_action"),
                result.getString("lifecycle_state"),
                result.getString("hold_evaluation_state"),
                List.copyOf(evidenceRefs),
                result.getString("execution_state"),
                result.getString("justification"),
                result.getLong("requested_by"),
                result.getObject("submitted_by", Long.class),
                result.getObject("approved_by", Long.class),
                instant(result, "submitted_at"),
                instant(result, "approved_at"),
                result.getString("decision_reason"),
                result.getLong("version"),
                instant(result, "created_at"),
                instant(result, "updated_at"));
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private JsonNode node(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Provider resource-governance JSON is invalid.", exception);
        }
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Provider resource-governance JSON cannot be serialized.", exception);
        }
    }
}
