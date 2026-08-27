package com.dwp.services.provider;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ProviderSupportAuthSessionBindingMigrationPostgresTest {

    private static final UUID TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private PGSimpleDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        flyway(null).clean();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void freshV48RejectsUnboundRequestsInvalidDurationsAndMismatchedSessionCommit() {
        flyway("48").migrate();
        Long operatorId = seededOperatorId();
        enableActivation(operatorId);

        assertThatThrownBy(() -> insertRequest(
                UUID.randomUUID(), operatorId, null, "PENDING_APPROVAL", 15))
                .rootCause()
                .hasMessageContaining("ck_prv_support_request_auth_session_binding");
        for (int invalidDuration : new int[]{4, 61}) {
            assertThatThrownBy(() -> insertRequest(
                    UUID.randomUUID(), operatorId, UUID.randomUUID(),
                    "PENDING_APPROVAL", invalidDuration))
                    .rootCause()
                    .hasMessageContaining("ck_prv_support_access_duration");
        }
        UUID maximumDurationRequest = UUID.randomUUID();
        insertRequest(
                maximumDurationRequest, operatorId, UUID.randomUUID(),
                "PENDING_APPROVAL", 60);
        assertThat(jdbc.queryForObject("""
                SELECT duration_minutes FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, Integer.class, maximumDurationRequest)).isEqualTo(60);

        UUID requestId = UUID.randomUUID();
        UUID originalAuthSessionId = UUID.randomUUID();
        insertRequest(requestId, operatorId, originalAuthSessionId, "APPROVED", 15);
        jdbc.update("""
                INSERT INTO prv_support_access_request_scopes (
                    support_access_request_id, scope_code)
                VALUES (?, 'TENANT_EXPERIENCE_PREVIEW')
                """, requestId);
        UUID supportSessionId = UUID.randomUUID();
        TransactionTemplate transactions =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            jdbc.update("""
                    INSERT INTO prv_support_sessions (
                        support_session_id, provider_tenant_id, provider_operator_id,
                        support_access_request_id, justification, token_hash,
                        expires_at, last_used_at, access_mode, approval_reference,
                        customer_approval_required, risk_tier, origin_auth_session_id)
                    VALUES (?, ?, ?, ?, 'Mismatched auth binding test', ?,
                            CURRENT_TIMESTAMP + INTERVAL '15 minutes', CURRENT_TIMESTAMP,
                            'STANDARD', 'CUSTOMER-APPROVAL-MIGRATION', TRUE, 'L1', ?)
                    """, supportSessionId, TENANT_ID, operatorId, requestId,
                    "a".repeat(64), UUID.randomUUID());
            jdbc.update("""
                    INSERT INTO prv_support_session_scopes (support_session_id, scope_code)
                    VALUES (?, 'TENANT_EXPERIENCE_PREVIEW')
                    """, supportSessionId);
            jdbc.update("""
                    UPDATE prv_support_access_requests
                       SET lifecycle_state = 'ACTIVATED', activated_at = CURRENT_TIMESTAMP,
                           updated_at = CURRENT_TIMESTAMP, version = version + 1
                     WHERE support_access_request_id = ?
                    """, requestId);
        })).rootCause().hasMessageContaining(
                "is not bound to the original request auth session");

        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, String.class, requestId)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_support_sessions WHERE support_session_id = ?
                """, Integer.class, supportSessionId)).isZero();
        flyway("48").validate();
    }

    @Test
    void upgradeFromV48TerminatesEveryPreviouslyExecutableGrantWithRetainedEvidence() {
        flyway("48").migrate();
        Long operatorId = seededOperatorId();
        enableActivation(operatorId);
        UUID pendingRequestId = UUID.randomUUID();
        insertRequest(
                pendingRequestId, operatorId, UUID.randomUUID(),
                "PENDING_APPROVAL", 15);
        UUID activeRequestId = UUID.randomUUID();
        UUID activeSessionId = UUID.randomUUID();
        UUID sessionAuthId = UUID.randomUUID();
        insertRequest(activeRequestId, operatorId, sessionAuthId, "APPROVED", 15);
        jdbc.update("""
                INSERT INTO prv_support_access_request_scopes (
                    support_access_request_id, scope_code)
                VALUES (?, 'TENANT_EXPERIENCE_PREVIEW')
                """, activeRequestId);
        new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .executeWithoutResult(ignored -> {
                    jdbc.update("""
                            INSERT INTO prv_support_sessions (
                                support_session_id, provider_tenant_id, provider_operator_id,
                                support_access_request_id, justification, token_hash,
                                expires_at, last_used_at, access_mode, approval_reference,
                                customer_approval_required, risk_tier, origin_auth_session_id)
                            VALUES (?, ?, ?, ?, 'Legacy active support request', ?,
                                    CURRENT_TIMESTAMP + INTERVAL '15 minutes', CURRENT_TIMESTAMP,
                                    'STANDARD', 'CUSTOMER-APPROVAL-MIGRATION', TRUE, 'L1', ?)
                            """, activeSessionId, TENANT_ID, operatorId, activeRequestId,
                            "b".repeat(64), sessionAuthId);
                    jdbc.update("""
                            INSERT INTO prv_support_session_scopes (
                                support_session_id, scope_code)
                            VALUES (?, 'TENANT_EXPERIENCE_PREVIEW')
                            """, activeSessionId);
                    jdbc.update("""
                            UPDATE prv_support_access_requests
                               SET lifecycle_state = 'ACTIVATED',
                                   activated_at = CURRENT_TIMESTAMP,
                                   updated_at = CURRENT_TIMESTAMP,
                                   version = version + 1
                             WHERE support_access_request_id = ?
                            """, activeRequestId);
                });

        flyway(null).migrate();

        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, String.class, pendingRequestId)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_support_sessions
                 WHERE support_session_id = ?
                """, String.class, activeSessionId)).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, String.class, activeRequestId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_support_access_requests
                 WHERE support_access_request_id IN (?, ?)
                   AND requester_auth_session_id IS NOT NULL
                """, Integer.class, pendingRequestId, activeRequestId)).isEqualTo(2);
        assertRetainedAudit(
                "provider.support-access.expired-automatically", pendingRequestId.toString());
        assertRetainedAudit(
                "provider.support-session.revoked-by-exact-grant-migration",
                activeSessionId.toString());
        assertRetainedAudit(
                "provider.support-access.completed-after-session-end",
                activeRequestId.toString());
        flyway(null).validate();
    }

    private void insertRequest(
            UUID requestId,
            Long operatorId,
            UUID authSessionId,
            String lifecycleState,
            int durationMinutes) {
        boolean approved = "APPROVED".equals(lifecycleState);
        jdbc.update("""
                INSERT INTO prv_support_access_requests (
                    support_access_request_id, provider_tenant_id, requester_operator_id,
                    requester_auth_session_id, lifecycle_state, justification,
                    duration_minutes, approval_reference, customer_approval_required,
                    risk_tier, request_key, request_fingerprint, decision_due_at,
                    decided_at, decided_by, decision_reason, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Auth binding migration test', ?,
                        'CUSTOMER-APPROVAL-MIGRATION', TRUE, 'L1', ?, ?,
                        CURRENT_TIMESTAMP + INTERVAL '1 hour',
                        CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                        CASE WHEN ? THEN ? ELSE NULL END,
                        CASE WHEN ? THEN 'Independent migration test approval' ELSE NULL END,
                        ?, ?)
                """, requestId, TENANT_ID, operatorId, authSessionId, lifecycleState,
                durationMinutes, "request-" + requestId, "c".repeat(64),
                approved, approved, operatorId, approved, operatorId, operatorId);
    }

    private void enableActivation(Long operatorId) {
        jdbc.update("""
                UPDATE prv_support_activation_control
                   SET activation_enabled = TRUE,
                       change_reason = 'Migration integration fixture',
                       change_correlation_id = 'migration:v48:test',
                       changed_by = ?, changed_at = CURRENT_TIMESTAMP,
                       version = version + 1
                 WHERE control_key = 'STANDARD_JIT' AND NOT activation_enabled
                """, operatorId);
    }

    private void assertRetainedAudit(String action, String targetId) {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_audit_events event
                  JOIN sys_audit_outbox outbox ON outbox.event_id = event.audit_event_id
                 WHERE event.action = ? AND event.target_id = ?
                   AND outbox.payload ->> 'retentionClass' = 'EXTENDED'
                """, Integer.class, action, targetId)).isEqualTo(1);
    }

    private Long seededOperatorId() {
        return jdbc.queryForObject("""
                SELECT provider_operator_id FROM prv_operators
                 WHERE auth_tenant_id = 1 AND auth_user_id = 900001
                """, Long.class);
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }
}
