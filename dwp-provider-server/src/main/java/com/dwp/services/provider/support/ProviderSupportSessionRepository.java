package com.dwp.services.provider.support;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.ProviderDtos;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProviderSupportSessionRepository {

    static final int IDLE_TIMEOUT_MINUTES = 15;

    private final JdbcTemplate jdbc;

    public ProviderSupportSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void requireNoActiveSupportSession(Long operatorId) {
        Long active = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_support_sessions
                 WHERE provider_operator_id = ?
                   AND lifecycle_state = 'ACTIVE'
                   AND expires_at > statement_timestamp()
                   AND last_used_at > statement_timestamp() - INTERVAL '15 minutes'
                """, Long.class, operatorId);
        if (active != null && active > 0) throw activeSupportSessionConflict();
    }

    public Optional<ActivatedSupportSession> activateApprovedRequest(
            UUID supportAccessRequestId,
            long expectedVersion,
            Long operatorId,
            UUID originAuthSessionId,
            String tokenHash) {
        UUID sessionId = UUID.randomUUID();
        try {
            return jdbc.query("""
                    WITH candidate AS MATERIALIZED (
                        SELECT request.support_access_request_id,
                               request.provider_tenant_id,
                               request.requester_operator_id
                          FROM prv_support_access_requests request
                         WHERE request.support_access_request_id = ?
                           AND request.requester_operator_id = ?
                           AND request.requester_auth_session_id = ?
                    ), enabled_control AS MATERIALIZED (
                        SELECT control.control_key
                          FROM prv_support_activation_control control
                          JOIN candidate ON TRUE
                         WHERE control.control_key = 'STANDARD_JIT'
                           AND control.activation_enabled
                         FOR SHARE OF control
                    ), active_scope AS MATERIALIZED (
                        SELECT catalog.scope_code
                          FROM prv_support_scope_catalog catalog
                          JOIN enabled_control ON TRUE
                         WHERE catalog.scope_code = 'TENANT_EXPERIENCE_PREVIEW'
                           AND catalog.lifecycle_state = 'ACTIVE'
                           AND catalog.risk_tier = 'L1'
                           AND catalog.requires_customer_approval
                         FOR SHARE OF catalog
                    ), eligible_tenant AS MATERIALIZED (
                        SELECT tenant.provider_tenant_id
                          FROM prv_tenants tenant
                          JOIN candidate
                            ON candidate.provider_tenant_id = tenant.provider_tenant_id
                          JOIN active_scope ON TRUE
                         WHERE tenant.lifecycle_state = 'ACTIVE'
                           AND tenant.onboarding_state = 'READY'
                           AND tenant.auth_tenant_id IS NOT NULL
                         FOR SHARE OF tenant
                    ), eligible_operator_authority AS MATERIALIZED (
                        SELECT assignment.provider_operator_id
                          FROM prv_operators operator
                          JOIN candidate
                            ON candidate.requester_operator_id =
                               operator.provider_operator_id
                          JOIN eligible_tenant ON TRUE
                          JOIN prv_operator_role_assignments assignment
                            ON assignment.provider_operator_id =
                               operator.provider_operator_id
                           AND assignment.lifecycle_state = 'ACTIVE'
                           AND (assignment.valid_from IS NULL
                                OR assignment.valid_from <= statement_timestamp())
                           AND (assignment.valid_to IS NULL
                                OR assignment.valid_to > statement_timestamp())
                          JOIN prv_operator_roles role
                            ON role.role_code = assignment.role_code
                           AND role.lifecycle_state = 'ACTIVE'
                          JOIN prv_operator_role_permissions permission
                            ON permission.role_code = role.role_code
                           AND permission.permission_code = 'SUPPORT_SESSION_WRITE'
                          JOIN prv_operator_permission_catalog permission_catalog
                            ON permission_catalog.permission_code =
                               permission.permission_code
                           AND permission_catalog.lifecycle_state = 'ACTIVE'
                           AND permission_catalog.risk_tier = 'L3'
                         WHERE operator.lifecycle_state = 'ACTIVE'
                         FOR SHARE OF operator, assignment, role, permission,
                                      permission_catalog
                    ), operator_lock AS MATERIALIZED (
                        SELECT pg_advisory_xact_lock(candidate.requester_operator_id)
                          FROM candidate
                         WHERE EXISTS (
                             SELECT 1 FROM eligible_operator_authority)
                    ), locked_request AS MATERIALIZED (
                        SELECT request.*
                          FROM prv_support_access_requests request
                          JOIN candidate
                            ON candidate.support_access_request_id =
                               request.support_access_request_id
                          JOIN operator_lock ON TRUE
                         WHERE request.lifecycle_state = 'APPROVED'
                           AND request.access_mode = 'STANDARD'
                           AND request.risk_tier = 'L1'
                           AND request.customer_approval_required
                           AND request.approval_reference IS NOT NULL
                           AND LENGTH(BTRIM(request.approval_reference)) > 0
                           AND request.duration_minutes BETWEEN 5 AND 60
                           AND request.decided_by IS NOT NULL
                           AND request.decided_by <> request.requester_operator_id
                           AND request.decision_due_at > statement_timestamp()
                           AND request.version = ?
                           AND (SELECT COUNT(*)
                                  FROM prv_support_access_request_scopes scope
                                 WHERE scope.support_access_request_id =
                                       request.support_access_request_id) = 1
                           AND EXISTS (
                               SELECT 1 FROM prv_support_access_request_scopes scope
                                WHERE scope.support_access_request_id =
                                      request.support_access_request_id
                                  AND scope.scope_code = 'TENANT_EXPERIENCE_PREVIEW')
                         FOR UPDATE OF request
                    ), inserted_session AS (
                        INSERT INTO prv_support_sessions (
                            support_session_id, provider_tenant_id,
                            provider_operator_id, support_access_request_id,
                            justification, token_hash, started_at, expires_at,
                            last_used_at, access_mode, approval_reference,
                            customer_approval_required, risk_tier,
                            origin_auth_session_id, created_by, updated_by)
                        SELECT ?, request.provider_tenant_id,
                               request.requester_operator_id,
                               request.support_access_request_id,
                               request.justification, ?, statement_timestamp(),
                               statement_timestamp() + make_interval(
                                   mins => request.duration_minutes),
                               statement_timestamp(), request.access_mode,
                               request.approval_reference,
                               request.customer_approval_required,
                               request.risk_tier, request.requester_auth_session_id,
                               request.requester_operator_id,
                               request.requester_operator_id
                          FROM locked_request request
                        RETURNING support_session_id, support_access_request_id,
                                  provider_tenant_id, started_at, expires_at
                    ), inserted_scope AS (
                        INSERT INTO prv_support_session_scopes (
                            support_session_id, scope_code)
                        SELECT session.support_session_id,
                               'TENANT_EXPERIENCE_PREVIEW'
                          FROM inserted_session session
                        RETURNING support_session_id
                    ), activated_request AS (
                        UPDATE prv_support_access_requests request
                           SET lifecycle_state = 'ACTIVATED',
                               activated_at = session.started_at,
                               updated_at = statement_timestamp(),
                               updated_by = request.requester_operator_id,
                               version = request.version + 1
                          FROM inserted_session session, inserted_scope scope
                         WHERE request.support_access_request_id =
                               session.support_access_request_id
                           AND scope.support_session_id = session.support_session_id
                           AND request.lifecycle_state = 'APPROVED'
                           AND request.version = ?
                        RETURNING request.support_access_request_id
                    )
                    SELECT session.support_session_id,
                           session.provider_tenant_id,
                           session.started_at,
                           session.expires_at
                      FROM inserted_session session
                      JOIN activated_request request
                        ON request.support_access_request_id =
                           session.support_access_request_id
                    """, (result, ignored) -> new ActivatedSupportSession(
                            result.getObject("support_session_id", UUID.class),
                            result.getObject("provider_tenant_id", UUID.class),
                            instant(result, "started_at"),
                            instant(result, "expires_at")),
                    supportAccessRequestId, operatorId, originAuthSessionId,
                    expectedVersion, sessionId, tokenHash, expectedVersion)
                    .stream().findFirst();
        } catch (DuplicateKeyException exception) {
            throw activeSupportSessionConflict();
        }
    }

    public List<ProviderDtos.SupportSessionSummary> sessions(UUID tenantId) {
        String tenantClause = tenantId == null ? "" : " WHERE session.provider_tenant_id = ?";
        Object[] arguments = tenantId == null ? new Object[0] : new Object[]{tenantId};
        return summaries(tenantClause, " ORDER BY session.created_at DESC LIMIT 200", arguments);
    }

    public ProviderDtos.SupportSessionSummary summary(UUID sessionId) {
        return summaries(
                " WHERE session.support_session_id = ?", "", new Object[]{sessionId})
                .stream()
                .findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private List<ProviderDtos.SupportSessionSummary> summaries(
            String predicate,
            String ordering,
            Object[] arguments) {
        return jdbc.query("""
                SELECT session.support_session_id,
                       session.support_access_request_id,
                       session.provider_tenant_id,
                       tenant.tenant_key,
                       tenant.display_name AS tenant_name,
                       session.provider_operator_id,
                       operator.display_name AS operator_name,
                       session.lifecycle_state,
                       session.justification,
                       session.access_mode,
                       session.approval_reference,
                       session.customer_approval_required,
                       session.risk_tier,
                       session.started_at,
                       session.expires_at,
                       session.last_used_at,
                       session.revoked_at,
                       session.version,
                       COALESCE(array_agg(scope.scope_code ORDER BY scope.scope_code)
                           FILTER (WHERE scope.scope_code IS NOT NULL), ARRAY[]::varchar[]) AS scopes
                  FROM prv_support_sessions session
                  JOIN prv_tenants tenant ON tenant.provider_tenant_id = session.provider_tenant_id
                  JOIN prv_operators operator ON operator.provider_operator_id = session.provider_operator_id
                  LEFT JOIN prv_support_session_scopes scope
                    ON scope.support_session_id = session.support_session_id
                """ + predicate + """
                 GROUP BY session.support_session_id, tenant.tenant_key, tenant.display_name,
                          operator.display_name
                """ + ordering, (RowMapper<ProviderDtos.SupportSessionSummary>) this::summary, arguments);
    }

    public Optional<SupportSessionRecord> session(UUID sessionId) {
        return sessionQuery("session.support_session_id = ?", sessionId);
    }

    public Optional<SupportSessionRecord> sessionByTokenHash(String tokenHash) {
        return sessionQuery("session.token_hash = ?", tokenHash);
    }

    private Optional<SupportSessionRecord> sessionQuery(String predicate, Object argument) {
        String sql = """
                SELECT session.support_session_id, session.provider_tenant_id,
                       session.provider_operator_id, session.lifecycle_state,
                       session.token_hash, session.access_mode, session.expires_at,
                       session.last_used_at,
                       LEAST(session.expires_at,
                           session.last_used_at + INTERVAL '15 minutes') AS effective_expires_at,
                       session.version, session.origin_auth_session_id
                  FROM prv_support_sessions session
                 WHERE %s
                """.formatted(predicate);
        return jdbc.query(sql, (result, ignored) -> new SupportSessionRecord(
                        result.getObject("support_session_id", UUID.class),
                        result.getObject("provider_tenant_id", UUID.class),
                        result.getLong("provider_operator_id"),
                        result.getString("lifecycle_state"),
                        result.getString("token_hash"),
                        result.getString("access_mode"),
                        instant(result, "expires_at"),
                        instant(result, "last_used_at"),
                        instant(result, "effective_expires_at"),
                        result.getLong("version"),
                        result.getObject("origin_auth_session_id", UUID.class)),
                argument).stream().findFirst();
    }

    public List<String> scopes(UUID sessionId) {
        return jdbc.queryForList("""
                SELECT scope.scope_code
                  FROM prv_support_session_scopes scope
                  JOIN prv_support_scope_catalog catalog
                    ON catalog.scope_code = scope.scope_code
                   AND catalog.lifecycle_state = 'ACTIVE'
                 WHERE scope.support_session_id = ?
                 ORDER BY scope.scope_code
                """, String.class, sessionId);
    }

    public int scopeCount(UUID sessionId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_support_session_scopes
                 WHERE support_session_id = ?
                """, Integer.class, sessionId);
        return count == null ? 0 : count;
    }

    public Optional<SupportSessionTouch> touch(
            UUID sessionId,
            Long operatorId,
            UUID originAuthSessionId) {
        return jdbc.query("""
                WITH eligible_tenant AS MATERIALIZED (
                    SELECT provider_tenant_id, auth_tenant_id
                      FROM prv_tenants
                     WHERE provider_tenant_id = (
                               SELECT provider_tenant_id
                                 FROM prv_support_sessions
                                WHERE support_session_id = ?)
                       AND lifecycle_state = 'ACTIVE'
                       AND onboarding_state = 'READY'
                       AND auth_tenant_id IS NOT NULL
                     FOR SHARE
                ), enabled_control AS MATERIALIZED (
                    SELECT control_key
                      FROM prv_support_activation_control
                     WHERE control_key = 'STANDARD_JIT'
                       AND activation_enabled
                     FOR SHARE
                )
                UPDATE prv_support_sessions session
                   SET last_used_at = statement_timestamp(),
                       updated_at = statement_timestamp(),
                       updated_by = ?
                  FROM eligible_tenant tenant, enabled_control control
                 WHERE session.support_session_id = ?
                   AND session.provider_tenant_id = tenant.provider_tenant_id
                   AND session.provider_operator_id = ?
                   AND session.lifecycle_state = 'ACTIVE'
                   AND session.expires_at > statement_timestamp()
                   AND session.last_used_at > statement_timestamp() - INTERVAL '15 minutes'
                   AND session.origin_auth_session_id = ?
                RETURNING tenant.auth_tenant_id,
                          LEAST(session.expires_at,
                              session.last_used_at + INTERVAL '15 minutes') AS effective_expires_at
                """, (result, ignored) -> new SupportSessionTouch(
                        result.getLong("auth_tenant_id"),
                        instant(result, "effective_expires_at")),
                sessionId, operatorId, sessionId, operatorId, originAuthSessionId)
                .stream().findFirst();
    }

    public boolean revoke(UUID sessionId, Long operatorId, long expectedVersion) {
        return jdbc.update("""
                UPDATE prv_support_sessions
                   SET lifecycle_state = 'REVOKED',
                       revoked_at = statement_timestamp(),
                       revoked_by = ?,
                       updated_at = statement_timestamp(),
                       updated_by = ?,
                       version = version + 1
                 WHERE support_session_id = ?
                   AND lifecycle_state = 'ACTIVE'
                   AND version = ?
                """, operatorId, operatorId, sessionId, expectedVersion) == 1;
    }

    public SupportActivationState activationState() {
        return jdbc.query("""
                SELECT activation_enabled, version
                  FROM prv_support_activation_control
                 WHERE control_key = 'STANDARD_JIT'
                """, (result, ignored) -> new SupportActivationState(
                        result.getBoolean("activation_enabled"),
                        result.getLong("version")))
                .stream().findFirst()
                .orElse(new SupportActivationState(false, -1));
    }

    public SupportActivationState disableActivation(
            Long operatorId,
            String reason,
            String correlationId) {
        int changed = jdbc.update("""
                UPDATE prv_support_activation_control
                   SET activation_enabled = FALSE,
                       change_reason = ?,
                       change_correlation_id = ?,
                       changed_at = statement_timestamp(),
                       changed_by = ?,
                       version = version + 1
                 WHERE control_key = 'STANDARD_JIT'
                   AND activation_enabled
                """, reason, correlationId, operatorId);
        if (changed == 1) {
            return activationState();
        }
        SupportActivationState current = activationState();
        if (current.version() >= 0 && !current.enabled()) {
            return current;
        }
        throw new BaseException(ErrorCode.INVALID_STATE, "Support activation control is unavailable.");
    }

    int expireSupportSessions() {
        return jdbc.update("""
                UPDATE prv_support_sessions
                   SET lifecycle_state = 'EXPIRED',
                       updated_at = statement_timestamp(),
                       updated_by = provider_operator_id,
                       version = version + 1
                 WHERE lifecycle_state = 'ACTIVE'
                   AND (expires_at <= statement_timestamp()
                        OR last_used_at <= statement_timestamp() - INTERVAL '15 minutes')
                """);
    }

    void lockContainmentLedger() {
        jdbc.execute("""
                SELECT pg_advisory_xact_lock(
                    hashtextextended('dwp:provider:support-containment', 0))
                """);
    }

    int pulseAuthorityReconciliation() {
        return jdbc.update("""
                UPDATE prv_support_activation_control
                   SET authority_reconciled_at = statement_timestamp()
                 WHERE control_key = 'STANDARD_JIT'
                """);
    }

    private ProviderDtos.SupportSessionSummary summary(ResultSet result, int ignored)
            throws SQLException {
        return new ProviderDtos.SupportSessionSummary(
                result.getObject("support_session_id", UUID.class),
                result.getObject("support_access_request_id", UUID.class),
                result.getObject("provider_tenant_id", UUID.class),
                result.getString("tenant_key"),
                result.getString("tenant_name"),
                result.getLong("provider_operator_id"),
                result.getString("operator_name"),
                result.getString("lifecycle_state"),
                result.getString("justification"),
                List.of((String[]) result.getArray("scopes").getArray()),
                result.getString("access_mode"),
                result.getString("approval_reference"),
                result.getBoolean("customer_approval_required"),
                result.getString("risk_tier"),
                instant(result, "started_at"),
                instant(result, "expires_at"),
                instant(result, "last_used_at"),
                instant(result, "revoked_at"),
                result.getLong("version"));
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private BaseException activeSupportSessionConflict() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "End the current support session before activating access to another tenant.");
    }

    public record SupportSessionRecord(
            UUID supportSessionId,
            UUID tenantId,
            Long operatorId,
            String lifecycleState,
            String tokenHash,
            String accessMode,
            Instant absoluteExpiresAt,
            Instant lastUsedAt,
            Instant effectiveExpiresAt,
            long version,
            UUID originAuthSessionId) {
    }

    public record SupportSessionTouch(Long authTenantId, Instant effectiveExpiresAt) {
    }

    public record ActivatedSupportSession(
            UUID supportSessionId,
            UUID tenantId,
            Instant startedAt,
            Instant expiresAt) {
    }

    public record SupportActivationState(boolean enabled, long version) {
    }
}
