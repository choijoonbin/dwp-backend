package com.dwp.services.provider.support;

import com.dwp.services.provider.ProviderDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProviderSupportRequestRepository {

    private static final String REQUEST_SELECT = """
            SELECT request.support_access_request_id,
                   request.provider_tenant_id,
                   tenant.tenant_key,
                   tenant.display_name AS tenant_name,
                   request.requester_operator_id,
                   requester.display_name AS requester_name,
                   request.lifecycle_state,
                   request.access_mode,
                   request.justification,
                   request.duration_minutes,
                   request.approval_reference,
                   request.customer_approval_required,
                   request.risk_tier,
                   request.request_key,
                   request.request_fingerprint,
                   request.created_at AS requested_at,
                   request.decision_due_at,
                   request.decided_at,
                   request.decided_by,
                   decider.display_name AS decided_by_name,
                   request.decision_reason,
                   session.support_session_id,
                   request.activated_at,
                   request.completed_at,
                   request.post_review_state,
                   request.post_reviewed_at,
                   request.post_reviewed_by,
                   reviewer.display_name AS post_reviewed_by_name,
                   request.post_review_summary,
                   request.version,
                   ARRAY(
                       SELECT scope.scope_code
                         FROM prv_support_access_request_scopes scope
                        WHERE scope.support_access_request_id = request.support_access_request_id
                        ORDER BY scope.scope_code
                   ) AS scopes
              FROM prv_support_access_requests request
              JOIN prv_tenants tenant ON tenant.provider_tenant_id = request.provider_tenant_id
              JOIN prv_operators requester
                ON requester.provider_operator_id = request.requester_operator_id
              LEFT JOIN prv_operators decider
                ON decider.provider_operator_id = request.decided_by
              LEFT JOIN prv_operators reviewer
                ON reviewer.provider_operator_id = request.post_reviewed_by
              LEFT JOIN prv_support_sessions session
                ON session.support_access_request_id = request.support_access_request_id
            """;

    private final JdbcTemplate jdbc;

    public ProviderSupportRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CreateResult create(
            UUID tenantId,
            Long requesterOperatorId,
            String justification,
            int durationMinutes,
            String approvalReference,
            boolean customerApprovalRequired,
            String riskTier,
            String requestKey,
            String requestFingerprint,
            Instant decisionDueAt) {
        UUID requestId = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO prv_support_access_requests (
                    support_access_request_id, provider_tenant_id, requester_operator_id,
                    justification, duration_minutes, approval_reference,
                    customer_approval_required, risk_tier, request_key, access_mode,
                    request_fingerprint, decision_due_at, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'STANDARD', ?, ?, ?, ?)
                ON CONFLICT (requester_operator_id, request_key) DO NOTHING
                """, requestId, tenantId, requesterOperatorId, justification, durationMinutes,
                approvalReference, customerApprovalRequired, riskTier, requestKey,
                requestFingerprint, Timestamp.from(decisionDueAt), requesterOperatorId,
                requesterOperatorId);
        if (inserted == 1) return new CreateResult(requestId, true);
        return byKey(requesterOperatorId, requestKey)
                .map(record -> new CreateResult(record.requestId(), false))
                .orElseThrow(() -> new IllegalStateException("Support request idempotency lookup failed."));
    }

    public CreateResult createBreakGlass(
            UUID tenantId,
            Long requesterOperatorId,
            String justification,
            int durationMinutes,
            String requestKey,
            String requestFingerprint,
            Instant activationDueAt) {
        UUID requestId = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO prv_support_access_requests (
                    support_access_request_id, provider_tenant_id, requester_operator_id,
                    lifecycle_state, access_mode, justification, duration_minutes,
                    customer_approval_required, risk_tier, request_key, request_fingerprint,
                    decision_due_at, decided_at, decided_by, decision_reason,
                    created_by, updated_by)
                VALUES (?, ?, ?, 'APPROVED', 'BREAK_GLASS', ?, ?, FALSE, 'L3', ?, ?, ?,
                        CURRENT_TIMESTAMP, ?, 'Emergency access policy exception', ?, ?)
                ON CONFLICT (requester_operator_id, request_key) DO NOTHING
                """, requestId, tenantId, requesterOperatorId, justification, durationMinutes,
                requestKey, requestFingerprint, Timestamp.from(activationDueAt),
                requesterOperatorId, requesterOperatorId, requesterOperatorId);
        if (inserted == 1) return new CreateResult(requestId, true);
        return byKey(requesterOperatorId, requestKey)
                .map(record -> new CreateResult(record.requestId(), false))
                .orElseThrow(() -> new IllegalStateException("Break-glass idempotency lookup failed."));
    }

    public void addScopes(UUID requestId, List<String> scopes) {
        jdbc.batchUpdate("""
                INSERT INTO prv_support_access_request_scopes (
                    support_access_request_id, scope_code)
                VALUES (?, ?)
                ON CONFLICT (support_access_request_id, scope_code) DO NOTHING
                """, scopes, scopes.size(), (statement, scope) -> {
                    statement.setObject(1, requestId);
                    statement.setString(2, scope);
                });
    }

    public Optional<SupportAccessRequestRecord> byId(UUID requestId) {
        reconcile();
        return records("request.support_access_request_id = ?", requestId).stream().findFirst();
    }

    public Optional<SupportAccessRequestRecord> byKey(Long requesterOperatorId, String requestKey) {
        reconcile();
        return records("request.requester_operator_id = ? AND request.request_key = ?",
                requesterOperatorId, requestKey).stream().findFirst();
    }

    public List<ProviderDtos.SupportAccessRequestSummary> list(UUID tenantId) {
        reconcile();
        String predicate = tenantId == null ? "TRUE" : "request.provider_tenant_id = ?";
        Object[] arguments = tenantId == null ? new Object[0] : new Object[]{tenantId};
        return jdbc.query(REQUEST_SELECT + " WHERE " + predicate
                        + " ORDER BY request.created_at DESC LIMIT 300",
                this::mapSummary, arguments);
    }

    public ProviderDtos.SupportAccessRequestSummary summary(UUID requestId) {
        reconcile();
        return jdbc.query(REQUEST_SELECT + " WHERE request.support_access_request_id = ?",
                this::mapSummary, requestId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Support access request disappeared."));
    }

    public boolean decide(
            UUID requestId,
            long version,
            Long reviewerOperatorId,
            String decision,
            String reason) {
        return jdbc.update("""
                UPDATE prv_support_access_requests request
                   SET lifecycle_state = ?,
                       decided_at = CURRENT_TIMESTAMP,
                       decided_by = ?,
                       decision_reason = ?,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?,
                       version = version + 1
                 WHERE support_access_request_id = ?
                   AND lifecycle_state = 'PENDING_APPROVAL'
                   AND decision_due_at > CURRENT_TIMESTAMP
                   AND requester_operator_id <> ?
                   AND version = ?
                """, decision, reviewerOperatorId, reason, reviewerOperatorId,
                requestId, reviewerOperatorId, version) == 1;
    }

    public boolean activate(
            UUID requestId,
            UUID sessionId,
            long version,
            Long requesterOperatorId) {
        return jdbc.update("""
                UPDATE prv_support_access_requests request
                   SET lifecycle_state = 'ACTIVATED',
                       activated_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?,
                       version = version + 1
                 WHERE support_access_request_id = ?
                   AND requester_operator_id = ?
                   AND lifecycle_state = 'APPROVED'
                   AND decision_due_at > CURRENT_TIMESTAMP
                   AND version = ?
                   AND EXISTS (
                       SELECT 1 FROM prv_support_sessions session
                        WHERE session.support_session_id = ?
                          AND session.support_access_request_id = request.support_access_request_id
                   )
                """, requesterOperatorId, requestId, requesterOperatorId, version, sessionId) == 1;
    }

    public boolean cancel(
            UUID requestId,
            long version,
            Long operatorId,
            String reason) {
        return jdbc.update("""
                UPDATE prv_support_access_requests
                   SET lifecycle_state = 'CANCELLED',
                       cancelled_at = CURRENT_TIMESTAMP,
                       cancelled_by = ?,
                       cancellation_reason = ?,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?,
                       version = version + 1
                 WHERE support_access_request_id = ?
                   AND lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED')
                   AND version = ?
                """, operatorId, reason, operatorId, requestId, version) == 1;
    }

    public void completeForSession(UUID sessionId, Long operatorId) {
        jdbc.update("""
                UPDATE prv_support_access_requests request
                   SET lifecycle_state = 'COMPLETED',
                       completed_at = CURRENT_TIMESTAMP,
                       post_review_state = 'PENDING',
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?,
                       version = version + 1
                  FROM prv_support_sessions session
                 WHERE session.support_session_id = ?
                   AND session.support_access_request_id = request.support_access_request_id
                   AND request.lifecycle_state = 'ACTIVATED'
                """, operatorId, sessionId);
    }

    public boolean review(
            UUID requestId,
            long version,
            Long reviewerOperatorId,
            String summary) {
        return jdbc.update("""
                UPDATE prv_support_access_requests
                   SET lifecycle_state = 'REVIEWED',
                       post_review_state = 'COMPLETED',
                       post_reviewed_at = CURRENT_TIMESTAMP,
                       post_reviewed_by = ?,
                       post_review_summary = ?,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?,
                       version = version + 1
                 WHERE support_access_request_id = ?
                   AND lifecycle_state = 'COMPLETED'
                   AND requester_operator_id <> ?
                   AND version = ?
                """, reviewerOperatorId, summary, reviewerOperatorId,
                requestId, reviewerOperatorId, version) == 1;
    }

    private List<SupportAccessRequestRecord> records(String predicate, Object... arguments) {
        return jdbc.query(REQUEST_SELECT + " WHERE " + predicate, this::record, arguments);
    }

    private void reconcile() {
        jdbc.update("""
                UPDATE prv_support_sessions
                   SET lifecycle_state = 'EXPIRED', updated_at = CURRENT_TIMESTAMP
                 WHERE lifecycle_state = 'ACTIVE' AND expires_at <= CURRENT_TIMESTAMP
                """);
        jdbc.update("""
                UPDATE prv_support_access_requests
                   SET lifecycle_state = 'EXPIRED',
                       updated_at = CURRENT_TIMESTAMP,
                       version = version + 1
                 WHERE lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED')
                   AND decision_due_at <= CURRENT_TIMESTAMP
                """);
        jdbc.update("""
                UPDATE prv_support_access_requests request
                   SET lifecycle_state = 'COMPLETED',
                       completed_at = COALESCE(session.revoked_at, session.expires_at, CURRENT_TIMESTAMP),
                       post_review_state = 'PENDING',
                       updated_at = CURRENT_TIMESTAMP,
                       version = version + 1
                  FROM prv_support_sessions session
                 WHERE session.support_access_request_id = request.support_access_request_id
                   AND request.lifecycle_state = 'ACTIVATED'
                   AND session.lifecycle_state IN ('REVOKED', 'EXPIRED')
                """);
    }

    private ProviderDtos.SupportAccessRequestSummary mapSummary(ResultSet result, int ignored)
            throws SQLException {
        return new ProviderDtos.SupportAccessRequestSummary(
                result.getObject("support_access_request_id", UUID.class),
                result.getObject("provider_tenant_id", UUID.class),
                result.getString("tenant_key"),
                result.getString("tenant_name"),
                result.getLong("requester_operator_id"),
                result.getString("requester_name"),
                result.getString("lifecycle_state"),
                result.getString("access_mode"),
                result.getString("justification"),
                strings(result, "scopes"),
                result.getInt("duration_minutes"),
                result.getString("approval_reference"),
                result.getBoolean("customer_approval_required"),
                result.getString("risk_tier"),
                result.getString("request_key"),
                instant(result, "requested_at"),
                instant(result, "decision_due_at"),
                instant(result, "decided_at"),
                nullableLong(result, "decided_by"),
                result.getString("decided_by_name"),
                result.getString("decision_reason"),
                result.getObject("support_session_id", UUID.class),
                instant(result, "activated_at"),
                instant(result, "completed_at"),
                result.getString("post_review_state"),
                instant(result, "post_reviewed_at"),
                nullableLong(result, "post_reviewed_by"),
                result.getString("post_reviewed_by_name"),
                result.getString("post_review_summary"),
                result.getLong("version"));
    }

    private SupportAccessRequestRecord record(ResultSet result, int ignored) throws SQLException {
        return new SupportAccessRequestRecord(
                result.getObject("support_access_request_id", UUID.class),
                result.getObject("provider_tenant_id", UUID.class),
                result.getLong("requester_operator_id"),
                result.getString("lifecycle_state"),
                result.getString("access_mode"),
                result.getString("justification"),
                strings(result, "scopes"),
                result.getInt("duration_minutes"),
                result.getString("approval_reference"),
                result.getBoolean("customer_approval_required"),
                result.getString("risk_tier"),
                result.getString("request_key"),
                result.getString("request_fingerprint"),
                instant(result, "decision_due_at"),
                nullableLong(result, "decided_by"),
                result.getObject("support_session_id", UUID.class),
                result.getString("post_review_state"),
                result.getLong("version"));
    }

    private List<String> strings(ResultSet result, String column) throws SQLException {
        return List.of((String[]) result.getArray(column).getArray());
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    public record SupportAccessRequestRecord(
            UUID requestId,
            UUID tenantId,
            Long requesterOperatorId,
            String lifecycleState,
            String accessMode,
            String justification,
            List<String> scopes,
            int durationMinutes,
            String approvalReference,
            boolean customerApprovalRequired,
            String riskTier,
            String requestKey,
            String requestFingerprint,
            Instant decisionDueAt,
            Long decidedBy,
            UUID supportSessionId,
            String postReviewState,
            long version) {
    }

    public record CreateResult(UUID requestId, boolean created) {
    }
}
