package com.dwp.services.provider.support;

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
public class ProviderSupportPostReviewEvidenceRepository {

    static final int DISPLAY_LIMIT = 6;
    private static final String EVIDENCE_CTE = """
            WITH target_evidence AS (
                SELECT audit.audit_event_id, audit.provider_tenant_id, audit.target_id, audit.action,
                       audit.outcome, audit.correlation_id, audit.redacted_snapshot, audit.occurred_at,
                       UPPER(COALESCE(audit.redacted_snapshot ->> 'method', '')) AS method,
                       CASE
                           WHEN audit.redacted_snapshot ->> 'routeTemplate' = ? THEN ?
                           WHEN audit.redacted_snapshot ->> 'resourcePath' = ? THEN ?
                           WHEN audit.redacted_snapshot ->> 'routeTemplate' ~ ?
                               THEN audit.redacted_snapshot ->> 'routeTemplate'
                           ELSE NULL
                       END AS route_template,
                       NULLIF(audit.redacted_snapshot ->> 'scope', '') AS used_scope,
                       NULLIF(audit.redacted_snapshot ->> 'requiredScope', '') AS required_scope,
                       NULLIF(audit.redacted_snapshot ->> 'reasonCode', '') AS reason_code
                  FROM prv_audit_events audit
                 WHERE audit.target_type = 'SUPPORT_SESSION'
                   AND audit.target_id = ?
                   AND audit.action IN (
                       'provider.support-session.used',
                       'provider.support-session.access-denied')
                   AND audit.occurred_at >= ?
                   AND audit.occurred_at <= ?
            ), classified AS (
                SELECT evidence.*,
                       evidence.provider_tenant_id IS NOT DISTINCT FROM ? AS tenant_bound,
                       evidence.action = 'provider.support-session.used'
                           AND evidence.outcome = 'SUCCESS'
                           AND evidence.correlation_id ~ ?
                           AND evidence.method = 'GET'
                           AND evidence.route_template = ?
                           AND evidence.used_scope = ANY (?::varchar[]) AS valid_use,
                       evidence.action = 'provider.support-session.access-denied'
                           AND evidence.outcome = 'DENIED'
                           AND evidence.correlation_id ~ ?
                           AND evidence.method ~ '^[A-Z]{3,12}$'
                           AND evidence.route_template IS NOT NULL
                           AND evidence.reason_code IS NOT NULL AS valid_denial
                  FROM target_evidence evidence
            )
            """;

    private final JdbcTemplate jdbc;

    public ProviderSupportPostReviewEvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Context> context(UUID requestId) {
        return jdbc.query("""
                SELECT request.support_access_request_id,
                       request.provider_tenant_id,
                       request.requester_operator_id,
                       request.lifecycle_state AS request_state,
                       request.completed_at,
                       session.support_session_id,
                       session.lifecycle_state AS session_state,
                       session.started_at,
                       ARRAY(
                           SELECT scope.scope_code
                             FROM prv_support_session_scopes scope
                            WHERE scope.support_session_id = session.support_session_id
                            ORDER BY scope.scope_code
                       ) AS granted_scopes
                  FROM prv_support_access_requests request
                  JOIN prv_support_sessions session
                    ON session.support_access_request_id = request.support_access_request_id
                   AND session.provider_tenant_id = request.provider_tenant_id
                 WHERE request.support_access_request_id = ?
                """, this::mapContext, requestId).stream().findFirst();
    }

    public Statistics statistics(Context context) {
        Object[] arguments = evidenceArguments(context);
        return jdbc.query(EVIDENCE_CTE + """
                SELECT COUNT(*) FILTER (WHERE tenant_bound) AS total_count,
                       COUNT(*) FILTER (WHERE tenant_bound AND valid_use) AS allowed_count,
                       COUNT(*) FILTER (WHERE tenant_bound AND valid_denial) AS denied_count,
                       COUNT(*) FILTER (
                           WHERE tenant_bound AND NOT valid_use AND NOT valid_denial) AS invalid_count,
                       COUNT(*) FILTER (WHERE NOT tenant_bound) AS cross_tenant_count,
                       ARRAY(
                           SELECT DISTINCT used_scope
                             FROM classified
                            WHERE tenant_bound AND valid_use
                            ORDER BY used_scope
                       ) AS observed_scopes
                  FROM classified
                """, (result, ignored) -> new Statistics(
                        result.getLong("total_count"),
                        result.getLong("allowed_count"),
                        result.getLong("denied_count"),
                        result.getLong("invalid_count"),
                        result.getLong("cross_tenant_count"),
                        strings(result, "observed_scopes")), arguments).stream()
                .findFirst().orElse(new Statistics(0, 0, 0, 0, 0, List.of()));
    }

    public List<EvidenceRow> events(Context context) {
        Object[] arguments = evidenceArguments(context);
        return jdbc.query(EVIDENCE_CTE + """
                SELECT audit_event_id, provider_tenant_id, target_id, action, outcome,
                       correlation_id, occurred_at,
                       method, route_template, used_scope, required_scope, reason_code
                  FROM classified
                 WHERE tenant_bound AND (valid_use OR valid_denial)
                 ORDER BY occurred_at DESC, audit_event_id DESC
                 LIMIT 6
                """, this::evidenceRow, arguments);
    }

    private Object[] evidenceArguments(Context context) {
        return new Object[]{
                ProviderSupportPostReviewEvidencePolicy.PREVIEW_ROUTE,
                ProviderSupportPostReviewEvidencePolicy.PREVIEW_ROUTE,
                ProviderSupportPostReviewEvidencePolicy.PREVIEW_ROUTE,
                ProviderSupportPostReviewEvidencePolicy.PREVIEW_ROUTE,
                ProviderSupportPostReviewEvidencePolicy.SAFE_DENIAL_ROUTE_PATTERN,
                context.supportSessionId().toString(),
                Timestamp.from(context.startedAt()), Timestamp.from(context.completedAt()),
                context.tenantId(),
                ProviderSupportPostReviewEvidencePolicy.CANONICAL_CORRELATION_PATTERN,
                ProviderSupportPostReviewEvidencePolicy.PREVIEW_ROUTE,
                context.grantedScopes().toArray(String[]::new),
                ProviderSupportPostReviewEvidencePolicy.CANONICAL_CORRELATION_PATTERN};
    }

    private Context mapContext(ResultSet result, int ignored) throws SQLException {
        return new Context(
                result.getObject("support_access_request_id", UUID.class),
                result.getObject("support_session_id", UUID.class),
                result.getObject("provider_tenant_id", UUID.class),
                result.getLong("requester_operator_id"),
                result.getString("request_state"),
                result.getString("session_state"),
                instant(result, "started_at"),
                instant(result, "completed_at"),
                strings(result, "granted_scopes"));
    }

    private EvidenceRow evidenceRow(ResultSet result, int ignored) throws SQLException {
        boolean allowed = "provider.support-session.used".equals(result.getString("action"));
        return new EvidenceRow(
                result.getObject("audit_event_id", UUID.class),
                UUID.fromString(result.getString("target_id")),
                result.getObject("provider_tenant_id", UUID.class),
                instant(result, "occurred_at"),
                allowed ? "ALLOW" : "DENY",
                result.getString("method"),
                result.getString("route_template"),
                allowed ? result.getString("used_scope") : result.getString("required_scope"),
                result.getString("outcome"),
                result.getString("reason_code"),
                result.getString("correlation_id"));
    }

    private List<String> strings(ResultSet result, String column) throws SQLException {
        java.sql.Array array = result.getArray(column);
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record Context(
            UUID requestId,
            UUID supportSessionId,
            UUID tenantId,
            Long requesterOperatorId,
            String requestState,
            String sessionState,
            Instant startedAt,
            Instant completedAt,
            List<String> grantedScopes) {
    }

    public record Statistics(
            long totalCount,
            long allowedCount,
            long deniedCount,
            long invalidCount,
            long crossTenantCount,
            List<String> observedScopes) {
    }

    public record EvidenceRow(
            UUID auditEventId,
            UUID supportSessionId,
            UUID tenantId,
            Instant occurredAt,
            String decision,
            String method,
            String routeTemplate,
            String scope,
            String outcome,
            String reasonCode,
            String correlationId) {
    }
}
