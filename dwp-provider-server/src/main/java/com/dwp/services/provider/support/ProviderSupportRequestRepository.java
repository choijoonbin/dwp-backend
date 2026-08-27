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
                   request.requester_auth_session_id,
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
            UUID requesterAuthSessionId,
            String justification,
            int durationMinutes,
            String approvalReference,
            boolean customerApprovalRequired,
            String riskTier,
            String requestKey,
            String requestFingerprint) {
        UUID requestId = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO prv_support_access_requests (
                    support_access_request_id, provider_tenant_id, requester_operator_id,
                    requester_auth_session_id, justification, duration_minutes, approval_reference,
                    customer_approval_required, risk_tier, request_key, access_mode,
                    request_fingerprint, decision_due_at, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'STANDARD', ?,
                        statement_timestamp() + INTERVAL '24 hours', ?, ?)
                ON CONFLICT (requester_operator_id, request_key) DO NOTHING
                """, requestId, tenantId, requesterOperatorId, requesterAuthSessionId,
                justification, durationMinutes,
                approvalReference, customerApprovalRequired, riskTier, requestKey,
                requestFingerprint, requesterOperatorId, requesterOperatorId);
        if (inserted == 1) return new CreateResult(requestId, true);
        return byKey(requesterOperatorId, requestKey)
                .map(record -> new CreateResult(record.requestId(), false))
                .orElseThrow(() -> new IllegalStateException("Support request idempotency lookup failed."));
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
        return records("request.support_access_request_id = ?", requestId).stream().findFirst();
    }

    public Optional<SupportAccessRequestRecord> byKey(Long requesterOperatorId, String requestKey) {
        return records("request.requester_operator_id = ? AND request.request_key = ?",
                requesterOperatorId, requestKey).stream().findFirst();
    }

    public List<ProviderDtos.SupportAccessRequestSummary> list(UUID tenantId) {
        String predicate = tenantId == null ? "TRUE" : "request.provider_tenant_id = ?";
        Object[] arguments = tenantId == null ? new Object[0] : new Object[]{tenantId};
        return jdbc.query(REQUEST_SELECT + " WHERE " + predicate
                        + " ORDER BY request.created_at DESC LIMIT 300",
                this::mapSummary, arguments);
    }

    public ProviderDtos.SupportAccessRequestSummary summary(UUID requestId) {
        return jdbc.query(REQUEST_SELECT + " WHERE request.support_access_request_id = ?",
                this::mapSummary, requestId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Support access request disappeared."));
    }

    public boolean decide(
            UUID requestId,
            long version,
            Long reviewerOperatorId,
            Long requesterOperatorId,
            UUID requesterAuthSessionId,
            String decision,
            String reason) {
        return jdbc.update("""
                UPDATE prv_support_access_requests request
                   SET lifecycle_state = ?,
                       decided_at = statement_timestamp(),
                       decided_by = ?,
                       decision_reason = ?,
                       updated_at = statement_timestamp(),
                       updated_by = ?,
                       version = version + 1
                 WHERE support_access_request_id = ?
                   AND lifecycle_state = 'PENDING_APPROVAL'
                   AND decision_due_at > statement_timestamp()
                   AND requester_operator_id = ?
                   AND requester_auth_session_id = ?
                   AND requester_operator_id <> ?
                   AND version = ?
                """, decision, reviewerOperatorId, reason, reviewerOperatorId,
                requestId, requesterOperatorId, requesterAuthSessionId,
                reviewerOperatorId, version) == 1;
    }

    public boolean cancel(
            UUID requestId,
            long version,
            Long operatorId,
            String reason) {
        return jdbc.update("""
                UPDATE prv_support_access_requests
                   SET lifecycle_state = 'CANCELLED',
                       cancelled_at = statement_timestamp(),
                       cancelled_by = ?,
                       cancellation_reason = ?,
                       updated_at = statement_timestamp(),
                       updated_by = ?,
                       version = version + 1
                 WHERE support_access_request_id = ?
                   AND lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED')
                   AND version = ?
                """, operatorId, reason, operatorId, requestId, version) == 1;
    }

    public boolean review(
            UUID requestId,
            long version,
            Long reviewerOperatorId,
            String summary) {
        return jdbc.update("""
                UPDATE prv_support_access_requests request
                   SET lifecycle_state = 'REVIEWED',
                       post_review_state = 'COMPLETED',
                       post_reviewed_at = statement_timestamp(),
                       post_reviewed_by = ?,
                       post_review_summary = ?,
                       updated_at = statement_timestamp(),
                       updated_by = ?,
                       version = version + 1
                 WHERE request.support_access_request_id = ?
                   AND request.lifecycle_state = 'COMPLETED'
                   AND request.requester_operator_id <> ?
                   AND request.version = ?
                   AND request.completed_at IS NOT NULL
                   AND EXISTS (
                       SELECT 1
                         FROM prv_support_sessions session
                        WHERE session.support_access_request_id =
                              request.support_access_request_id
                          AND session.provider_tenant_id = request.provider_tenant_id
                          AND session.lifecycle_state IN ('REVOKED', 'EXPIRED')
                          AND NOT EXISTS (
                              SELECT 1
                                FROM prv_audit_events audit
                               WHERE audit.target_type = 'SUPPORT_SESSION'
                                 AND audit.target_id = session.support_session_id::text
                                 AND audit.action IN (
                                     'provider.support-session.used',
                                     'provider.support-session.access-denied')
                                 AND audit.occurred_at >= session.started_at
                                 AND audit.occurred_at <= request.completed_at
                                 AND (
                                     audit.provider_tenant_id IS DISTINCT FROM
                                         request.provider_tenant_id
                                     OR (
                                         audit.action = 'provider.support-session.used'
                                         AND (
                                             audit.outcome = 'SUCCESS'
                                             AND audit.correlation_id ~ ?
                                             AND UPPER(COALESCE(
                                                 audit.redacted_snapshot ->> 'method', '')) = 'GET'
                                             AND COALESCE(
                                                 audit.redacted_snapshot ->> 'routeTemplate',
                                                 audit.redacted_snapshot ->> 'resourcePath') =
                                                 ?
                                             AND EXISTS (
                                                 SELECT 1
                                                   FROM prv_support_session_scopes scope
                                                  WHERE scope.support_session_id =
                                                        session.support_session_id
                                                    AND scope.scope_code =
                                                        audit.redacted_snapshot ->> 'scope')
                                         ) IS NOT TRUE
                                     )
                                     OR (
                                         audit.action = 'provider.support-session.access-denied'
                                         AND (
                                             audit.outcome = 'DENIED'
                                             AND audit.correlation_id ~ ?
                                             AND UPPER(COALESCE(
                                                 audit.redacted_snapshot ->> 'method', ''))
                                                 ~ '^[A-Z]{3,12}$'
                                             AND (
                                                 COALESCE(
                                                     audit.redacted_snapshot ->> 'routeTemplate',
                                                     audit.redacted_snapshot ->> 'resourcePath') = ?
                                                 OR audit.redacted_snapshot ->> 'routeTemplate' ~ ?
                                             )
                                             AND NULLIF(
                                                 audit.redacted_snapshot ->> 'reasonCode', '')
                                                 IS NOT NULL
                                         ) IS NOT TRUE
                                     )
                                 )
                          )
                   )
                """, reviewerOperatorId, summary, reviewerOperatorId,
                requestId, reviewerOperatorId, version,
                ProviderSupportPostReviewEvidencePolicy.CANONICAL_CORRELATION_PATTERN,
                ProviderSupportPostReviewEvidencePolicy.PREVIEW_ROUTE,
                ProviderSupportPostReviewEvidencePolicy.CANONICAL_CORRELATION_PATTERN,
                ProviderSupportPostReviewEvidencePolicy.PREVIEW_ROUTE,
                ProviderSupportPostReviewEvidencePolicy.SAFE_DENIAL_ROUTE_PATTERN) == 1;
    }

    private List<SupportAccessRequestRecord> records(String predicate, Object... arguments) {
        return jdbc.query(REQUEST_SELECT + " WHERE " + predicate, this::record, arguments);
    }

    int expireElapsedRequests() {
        return jdbc.update("""
                UPDATE prv_support_access_requests
                   SET lifecycle_state = 'EXPIRED',
                       updated_at = statement_timestamp(),
                       version = version + 1
                 WHERE lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED')
                   AND decision_due_at <= statement_timestamp()
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
                result.getObject("requester_auth_session_id", UUID.class),
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
            UUID requesterAuthSessionId,
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
