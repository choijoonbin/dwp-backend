package com.dwp.services.platform.preference;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class ManagedPreferenceRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ManagedPreferenceRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public ManagedPreferenceDtos.ManagedPreferencePolicy policy(Long tenantId) {
        List<ManagedPreferenceDtos.ManagedPreferencePolicy> policies = jdbc.query("""
                SELECT managed_preference_policy_id, policy_source, owner_type, owner_ref,
                       owner_display_name, contact_uri, version
                  FROM adm_managed_preference_policies
                 WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'
                """, (row, ignored) -> {
            UUID policyId = row.getObject("managed_preference_policy_id", UUID.class);
            List<ManagedPreferenceDtos.ManagedPreferenceRule> rules = rules(tenantId, policyId);
            return new ManagedPreferenceDtos.ManagedPreferencePolicy(
                    policyId, "TENANT", row.getString("policy_source"),
                    row.getString("owner_type"), row.getString("owner_ref"),
                    row.getString("owner_display_name"), row.getString("contact_uri"),
                    rules.stream().map(ManagedPreferenceDtos.ManagedPreferenceRule::preferencePath)
                            .toList(),
                    rules, row.getLong("version"));
        }, tenantId);
        if (policies.isEmpty()) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "The tenant managed preference policy is not provisioned.");
        }
        return policies.get(0);
    }

    public List<ManagedPreferenceDtos.PreferenceExceptionRequest> userRequests(
            Long tenantId, Long userId) {
        return jdbc.query(BASE_REQUEST_QUERY + """
                 WHERE request.tenant_id = ? AND request.user_id = ?
                 ORDER BY request.created_at DESC
                """, this::mapRequest, tenantId, userId);
    }

    public List<ManagedPreferenceDtos.PreferenceExceptionRequest> adminRequests(
            Long tenantId, String state) {
        if (state == null || state.isBlank() || "ALL".equalsIgnoreCase(state)) {
            return jdbc.query(BASE_REQUEST_QUERY + """
                     WHERE request.tenant_id = ?
                     ORDER BY CASE request.request_state WHEN 'PENDING' THEN 0 ELSE 1 END,
                              request.created_at DESC
                    """, this::mapRequest, tenantId);
        }
        return jdbc.query(BASE_REQUEST_QUERY + """
                 WHERE request.tenant_id = ? AND request.request_state = ?
                 ORDER BY request.created_at DESC
                """, this::mapRequest, tenantId, state);
    }

    public ManagedPreferenceDtos.PreferenceExceptionRequest createRequest(
            Long tenantId,
            Long userId,
            ManagedPreferenceDtos.ManagedPreferencePolicy policy,
            ManagedPreferenceDtos.ManagedPreferenceRule rule,
            ManagedPreferenceDtos.CreateExceptionRequest request) {
        UUID requestId = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO usr_preference_exception_requests (
                        preference_exception_request_id, tenant_id, user_id,
                        managed_preference_policy_id, managed_preference_rule_id,
                        preference_path, requested_value, business_justification,
                        business_impact, policy_version, rule_version,
                        assigned_owner_ref, requested_until, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, requestId, tenantId, userId, policy.policyId(), rule.ruleId(),
                    rule.preferencePath(), json(request.requestedValue()),
                    request.businessJustification().trim(), request.businessImpact().trim(),
                    policy.version(), rule.version(), policy.ownerRef(), request.requestedUntil(),
                    userId, userId);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "A pending exception request already exists for this managed setting.",
                    exception);
        }
        return requireRequest(tenantId, requestId);
    }

    public ManagedPreferenceDtos.PreferenceExceptionRequest cancelRequest(
            Long tenantId, Long userId, UUID requestId, long version) {
        ManagedPreferenceDtos.PreferenceExceptionRequest before = requireUserRequest(
                tenantId, userId, requestId);
        if (!"PENDING".equals(before.requestState()) || before.version() != version) throw conflict();
        appendDecision(tenantId, before, "CANCELLED", "USER", userId,
                "Cancelled by the requesting user.", null);
        int updated = jdbc.update("""
                UPDATE usr_preference_exception_requests
                   SET request_state = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND user_id = ?
                   AND preference_exception_request_id = ?
                   AND request_state = 'PENDING' AND version = ?
                """, userId, tenantId, userId, requestId, version);
        if (updated != 1) throw conflict();
        return requireRequest(tenantId, requestId);
    }

    public ManagedPreferenceDtos.PreferenceExceptionRequest decideRequest(
            Long tenantId,
            Long actorId,
            UUID requestId,
            ManagedPreferenceDtos.DecideExceptionRequest request) {
        ManagedPreferenceDtos.PreferenceExceptionRequest before = requireRequest(tenantId, requestId);
        if (!"PENDING".equals(before.requestState()) || before.version() != request.version()) {
            throw conflict();
        }
        String evidenceRef = trim(request.evidenceRef());
        appendDecision(tenantId, before, request.decision(), "ADMIN", actorId,
                request.reason().trim(), evidenceRef);
        int updated = jdbc.update("""
                UPDATE usr_preference_exception_requests
                   SET request_state = ?, decision_reason = ?, decision_evidence_ref = ?,
                       decided_by = ?, decided_at = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND preference_exception_request_id = ?
                   AND request_state = 'PENDING' AND version = ?
                """, request.decision(), request.reason().trim(), evidenceRef,
                actorId, actorId, tenantId, requestId, request.version());
        if (updated != 1) throw conflict();
        return requireRequest(tenantId, requestId);
    }

    public int expireDueRequests() {
        List<DueRequest> due = jdbc.query("""
                SELECT tenant_id, preference_exception_request_id
                  FROM usr_preference_exception_requests
                 WHERE request_state = 'PENDING'
                   AND requested_until IS NOT NULL
                   AND requested_until <= CURRENT_TIMESTAMP
                 ORDER BY requested_until
                """, (row, ignored) -> new DueRequest(
                row.getLong("tenant_id"),
                row.getObject("preference_exception_request_id", UUID.class)));
        int expired = 0;
        for (DueRequest item : due) {
            ManagedPreferenceDtos.PreferenceExceptionRequest before = requireRequest(
                    item.tenantId(), item.requestId());
            appendDecision(item.tenantId(), before, "EXPIRED", "SYSTEM", null,
                    "The requested exception period expired before a decision was recorded.", null);
            expired += jdbc.update("""
                    UPDATE usr_preference_exception_requests
                       SET request_state = 'EXPIRED', version = version + 1,
                           updated_at = CURRENT_TIMESTAMP, updated_by = NULL
                     WHERE tenant_id = ? AND preference_exception_request_id = ?
                       AND request_state = 'PENDING' AND version = ?
                    """, item.tenantId(), item.requestId(), before.version());
        }
        return expired;
    }

    private List<ManagedPreferenceDtos.ManagedPreferenceRule> rules(Long tenantId, UUID policyId) {
        return jdbc.query("""
                SELECT managed_preference_rule_id, preference_path, display_key,
                       managed_value::text, exception_allowed, version
                  FROM adm_managed_preference_rules
                 WHERE tenant_id = ? AND managed_preference_policy_id = ?
                   AND lifecycle_state = 'ACTIVE'
                 ORDER BY preference_path
                """, (row, ignored) -> new ManagedPreferenceDtos.ManagedPreferenceRule(
                row.getObject("managed_preference_rule_id", UUID.class),
                row.getString("preference_path"), row.getString("display_key"),
                jsonNode(row.getString("managed_value")),
                row.getBoolean("exception_allowed"), row.getLong("version")), tenantId, policyId);
    }

    private ManagedPreferenceDtos.PreferenceExceptionRequest requireRequest(
            Long tenantId, UUID requestId) {
        List<ManagedPreferenceDtos.PreferenceExceptionRequest> rows = jdbc.query(
                BASE_REQUEST_QUERY + """
                 WHERE request.tenant_id = ? AND request.preference_exception_request_id = ?
                """, this::mapRequest, tenantId, requestId);
        if (rows.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);
        return rows.get(0);
    }

    private ManagedPreferenceDtos.PreferenceExceptionRequest requireUserRequest(
            Long tenantId, Long userId, UUID requestId) {
        ManagedPreferenceDtos.PreferenceExceptionRequest request = requireRequest(tenantId, requestId);
        if (request.userId() != userId) throw new BaseException(ErrorCode.NOT_FOUND);
        return request;
    }

    private void appendDecision(
            Long tenantId,
            ManagedPreferenceDtos.PreferenceExceptionRequest request,
            String decision,
            String actorType,
            Long actorId,
            String reason,
            String evidenceRef) {
        jdbc.update("""
                INSERT INTO usr_preference_exception_decisions (
                    preference_exception_request_id, tenant_id, previous_state,
                    decision, reason, evidence_ref, actor_type, actor_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, request.requestId(), tenantId, request.requestState(), decision,
                reason, evidenceRef, actorType, actorId);
    }

    private ManagedPreferenceDtos.PreferenceExceptionRequest mapRequest(
            ResultSet row, int ignored) throws SQLException {
        return new ManagedPreferenceDtos.PreferenceExceptionRequest(
                row.getObject("preference_exception_request_id", UUID.class),
                row.getLong("user_id"), row.getString("preference_path"),
                jsonNode(row.getString("requested_value")),
                row.getString("business_justification"), row.getString("business_impact"),
                row.getString("request_state"), row.getString("assigned_owner_ref"),
                row.getObject("requested_until", OffsetDateTime.class),
                row.getString("decision_reason"), row.getString("decision_evidence_ref"),
                row.getObject("decided_by", Long.class),
                row.getObject("decided_at", OffsetDateTime.class),
                row.getObject("created_at", OffsetDateTime.class),
                row.getObject("updated_at", OffsetDateTime.class), row.getLong("version"));
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The requested value is invalid.");
        }
    }

    private JsonNode jsonNode(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "Stored preference policy JSON is invalid.");
        }
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BaseException conflict() {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT);
    }

    private record DueRequest(Long tenantId, UUID requestId) {
    }

    private static final String BASE_REQUEST_QUERY = """
            SELECT request.preference_exception_request_id, request.user_id,
                   request.preference_path, request.requested_value::text,
                   request.business_justification, request.business_impact,
                   request.request_state, request.assigned_owner_ref,
                   request.requested_until, request.decision_reason,
                   request.decision_evidence_ref, request.decided_by,
                   request.decided_at, request.created_at, request.updated_at,
                   request.version
              FROM usr_preference_exception_requests request
            """;
}
