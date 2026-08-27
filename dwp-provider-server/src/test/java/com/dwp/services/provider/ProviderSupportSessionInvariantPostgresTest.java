package com.dwp.services.provider;

import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.dwp.services.provider.support.ProviderSupportRequestRepository;
import com.dwp.services.provider.support.ProviderSupportSessionRepository;
import com.dwp.services.provider.support.ProviderSupportSessionLifecycleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ProviderSupportSessionInvariantPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static ProviderSupportRequestRepository repository;
    private static ProviderSupportSessionRepository sessionRepository;
    private static ProviderSupportSessionLifecycleService lifecycleService;
    private static PGSimpleDataSource dataSource;
    private static TransactionTemplate transactions;
    private static boolean migrationDefaultedActivationOff;

    @BeforeAll
    static void migrate() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        migrationDefaultedActivationOff = Boolean.FALSE.equals(jdbc.queryForObject("""
                SELECT activation_enabled
                  FROM prv_support_activation_control
                 WHERE control_key = 'STANDARD_JIT'
                """, Boolean.class));
        repository = new ProviderSupportRequestRepository(jdbc);
        sessionRepository = new ProviderSupportSessionRepository(jdbc);
        lifecycleService = new ProviderSupportSessionLifecycleService(sessionRepository, repository);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void enableTheExplicitTestDeploymentDatabaseControl() {
        jdbc.update("""
                UPDATE prv_support_scope_catalog
                   SET lifecycle_state = 'ACTIVE', risk_tier = 'L1',
                       requires_customer_approval = TRUE
                 WHERE scope_code = 'TENANT_EXPERIENCE_PREVIEW'
                """);
        jdbc.update("""
                UPDATE prv_tenants
                   SET lifecycle_state = 'ACTIVE', onboarding_state = 'READY',
                       auth_tenant_id = 1
                 WHERE provider_tenant_id = '00000000-0000-0000-0000-000000000001'
                """);
        jdbc.update("""
                UPDATE prv_support_activation_control
                   SET activation_enabled = TRUE,
                       change_reason = 'Enable support activation for an isolated integration test.',
                       change_correlation_id = 'test:provider-support-activation',
                       changed_at = CURRENT_TIMESTAMP,
                       changed_by = ?,
                       version = version + 1
                 WHERE control_key = 'STANDARD_JIT'
                   AND NOT activation_enabled
                """, operatorId());
    }

    @Test
    void registersTheDedicatedPreviewScope() {
        assertThat(migrationDefaultedActivationOff).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state = 'ACTIVE'
                       AND risk_tier = 'L1'
                       AND requires_customer_approval
                  FROM prv_support_scope_catalog
                 WHERE scope_code = 'TENANT_EXPERIENCE_PREVIEW'
                """, Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state
                  FROM prv_support_scope_catalog
                 WHERE scope_code = 'WORKFORCE_READ'
                """, String.class)).isEqualTo("RETIRED");
        assertThat(jdbc.queryForList("""
                SELECT lifecycle_state
                  FROM prv_support_scope_catalog
                 WHERE scope_code IN (
                     'TENANT_CONFIGURATION_READ', 'TENANT_CONFIGURATION_WRITE')
                 ORDER BY scope_code
                """, String.class)).containsExactly("RETIRED", "RETIRED");
    }

    @Test
    void expiresAnIdleSessionAtFifteenMinutesAndRetainsTheReason() {
        Long operatorId = newOperator(990046, "Idle expiry operator");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID sessionId = UUID.randomUUID();
        insertExactSession(sessionId, operatorId, tenantId, "4".repeat(64),
                "CURRENT_TIMESTAMP - INTERVAL '20 minutes'",
                "CURRENT_TIMESTAMP + INTERVAL '30 minutes'",
                "CURRENT_TIMESTAMP - INTERVAL '16 minutes'");

        lifecycleService.expireElapsedSessions();
        assertThat(sessionRepository.session(sessionId)).get()
                .extracting(ProviderSupportSessionRepository.SupportSessionRecord::lifecycleState)
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("""
                SELECT redacted_snapshot ->> 'reasonCode'
                  FROM prv_audit_events
                 WHERE action = 'provider.support-session.expired-automatically'
                   AND target_id = ?
                """, String.class, sessionId.toString())).isEqualTo("IDLE_EXPIRY");
    }

    @Test
    void atomicallyTouchesTheIdleLeaseWithoutExtendingTheAbsoluteExpiry() {
        Long operatorId = newOperator(990047, "Idle touch operator");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID sessionId = UUID.randomUUID();
        insertExactSession(sessionId, operatorId, tenantId, "5".repeat(64),
                "CURRENT_TIMESTAMP - INTERVAL '14 minutes'",
                "CURRENT_TIMESTAMP + INTERVAL '5 minutes'",
                "CURRENT_TIMESTAMP - INTERVAL '14 minutes'");
        UUID authSessionId = jdbc.queryForObject("""
                SELECT origin_auth_session_id FROM prv_support_sessions
                 WHERE support_session_id = ?
                """, UUID.class, sessionId);

        ProviderSupportSessionRepository.SupportSessionTouch touch = sessionRepository
                .touch(sessionId, operatorId, authSessionId).orElseThrow();
        java.sql.Timestamp absoluteExpiry = jdbc.queryForObject("""
                SELECT expires_at FROM prv_support_sessions WHERE support_session_id = ?
                """, java.sql.Timestamp.class, sessionId);

        assertThat(touch.authTenantId()).isEqualTo(1L);
        assertThat(touch.effectiveExpiresAt()).isEqualTo(absoluteExpiry.toInstant());
        assertThat(jdbc.queryForObject("""
                SELECT last_used_at > CURRENT_TIMESTAMP - INTERVAL '5 seconds'
                  FROM prv_support_sessions WHERE support_session_id = ?
                """, Boolean.class, sessionId)).isTrue();
    }

    @Test
    void databaseKillSwitchRevokesEverySessionAndWritesExtendedAuditEvidence() {
        Long operatorId = newOperator(990048, "Kill switch operator");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID sessionId = UUID.randomUUID();
        insertExactSession(sessionId, operatorId, tenantId, "6".repeat(64),
                "CURRENT_TIMESTAMP", "CURRENT_TIMESTAMP + INTERVAL '30 minutes'",
                "CURRENT_TIMESTAMP");

        sessionRepository.disableActivation(
                operatorId, "Immediate support access containment", "test:kill-switch");

        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_support_sessions WHERE support_session_id = ?
                """, String.class, sessionId)).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_audit_events event
                  JOIN sys_audit_outbox outbox ON outbox.event_id = event.audit_event_id
                 WHERE event.action = 'provider.support-session.revoked-by-kill-switch'
                   AND event.target_id = ?
                   AND outbox.payload ->> 'retentionClass' = 'EXTENDED'
                """, Integer.class, sessionId.toString())).isEqualTo(1);
        assertThat(sessionRepository.activationState().enabled()).isFalse();
    }

    @Test
    void activationControlRejectsUnauthorizedOrSameStateWritesAndDerivesEvidence() {
        Long authorizedAdmin = newOperator(990063, "Activation control administrator");
        Long unauthorizedOperator = jdbc.queryForObject("""
                INSERT INTO prv_operators (
                    auth_tenant_id, auth_user_id, display_name, role_code, lifecycle_state)
                VALUES (1, 990064, 'Unauthorized activation operator',
                        'PROVIDER_OPERATOR', 'ACTIVE')
                RETURNING provider_operator_id
                """, Long.class);
        jdbc.update("""
                INSERT INTO prv_operator_role_assignments (
                    provider_operator_id, role_code, lifecycle_state, created_by)
                VALUES (?, 'PROVIDER_OPERATOR', 'ACTIVE', ?)
                """, unauthorizedOperator, unauthorizedOperator);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE prv_support_activation_control
                   SET change_reason = 'Same-state metadata spoof',
                       changed_by = ?, version = version + 1
                 WHERE control_key = 'STANDARD_JIT'
                """, authorizedAdmin)).rootCause()
                .hasMessageContaining("one exact state transition");

        sessionRepository.disableActivation(
                authorizedAdmin, "Exercise governed containment", "34".repeat(16));
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE prv_support_activation_control
                   SET activation_enabled = TRUE,
                       change_reason = 'Unauthorized re-enable',
                       change_correlation_id = 'secret-correlation-canary',
                       changed_at = TIMESTAMPTZ '2000-01-01 00:00:00+00',
                       changed_by = ?, version = version + 1
                 WHERE control_key = 'STANDARD_JIT'
                """, unauthorizedOperator)).rootCause()
                .hasMessageContaining("actor is not authorized");

        jdbc.update("""
                UPDATE prv_support_activation_control
                   SET activation_enabled = TRUE,
                       change_reason = '  Governed database re-enable  ',
                       change_correlation_id = 'secret-correlation-canary',
                       changed_at = TIMESTAMPTZ '2000-01-01 00:00:00+00',
                       changed_by = ?, version = version + 1
                 WHERE control_key = 'STANDARD_JIT'
                """, authorizedAdmin);
        assertThat(jdbc.queryForObject("""
                SELECT activation_enabled
                       AND change_reason = 'Governed database re-enable'
                       AND changed_at > statement_timestamp() - INTERVAL '5 seconds'
                       AND change_correlation_id ~ '^[0-9a-f]{32}$'
                       AND change_correlation_id <> repeat('0', 32)
                  FROM prv_support_activation_control
                 WHERE control_key = 'STANDARD_JIT'
                """, Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT change_correlation_id
                  FROM prv_support_activation_control
                 WHERE control_key = 'STANDARD_JIT'
                """, String.class)).doesNotContain("secret-correlation-canary");
    }

    @Test
    void tenantReadinessRegressionAtomicallyRevokesItsSupportSession() {
        Long operatorId = newOperator(990049, "Tenant readiness operator");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID sessionId = UUID.randomUUID();
        insertExactSession(sessionId, operatorId, tenantId, "7".repeat(64),
                "CURRENT_TIMESTAMP", "CURRENT_TIMESTAMP + INTERVAL '30 minutes'",
                "CURRENT_TIMESTAMP");

        try {
            jdbc.update("""
                    UPDATE prv_tenants SET onboarding_state = 'FAILED'
                     WHERE provider_tenant_id = ?
                    """, tenantId);

            assertThat(jdbc.queryForObject("""
                    SELECT lifecycle_state FROM prv_support_sessions WHERE support_session_id = ?
                    """, String.class, sessionId)).isEqualTo("REVOKED");
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM prv_audit_events
                     WHERE action = 'provider.support-session.revoked-for-tenant-state'
                       AND target_id = ?
                    """, Integer.class, sessionId.toString())).isEqualTo(1);
        } finally {
            jdbc.update("""
                    UPDATE prv_tenants SET onboarding_state = 'READY'
                     WHERE provider_tenant_id = ?
                    """, tenantId);
        }
    }

    @Test
    void tenantAuthLinkRemovalKeepsSystemAuditInAPositiveTenantPartition() {
        Long operatorId = newOperator(990164, "Tenant auth unlink containment owner");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID sessionId = UUID.randomUUID();
        insertExactSession(sessionId, operatorId, tenantId, uniqueTokenHash(),
                "statement_timestamp()", "statement_timestamp() + INTERVAL '30 minutes'",
                "statement_timestamp()");

        try {
            jdbc.update("""
                    UPDATE prv_tenants SET auth_tenant_id = NULL
                     WHERE provider_tenant_id = ?
                    """, tenantId);

            assertThat(jdbc.queryForMap("""
                    SELECT lifecycle_state, revocation_origin
                      FROM prv_support_sessions WHERE support_session_id = ?
                    """, sessionId))
                    .containsEntry("lifecycle_state", "REVOKED")
                    .containsEntry("revocation_origin", "AUTOMATIC_TENANT_CONTAINMENT");
            assertThat(jdbc.queryForObject("""
                    SELECT (outbox.payload ->> 'tenantId')::bigint > 0
                           AND outbox.payload ->> 'actorType' = 'SYSTEM'
                           AND outbox.payload -> 'actorRoles' = '[]'::jsonb
                           AND outbox.payload #>> '{metadata,providerTenantId}' = ?
                           AND outbox.payload #>> '{metadata,transitionOrigin}' =
                               'AUTOMATIC_TENANT_CONTAINMENT'
                      FROM prv_audit_events event
                      JOIN sys_audit_outbox outbox ON outbox.event_id = event.audit_event_id
                     WHERE event.action = 'provider.support-session.revoked-for-tenant-state'
                       AND event.target_id = ?
                    """, Boolean.class, tenantId.toString(), sessionId.toString())).isTrue();
        } finally {
            jdbc.update("""
                    UPDATE prv_tenants SET auth_tenant_id = 1
                     WHERE provider_tenant_id = ?
                    """, tenantId);
        }
    }

    @Test
    void deferredDatabaseContractRejectsACommittedBroadScopeSession() {
        Long operatorId = newOperator(990050, "Broad scope rejection operator");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            UUID sessionId = UUID.randomUUID();
            UUID requestId = insertApprovedRequest(operatorId, tenantId, UUID.randomUUID());
            jdbc.update("""
                    INSERT INTO prv_support_sessions (
                        support_session_id, provider_tenant_id, provider_operator_id,
                        support_access_request_id, justification, token_hash, expires_at, last_used_at,
                        access_mode, approval_reference, customer_approval_required,
                        risk_tier, origin_auth_session_id)
                    SELECT ?, ?, ?, ?, 'Broad scope rejection', ?,
                            CURRENT_TIMESTAMP + INTERVAL '30 minutes', CURRENT_TIMESTAMP,
                            'STANDARD', 'CUSTOMER-APPROVAL-TEST', TRUE, 'L1',
                            requester_auth_session_id
                      FROM prv_support_access_requests
                     WHERE support_access_request_id = ?
                    """, sessionId, tenantId, operatorId, requestId, "8".repeat(64), requestId);
            jdbc.update("""
                    INSERT INTO prv_support_session_scopes (support_session_id, scope_code)
                    VALUES (?, 'TENANT_CONFIGURATION_READ')
                    """, sessionId);
        })).rootCause().hasMessageContaining("one immutable preview scope");
    }

    @Test
    void directSqlApprovalRequiresAnActiveAuthorizedIndependentReviewerAndUsesDatabaseTime() {
        Long requesterId = newOperator(990051, "Direct SQL request owner");
        Long unauthorizedReviewer = jdbc.queryForObject("""
                INSERT INTO prv_operators (
                    auth_tenant_id, auth_user_id, display_name, role_code, lifecycle_state)
                VALUES (1, 990052, 'Unauthorized direct SQL reviewer',
                        'PROVIDER_OPERATOR', 'ACTIVE')
                RETURNING provider_operator_id
                """, Long.class);
        jdbc.update("""
                INSERT INTO prv_operator_role_assignments (
                    provider_operator_id, role_code, lifecycle_state, created_by)
                VALUES (?, 'PROVIDER_OPERATOR', 'ACTIVE', ?)
                """, unauthorizedReviewer, unauthorizedReviewer);
        UUID requestId = insertPendingRequest(
                requesterId,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE prv_support_access_requests
                   SET lifecycle_state = 'APPROVED', decided_by = ?,
                       decided_at = TIMESTAMPTZ '2000-01-01 00:00:00+00',
                       decision_reason = 'Unauthorized direct SQL approval',
                       version = version + 1
                 WHERE support_access_request_id = ?
                """, unauthorizedReviewer, requestId))
                .rootCause().hasMessageContaining("decision evidence is immutable");

        Long authorizedReviewer = newOperator(990053, "Authorized direct SQL reviewer");
        jdbc.update("""
                UPDATE prv_support_access_requests
                   SET lifecycle_state = 'APPROVED', decided_by = ?,
                       decided_at = TIMESTAMPTZ '2000-01-01 00:00:00+00',
                       decision_reason = 'Authorized independent approval',
                       version = version + 1
                 WHERE support_access_request_id = ?
                """, authorizedReviewer, requestId);

        assertThat(jdbc.queryForObject("""
                SELECT decided_at > statement_timestamp() - INTERVAL '5 seconds'
                       AND decided_at <= decision_due_at
                  FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, Boolean.class, requestId)).isTrue();
        assertRetainedAudit("provider.support-access.decision-persisted", requestId.toString());
    }

    @Test
    void directSqlRequestCreationRejectsAnOperatorWithoutActiveSupportAuthority() {
        Long unauthorizedRequester = jdbc.queryForObject("""
                INSERT INTO prv_operators (
                    auth_tenant_id, auth_user_id, display_name, role_code, lifecycle_state)
                VALUES (1, 990056, 'Unauthorized direct SQL requester',
                        'PROVIDER_OPERATOR', 'ACTIVE')
                RETURNING provider_operator_id
                """, Long.class);
        jdbc.update("""
                INSERT INTO prv_operator_role_assignments (
                    provider_operator_id, role_code, lifecycle_state, created_by)
                VALUES (?, 'PROVIDER_OPERATOR', 'ACTIVE', ?)
                """, unauthorizedRequester, unauthorizedRequester);
        UUID requestId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO prv_support_access_requests (
                    support_access_request_id, provider_tenant_id,
                    requester_operator_id, requester_auth_session_id,
                    lifecycle_state, justification, duration_minutes,
                    approval_reference, customer_approval_required, risk_tier,
                    request_key, request_fingerprint, decision_due_at,
                    created_by, updated_by)
                VALUES (?, '00000000-0000-0000-0000-000000000001', ?, ?,
                        'PENDING_APPROVAL', 'Unauthorized request', 15,
                        'CUSTOMER-APPROVAL-TEST', TRUE, 'L1', ?, ?,
                        statement_timestamp() + INTERVAL '1 hour', ?, ?)
                """, requestId, unauthorizedRequester, UUID.randomUUID(),
                "unauthorized-" + requestId, "5".repeat(64),
                unauthorizedRequester, unauthorizedRequester))
                .rootCause().hasMessageContaining("lacks effective support authority");
    }

    @Test
    void immutableGrantLedgerRejectsDeleteMutationAndTerminalResurrection() {
        Long operatorId = newOperator(990054, "Immutable grant operator");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID sessionId = UUID.randomUUID();
        UUID requestId = insertExactSession(
                sessionId, operatorId, tenantId, "2".repeat(64),
                "CURRENT_TIMESTAMP", "CURRENT_TIMESTAMP + INTERVAL '15 minutes'",
                "CURRENT_TIMESTAMP");

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE prv_support_access_requests SET justification = 'Tampered purpose'
                 WHERE support_access_request_id = ?
                """, requestId)).rootCause()
                .hasMessageContaining("security metadata is immutable");
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM prv_support_session_scopes WHERE support_session_id = ?
                """, sessionId)).rootCause()
                .hasMessageContaining("scope history is immutable");
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM prv_support_sessions WHERE support_session_id = ?
                """, sessionId)).rootCause()
                .hasMessageContaining("session history is immutable");

        assertThat(sessionRepository.revoke(sessionId, operatorId, 0)).isTrue();
        assertRetainedAudit("provider.support-session.revocation-persisted", sessionId.toString());
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE prv_support_sessions
                   SET lifecycle_state = 'ACTIVE', version = version + 1
                 WHERE support_session_id = ?
                """, sessionId)).rootCause()
                .hasMessageContaining("invalid support session lifecycle transition");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE prv_support_access_requests
                   SET lifecycle_state = 'ACTIVATED', version = version + 1
                 WHERE support_access_request_id = ?
                """, requestId)).rootCause()
                .hasMessageContaining("invalid support request lifecycle transition");
    }

    @Test
    void directSqlCancelAndRevokeRequireTheSameLifecycleActorsAsJavaCommands() {
        Long ownerId = newOperator(990057, "Direct lifecycle owner");
        Long unauthorizedActor = jdbc.queryForObject("""
                INSERT INTO prv_operators (
                    auth_tenant_id, auth_user_id, display_name, role_code, lifecycle_state)
                VALUES (1, 990058, 'Direct lifecycle attacker',
                        'PROVIDER_OPERATOR', 'ACTIVE')
                RETURNING provider_operator_id
                """, Long.class);
        jdbc.update("""
                INSERT INTO prv_operator_role_assignments (
                    provider_operator_id, role_code, lifecycle_state, created_by)
                VALUES (?, 'PROVIDER_OPERATOR', 'ACTIVE', ?)
                """, unauthorizedActor, unauthorizedActor);
        UUID requestId = insertPendingRequest(
                ownerId,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE prv_support_access_requests
                   SET lifecycle_state = 'CANCELLED', cancelled_by = ?,
                       cancelled_at = statement_timestamp(),
                       cancellation_reason = 'Unauthorized cancellation',
                       version = version + 1
                 WHERE support_access_request_id = ?
                """, unauthorizedActor, requestId))
                .rootCause().hasMessageContaining("cancellation evidence is immutable");

        UUID sessionId = UUID.randomUUID();
        insertExactSession(
                sessionId, ownerId,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.randomUUID().toString().replace("-", "").repeat(2), "CURRENT_TIMESTAMP",
                "CURRENT_TIMESTAMP + INTERVAL '15 minutes'", "CURRENT_TIMESTAMP");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE prv_support_sessions
                   SET lifecycle_state = 'REVOKED', revoked_by = ?,
                       revoked_at = statement_timestamp(), version = version + 1
                 WHERE support_session_id = ?
                """, unauthorizedActor, sessionId))
                .rootCause().hasMessageContaining("revocation actor is not authorized");

        Long providerAdmin = newOperator(990062, "Authorized direct lifecycle admin");
        java.sql.Timestamp retainedLastUsedAt = jdbc.queryForObject("""
                SELECT last_used_at FROM prv_support_sessions WHERE support_session_id = ?
                """, java.sql.Timestamp.class, sessionId);
        jdbc.update("""
                UPDATE prv_support_sessions
                   SET lifecycle_state = 'REVOKED', revoked_by = ?, updated_by = ?,
                       revoked_at = TIMESTAMPTZ '2000-01-01 00:00:00+00',
                       last_used_at = TIMESTAMPTZ '2099-01-01 00:00:00+00',
                       updated_at = TIMESTAMPTZ '2000-01-01 00:00:00+00',
                       version = version + 1
                 WHERE support_session_id = ?
                """, providerAdmin, unauthorizedActor, sessionId);
        assertThat(jdbc.queryForObject("""
                SELECT revoked_by = ? AND updated_by = ?
                       AND revoked_at > statement_timestamp() - INTERVAL '5 seconds'
                       AND last_used_at = ?
                  FROM prv_support_sessions WHERE support_session_id = ?
                """, Boolean.class, providerAdmin, providerAdmin,
                retainedLastUsedAt, sessionId)).isTrue();
    }

    @Test
    void disablingAnAlreadyDisabledActivationControlIsIdempotent() {
        Long providerAdmin = newOperator(990065, "Idempotent activation administrator");
        ProviderSupportSessionRepository.SupportActivationState disabled =
                sessionRepository.disableActivation(
                        providerAdmin, "First containment request", "35".repeat(16));

        ProviderSupportSessionRepository.SupportActivationState repeated =
                sessionRepository.disableActivation(
                        providerAdmin, "Repeated containment request", "36".repeat(16));

        assertThat(repeated.enabled()).isFalse();
        assertThat(repeated.version()).isEqualTo(disabled.version());
        assertThat(jdbc.queryForObject("""
                SELECT change_reason FROM prv_support_activation_control
                 WHERE control_key = 'STANDARD_JIT'
                """, String.class)).isEqualTo("First containment request");
    }

    @Test
    void databaseLedgerAuditFailureRollsBackTheDirectSqlDecision() {
        Long requesterId = newOperator(990059, "Audit failure requester");
        Long reviewerId = newOperator(990060, "Audit failure reviewer");
        UUID requestId = insertPendingRequest(
                requesterId,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.randomUUID());
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION pt_fail_persisted_transition_outbox()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.payload ->> 'action' =
                       'provider.support-access.decision-persisted' THEN
                        RAISE EXCEPTION 'persisted transition outbox fault';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_pt_fail_persisted_transition_outbox
                BEFORE INSERT ON sys_audit_outbox
                FOR EACH ROW EXECUTE FUNCTION pt_fail_persisted_transition_outbox()
                """);
        try {
            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE prv_support_access_requests
                       SET lifecycle_state = 'APPROVED', decided_by = ?,
                           decision_reason = 'Authorized decision with broken outbox',
                           version = version + 1
                     WHERE support_access_request_id = ?
                    """, reviewerId, requestId))
                    .rootCause().hasMessageContaining("persisted transition outbox fault");
        } finally {
            jdbc.execute("""
                    DROP TRIGGER IF EXISTS trg_pt_fail_persisted_transition_outbox
                    ON sys_audit_outbox
                    """);
            jdbc.execute("DROP FUNCTION IF EXISTS pt_fail_persisted_transition_outbox()");
        }

        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state = 'PENDING_APPROVAL'
                       AND decided_at IS NULL AND decided_by IS NULL AND version = 0
                  FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, Boolean.class, requestId)).isTrue();
    }

    @Test
    void containmentOutboxFailureRollsBackOperatorAndEveryOwnedGrantTransition() {
        Long ownerId = newOperator(990160, "Containment outbox failure owner");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID pendingRequestId = insertPendingRequest(ownerId, tenantId, UUID.randomUUID());
        UUID sessionId = UUID.randomUUID();
        UUID activatedRequestId = insertExactSession(
                sessionId, ownerId, tenantId, uniqueTokenHash(),
                "statement_timestamp()", "statement_timestamp() + INTERVAL '30 minutes'",
                "statement_timestamp()");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION pt_fail_containment_outbox()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.payload ->> 'action' =
                       'provider.support-session.revoked-for-operator-state' THEN
                        RAISE EXCEPTION 'automatic containment outbox fault';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_pt_fail_containment_outbox
                BEFORE INSERT ON sys_audit_outbox
                FOR EACH ROW EXECUTE FUNCTION pt_fail_containment_outbox()
                """);
        try {
            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE prv_operators SET lifecycle_state = 'SUSPENDED'
                     WHERE provider_operator_id = ?
                    """, ownerId)).rootCause()
                    .hasMessageContaining("automatic containment outbox fault");
        } finally {
            jdbc.execute("""
                    DROP TRIGGER IF EXISTS trg_pt_fail_containment_outbox
                    ON sys_audit_outbox
                    """);
            jdbc.execute("DROP FUNCTION IF EXISTS pt_fail_containment_outbox()");
        }

        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_operators
                 WHERE provider_operator_id = ?
                """, String.class, ownerId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, String.class, pendingRequestId)).isEqualTo("PENDING_APPROVAL");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, String.class, activatedRequestId)).isEqualTo("ACTIVATED");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_support_sessions
                 WHERE support_session_id = ?
                """, String.class, sessionId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_audit_events
                 WHERE action IN (
                    'provider.support-access.cancelled-for-operator-state',
                    'provider.support-session.revoked-for-operator-state')
                   AND target_id IN (?, ?)
                """, Integer.class, pendingRequestId.toString(), sessionId.toString())).isZero();
    }

    @Test
    void concurrentActivationRaceCreatesExactlyOneDatabaseDerivedGrant() throws Exception {
        Long operatorId = newOperator(990055, "Concurrent activation operator");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID authSessionId = UUID.randomUUID();
        UUID firstRequestId = insertApprovedRequest(operatorId, tenantId, authSessionId);
        UUID secondRequestId = insertApprovedRequest(operatorId, tenantId, authSessionId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Optional<ProviderSupportSessionRepository.ActivatedSupportSession>>
                    first = () -> {
                        ready.countDown();
                        start.await();
                        try {
                            return sessionRepository.activateApprovedRequest(
                                    firstRequestId, 1, operatorId, authSessionId, uniqueTokenHash());
                        } catch (com.dwp.core.exception.BaseException exception) {
                            assertThat(exception.getErrorCode())
                                    .isEqualTo(com.dwp.core.common.ErrorCode.RESOURCE_CONFLICT);
                            return Optional.empty();
                        }
                    };
            java.util.concurrent.Callable<Optional<ProviderSupportSessionRepository.ActivatedSupportSession>>
                    second = () -> {
                        ready.countDown();
                        start.await();
                        try {
                            return sessionRepository.activateApprovedRequest(
                                    secondRequestId, 1, operatorId, authSessionId, uniqueTokenHash());
                        } catch (com.dwp.core.exception.BaseException exception) {
                            assertThat(exception.getErrorCode())
                                    .isEqualTo(com.dwp.core.common.ErrorCode.RESOURCE_CONFLICT);
                            return Optional.empty();
                        }
                    };
            Future<Optional<ProviderSupportSessionRepository.ActivatedSupportSession>> firstResult =
                    executor.submit(first);
            Future<Optional<ProviderSupportSessionRepository.ActivatedSupportSession>> secondResult =
                    executor.submit(second);
            ready.await();
            start.countDown();

            assertThat(java.util.stream.Stream.of(
                            firstResult.get(10, java.util.concurrent.TimeUnit.SECONDS),
                            secondResult.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .filter(Optional::isPresent).count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_support_sessions
                 WHERE provider_operator_id = ? AND lifecycle_state = 'ACTIVE'
                """, Integer.class, operatorId)).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT lifecycle_state FROM prv_support_access_requests
                 WHERE support_access_request_id IN (?, ?)
                 ORDER BY lifecycle_state
                """, String.class, firstRequestId, secondRequestId))
                .containsExactly("ACTIVATED", "APPROVED");
        assertThat(jdbc.queryForObject("""
                SELECT expires_at = started_at + make_interval(mins => request.duration_minutes)
                  FROM prv_support_sessions session
                  JOIN prv_support_access_requests request
                    ON request.support_access_request_id = session.support_access_request_id
                 WHERE session.provider_operator_id = ?
                   AND session.lifecycle_state = 'ACTIVE'
                """, Boolean.class, operatorId)).isTrue();
    }

    @Test
    void activationAndOperatorSuspensionRaceLeavesNoExecutableGrantOrDeadlock()
            throws Exception {
        Long operatorId = newOperator(990155, "Activation suspension race operator");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID authSessionId = UUID.randomUUID();
        UUID requestId = insertApprovedRequest(operatorId, tenantId, authSessionId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> activation = executor.submit(() -> {
                ready.countDown();
                start.await();
                sessionRepository.activateApprovedRequest(
                        requestId, 1, operatorId, authSessionId, uniqueTokenHash());
                return null;
            });
            Future<?> suspension = executor.submit(() -> {
                ready.countDown();
                start.await();
                jdbc.update("""
                        UPDATE prv_operators SET lifecycle_state = 'SUSPENDED'
                         WHERE provider_operator_id = ?
                        """, operatorId);
                return null;
            });
            ready.await();
            start.countDown();
            activation.get(10, java.util.concurrent.TimeUnit.SECONDS);
            suspension.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_support_sessions
                 WHERE provider_operator_id = ? AND lifecycle_state = 'ACTIVE'
                """, Integer.class, operatorId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, String.class, requestId)).isIn("CANCELLED", "COMPLETED");
    }

    @Test
    void requestCreationAndOperatorSuspensionSerializeWithoutLeavingAPoisonRequest()
            throws Exception {
        Long ownerId = newOperator(990157, "Request creation suspension owner");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID requestId = UUID.randomUUID();
        CountDownLatch requestInserted = new CountDownLatch(1);
        CountDownLatch allowRequestCommit = new CountDownLatch(1);
        CountDownLatch suspensionConnectionReady = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger suspensionBackendPid =
                new java.util.concurrent.atomic.AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> creation = executor.submit(() -> {
                transactions.executeWithoutResult(ignored -> {
                    jdbc.update("""
                            INSERT INTO prv_support_access_requests (
                                support_access_request_id, provider_tenant_id,
                                requester_operator_id, requester_auth_session_id,
                                lifecycle_state, justification, duration_minutes,
                                approval_reference, customer_approval_required, risk_tier,
                                request_key, request_fingerprint, decision_due_at,
                                created_by, updated_by)
                            VALUES (?, ?, ?, ?, 'PENDING_APPROVAL',
                                    'Concurrent request creation containment test', 30,
                                    'CUSTOMER-APPROVAL-TEST', TRUE, 'L1', ?, ?,
                                    statement_timestamp() + INTERVAL '1 hour', ?, ?)
                            """, requestId, tenantId, ownerId, UUID.randomUUID(),
                            "request-" + requestId, "9".repeat(64), ownerId, ownerId);
                    jdbc.update("""
                            INSERT INTO prv_support_access_request_scopes (
                                support_access_request_id, scope_code)
                            VALUES (?, 'TENANT_EXPERIENCE_PREVIEW')
                            """, requestId);
                    requestInserted.countDown();
                    awaitLatch(allowRequestCommit);
                });
                return null;
            });
            assertThat(requestInserted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            Future<?> suspension = executor.submit(() -> {
                transactions.executeWithoutResult(ignored -> {
                    suspensionBackendPid.set(jdbc.queryForObject(
                            "SELECT pg_backend_pid()", Integer.class));
                    suspensionConnectionReady.countDown();
                    jdbc.update("""
                            UPDATE prv_operators SET lifecycle_state = 'SUSPENDED'
                             WHERE provider_operator_id = ?
                            """, ownerId);
                });
                return null;
            });
            assertThat(suspensionConnectionReady.await(
                    10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            awaitDatabaseLockWait(suspensionBackendPid.get());
            allowRequestCommit.countDown();
            creation.get(10, java.util.concurrent.TimeUnit.SECONDS);
            suspension.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            allowRequestCommit.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForMap("""
                SELECT lifecycle_state, cancellation_origin
                  FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, requestId))
                .containsEntry("lifecycle_state", "CANCELLED")
                .containsEntry("cancellation_origin", "AUTOMATIC_OPERATOR_CONTAINMENT");
    }

    @Test
    void requestCreationAndScopeRetirementSerializeWithoutRevivingARetiredGrant()
            throws Exception {
        Long ownerId = newOperator(990161, "Request creation scope retirement owner");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID requestId = UUID.randomUUID();
        CountDownLatch requestInserted = new CountDownLatch(1);
        CountDownLatch allowRequestCommit = new CountDownLatch(1);
        CountDownLatch retirementConnectionReady = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger retirementBackendPid =
                new java.util.concurrent.atomic.AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> creation = executor.submit(() -> {
                transactions.executeWithoutResult(ignored -> {
                    jdbc.update("""
                            INSERT INTO prv_support_access_requests (
                                support_access_request_id, provider_tenant_id,
                                requester_operator_id, requester_auth_session_id,
                                lifecycle_state, justification, duration_minutes,
                                approval_reference, customer_approval_required, risk_tier,
                                request_key, request_fingerprint, decision_due_at,
                                created_by, updated_by)
                            VALUES (?, ?, ?, ?, 'PENDING_APPROVAL',
                                    'Concurrent scope retirement request test', 30,
                                    'CUSTOMER-APPROVAL-TEST', TRUE, 'L1', ?, ?,
                                    statement_timestamp() + INTERVAL '1 hour', ?, ?)
                            """, requestId, tenantId, ownerId, UUID.randomUUID(),
                            "request-" + requestId, "9".repeat(64), ownerId, ownerId);
                    jdbc.update("""
                            INSERT INTO prv_support_access_request_scopes (
                                support_access_request_id, scope_code)
                            VALUES (?, 'TENANT_EXPERIENCE_PREVIEW')
                            """, requestId);
                    requestInserted.countDown();
                    awaitLatch(allowRequestCommit);
                });
                return null;
            });
            assertThat(requestInserted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            Future<?> retirement = executor.submit(() -> {
                transactions.executeWithoutResult(ignored -> {
                    retirementBackendPid.set(jdbc.queryForObject(
                            "SELECT pg_backend_pid()", Integer.class));
                    retirementConnectionReady.countDown();
                    jdbc.update("""
                            UPDATE prv_support_scope_catalog SET lifecycle_state = 'RETIRED'
                             WHERE scope_code = 'TENANT_EXPERIENCE_PREVIEW'
                            """);
                });
                return null;
            });
            assertThat(retirementConnectionReady.await(
                    10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            awaitDatabaseLockWait(retirementBackendPid.get());
            allowRequestCommit.countDown();
            creation.get(10, java.util.concurrent.TimeUnit.SECONDS);
            retirement.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            allowRequestCommit.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForMap("""
                SELECT lifecycle_state, cancellation_origin
                  FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, requestId))
                .containsEntry("lifecycle_state", "CANCELLED")
                .containsEntry("cancellation_origin", "AUTOMATIC_SCOPE_RETIREMENT");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_support_scope_catalog
                 WHERE scope_code = 'TENANT_EXPERIENCE_PREVIEW'
                """, String.class)).isEqualTo("RETIRED");
        jdbc.update("""
                UPDATE prv_support_scope_catalog SET lifecycle_state = 'ACTIVE'
                 WHERE scope_code = 'TENANT_EXPERIENCE_PREVIEW'
                """);
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, String.class, requestId)).isEqualTo("CANCELLED");
    }

    @Test
    void directSessionCreationAndOperatorSuspensionUseOneLockOrderWithoutDeadlock()
            throws Exception {
        Long ownerId = newOperator(990158, "Session creation suspension owner");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID authSessionId = UUID.randomUUID();
        UUID requestId = insertApprovedRequest(ownerId, tenantId, authSessionId);
        UUID sessionId = UUID.randomUUID();
        CountDownLatch sessionInserted = new CountDownLatch(1);
        CountDownLatch allowSessionCommit = new CountDownLatch(1);
        CountDownLatch suspensionConnectionReady = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger suspensionBackendPid =
                new java.util.concurrent.atomic.AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> creation = executor.submit(() -> {
                transactions.executeWithoutResult(ignored -> {
                    jdbc.update("""
                            WITH session_row AS (
                                INSERT INTO prv_support_sessions (
                                    support_session_id, support_access_request_id,
                                    lifecycle_state, justification, token_hash, expires_at,
                                    access_mode, approval_reference,
                                    customer_approval_required, risk_tier,
                                    origin_auth_session_id)
                                VALUES (?, ?, 'ACTIVE', 'Concurrent direct session creation', ?,
                                        statement_timestamp() + INTERVAL '30 minutes',
                                        'STANDARD', 'CUSTOMER-APPROVAL-TEST', TRUE, 'L1', ?)
                                RETURNING support_session_id
                            ), session_scope AS (
                                INSERT INTO prv_support_session_scopes (
                                    support_session_id, scope_code)
                                SELECT support_session_id, 'TENANT_EXPERIENCE_PREVIEW'
                                  FROM session_row
                                RETURNING support_session_id
                            )
                            UPDATE prv_support_access_requests
                               SET lifecycle_state = 'ACTIVATED', version = version + 1
                             WHERE support_access_request_id = ?
                               AND EXISTS (SELECT 1 FROM session_scope)
                            """, sessionId, requestId, uniqueTokenHash(), authSessionId, requestId);
                    sessionInserted.countDown();
                    awaitLatch(allowSessionCommit);
                });
                return null;
            });
            assertThat(sessionInserted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            Future<?> suspension = executor.submit(() -> {
                transactions.executeWithoutResult(ignored -> {
                    suspensionBackendPid.set(jdbc.queryForObject(
                            "SELECT pg_backend_pid()", Integer.class));
                    suspensionConnectionReady.countDown();
                    jdbc.update("""
                            UPDATE prv_operators SET lifecycle_state = 'SUSPENDED'
                             WHERE provider_operator_id = ?
                            """, ownerId);
                });
                return null;
            });
            assertThat(suspensionConnectionReady.await(
                    10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            awaitDatabaseLockWait(suspensionBackendPid.get());
            allowSessionCommit.countDown();
            creation.get(10, java.util.concurrent.TimeUnit.SECONDS);
            suspension.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            allowSessionCommit.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForMap("""
                SELECT lifecycle_state, revocation_origin
                  FROM prv_support_sessions WHERE support_session_id = ?
                """, sessionId))
                .containsEntry("lifecycle_state", "REVOKED")
                .containsEntry("revocation_origin", "AUTOMATIC_OPERATOR_CONTAINMENT");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, String.class, requestId)).isEqualTo("COMPLETED");
    }

    @Test
    void activationAndScopeRetirementRaceLeavesNoExecutableGrantOrDeadlock()
            throws Exception {
        Long operatorId = newOperator(990156, "Activation retirement race operator");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID authSessionId = UUID.randomUUID();
        UUID requestId = insertApprovedRequest(operatorId, tenantId, authSessionId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> activation = executor.submit(() -> {
                ready.countDown();
                start.await();
                sessionRepository.activateApprovedRequest(
                        requestId, 1, operatorId, authSessionId, uniqueTokenHash());
                return null;
            });
            Future<?> retirement = executor.submit(() -> {
                ready.countDown();
                start.await();
                jdbc.update("""
                        UPDATE prv_support_scope_catalog SET lifecycle_state = 'RETIRED'
                         WHERE scope_code = 'TENANT_EXPERIENCE_PREVIEW'
                        """);
                return null;
            });
            ready.await();
            start.countDown();
            activation.get(10, java.util.concurrent.TimeUnit.SECONDS);
            retirement.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            jdbc.update("""
                    UPDATE prv_support_scope_catalog SET lifecycle_state = 'ACTIVE'
                     WHERE scope_code = 'TENANT_EXPERIENCE_PREVIEW'
                    """);
        }
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_support_sessions session
                  JOIN prv_support_session_scopes scope
                    ON scope.support_session_id = session.support_session_id
                 WHERE scope.scope_code = 'TENANT_EXPERIENCE_PREVIEW'
                   AND session.lifecycle_state = 'ACTIVE'
                   AND session.provider_operator_id = ?
                """, Integer.class, operatorId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, String.class, requestId)).isIn("CANCELLED", "COMPLETED");
    }

    @Test
    void activationAndKillSwitchRaceTerminatesWithNoExecutableGrantOrDeadlock()
            throws Exception {
        Long operatorId = newOperator(990061, "Activation kill switch race operator");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID authSessionId = UUID.randomUUID();
        UUID requestId = insertApprovedRequest(operatorId, tenantId, authSessionId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> activation = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    sessionRepository.activateApprovedRequest(
                            requestId, 1, operatorId, authSessionId, "7".repeat(64));
                } catch (com.dwp.core.exception.BaseException exception) {
                    assertThat(exception.getErrorCode()).isIn(
                            com.dwp.core.common.ErrorCode.RESOURCE_CONFLICT,
                            com.dwp.core.common.ErrorCode.INVALID_STATE);
                }
                return null;
            });
            Future<?> disable = executor.submit(() -> {
                ready.countDown();
                start.await();
                sessionRepository.disableActivation(
                        operatorId, "Concurrent containment", "33".repeat(16));
                return null;
            });
            ready.await();
            start.countDown();
            activation.get(10, java.util.concurrent.TimeUnit.SECONDS);
            disable.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(sessionRepository.activationState().enabled()).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_support_sessions
                 WHERE provider_operator_id = ? AND lifecycle_state = 'ACTIVE'
                """, Integer.class, operatorId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, String.class, requestId)).isIn("APPROVED", "COMPLETED");
    }

    @Test
    void expiryAndTenantContainmentSerializeBulkLedgerMutationWithoutDeadlock()
            throws Exception {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Long elapsedOwnerId = newOperator(990162, "Elapsed containment race owner");
        Long activeOwnerId = newOperator(990163, "Active containment race owner");
        UUID elapsedSessionId = UUID.randomUUID();
        UUID activeSessionId = UUID.randomUUID();
        UUID elapsedRequestId = insertExactSession(
                elapsedSessionId, elapsedOwnerId, tenantId, uniqueTokenHash(),
                "statement_timestamp() - INTERVAL '2 hours'",
                "statement_timestamp() - INTERVAL '1 hour'",
                "statement_timestamp() - INTERVAL '2 hours'");
        UUID activeRequestId = insertExactSession(
                activeSessionId, activeOwnerId, tenantId, uniqueTokenHash(),
                "statement_timestamp()",
                "statement_timestamp() + INTERVAL '30 minutes'",
                "statement_timestamp()");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> expiry = executor.submit(() -> {
                ready.countDown();
                awaitLatch(start);
                transactions.executeWithoutResult(
                        ignored -> lifecycleService.expireElapsedSessions());
                return null;
            });
            Future<?> containment = executor.submit(() -> {
                ready.countDown();
                awaitLatch(start);
                transactions.executeWithoutResult(ignored -> jdbc.update("""
                        UPDATE prv_tenants SET onboarding_state = 'FAILED'
                         WHERE provider_tenant_id = ?
                        """, tenantId));
                return null;
            });
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            expiry.get(10, java.util.concurrent.TimeUnit.SECONDS);
            containment.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_support_sessions
                 WHERE support_session_id IN (?, ?) AND lifecycle_state = 'ACTIVE'
                """, Integer.class, elapsedSessionId, activeSessionId)).isZero();
        assertThat(jdbc.queryForList("""
                SELECT lifecycle_state FROM prv_support_sessions
                 WHERE support_session_id IN (?, ?) ORDER BY support_session_id
                """, String.class, elapsedSessionId, activeSessionId))
                .allMatch(state -> state.equals("EXPIRED") || state.equals("REVOKED"));
        assertThat(jdbc.queryForList("""
                SELECT lifecycle_state FROM prv_support_access_requests
                 WHERE support_access_request_id IN (?, ?) ORDER BY support_access_request_id
                """, String.class, elapsedRequestId, activeRequestId))
                .containsOnly("COMPLETED");
        assertThat(jdbc.queryForObject("""
                SELECT onboarding_state FROM prv_tenants WHERE provider_tenant_id = ?
                """, String.class, tenantId)).isEqualTo("FAILED");
        jdbc.update("""
                UPDATE prv_tenants SET onboarding_state = 'READY'
                 WHERE provider_tenant_id = ?
                """, tenantId);
    }

    @Test
    void uniqueIndexRejectsTwoConcurrentActiveTargetsForOneOperator() {
        Long operatorId = operatorId();
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        insertActive(operatorId, tenantId, "a".repeat(64));

        assertThatThrownBy(() -> sessionRepository.requireNoActiveSupportSession(operatorId))
                .hasMessageContaining("current support session");

        assertThatThrownBy(() -> insertActive(operatorId, tenantId, "b".repeat(64)))
                .rootCause()
                .hasMessageContaining(
                        "duplicate key value violates unique constraint \"uk_prv_support_sessions_one_active_operator\"");
    }

    @Test
    void lifecycleExpiresStaleRowsBeforeCheckingTheInvariant() {
        Long operatorId = jdbc.queryForObject("""
                INSERT INTO prv_operators (
                    auth_tenant_id, auth_user_id, display_name, role_code, lifecycle_state)
                VALUES (1, 990038, 'Expiry test operator', 'PROVIDER_SUPPORT', 'ACTIVE')
                RETURNING provider_operator_id
                """, Long.class);
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID sessionId = UUID.randomUUID();
        insertExactSession(sessionId, operatorId, tenantId, "c".repeat(64),
                "CURRENT_TIMESTAMP - INTERVAL '2 hours'",
                "CURRENT_TIMESTAMP - INTERVAL '1 hour'",
                "CURRENT_TIMESTAMP - INTERVAL '2 hours'");

        lifecycleService.expireElapsedSessions();
        sessionRepository.requireNoActiveSupportSession(operatorId);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_support_sessions
                 WHERE provider_operator_id = ? AND lifecycle_state = 'EXPIRED'
                """, Integer.class, operatorId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_audit_events
                 WHERE action = 'provider.support-session.expired-automatically'
                   AND target_id = ?
                """, Integer.class, sessionId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT payload ->> 'retentionClass'
                  FROM sys_audit_outbox outbox
                  JOIN prv_audit_events event ON event.audit_event_id = outbox.event_id
                 WHERE event.action = 'provider.support-session.expired-automatically'
                   AND event.target_id = ?
                """, String.class, sessionId.toString())).isEqualTo("EXTENDED");
    }

    @Test
    void acceptsGovernedProviderCategoriesAndKeepsAuditRowsAppendOnly() {
        Long operatorId = operatorId();
        Long actorId = jdbc.queryForObject("""
                SELECT auth_user_id FROM prv_operators WHERE provider_operator_id = ?
                """, Long.class, operatorId);
        UUID featureEvent = UUID.randomUUID();
        UUID commercialEvent = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prv_audit_events (
                    audit_event_id, actor_id, action, target_type, target_id,
                    outcome, redacted_snapshot, provider_operator_id, event_category)
                VALUES (?, ?, 'provider.feature-rollout.test', 'FEATURE', 'test-feature',
                        'SUCCESS', '{}'::jsonb, ?, 'FEATURE_ROLLOUT'),
                       (?, ?, 'provider.subscription-renewal.test', 'SUBSCRIPTION', 'test-subscription',
                        'SUCCESS', '{}'::jsonb, ?, 'COMMERCIAL_GOVERNANCE')
                """, featureEvent, actorId, operatorId,
                commercialEvent, actorId, operatorId);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_audit_events
                 WHERE audit_event_id IN (?, ?)
                """, Integer.class, featureEvent, commercialEvent)).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE prv_audit_events SET outcome = 'FAILED' WHERE audit_event_id = ?
                """, featureEvent))
                .rootCause()
                .hasMessageContaining("append-only");
    }

    @Test
    void deniedAuditCommitsEvenWhenTheRejectedBusinessTransactionRollsBack() {
        String targetId = UUID.randomUUID().toString();
        Long operatorId = operatorId();
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.register(TransactionTestConfiguration.class);
            context.registerBean(DataSource.class, () -> dataSource);
            context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
            context.registerBean(ObjectMapper.class,
                    () -> new ObjectMapper().findAndRegisterModules());
            context.registerBean(PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(dataSource));
            context.registerBean(ProviderAuditService.class);
            context.refresh();

            ProviderRequestContext.setForTest(operatorId, 1L);
            TransactionTemplate outer =
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            outer.executeWithoutResult(status -> {
                context.getBean(ProviderAuditService.class).denied(
                        "provider.support-session.use-denied",
                        "SUPPORT_SESSION",
                        targetId,
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        null,
                        "test:" + targetId,
                        Map.of("reason", "SCOPE_DENIED"));
                status.setRollbackOnly();
            });
        } finally {
            ProviderRequestContext.clear();
        }

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_audit_events
                 WHERE action = 'provider.support-session.use-denied'
                   AND target_id = ?
                   AND outcome = 'DENIED'
                """, Integer.class, targetId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT correlation_id
                  FROM prv_audit_events
                 WHERE action = 'provider.support-session.use-denied'
                   AND target_id = ?
                """, String.class, targetId))
                .startsWith("sha256:")
                .doesNotContain(targetId);
    }

    @Test
    void retiringAnActiveScopeImmediatelyRevokesExistingSessions() {
        Long operatorId = jdbc.queryForObject("""
                INSERT INTO prv_operators (
                    auth_tenant_id, auth_user_id, display_name, role_code, lifecycle_state)
                VALUES (1, 990043, 'Scope retirement operator', 'PROVIDER_SUPPORT', 'ACTIVE')
                RETURNING provider_operator_id
                """, Long.class);
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID sessionId = UUID.randomUUID();
        insertExactSession(
                sessionId, operatorId, tenantId,
                UUID.randomUUID().toString().replace("-", "").repeat(2),
                "CURRENT_TIMESTAMP", "CURRENT_TIMESTAMP + INTERVAL '15 minutes'",
                "CURRENT_TIMESTAMP");

        try {
            jdbc.update("""
                    UPDATE prv_support_scope_catalog
                       SET lifecycle_state = 'RETIRED'
                     WHERE scope_code = 'TENANT_EXPERIENCE_PREVIEW'
                    """);

            assertThat(jdbc.queryForObject("""
                    SELECT lifecycle_state FROM prv_support_sessions
                     WHERE support_session_id = ?
                    """, String.class, sessionId)).isEqualTo("REVOKED");
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM prv_audit_events
                     WHERE action = 'provider.support-session.revoked-by-policy'
                       AND target_id = ?
                    """, Integer.class, sessionId.toString())).isEqualTo(1);
        } finally {
            jdbc.update("""
                    UPDATE prv_support_scope_catalog
                       SET lifecycle_state = 'ACTIVE'
                     WHERE scope_code = 'TENANT_EXPERIENCE_PREVIEW'
                    """);
        }
    }

    @Test
    void scopeRetirementContainsRequestsAndSessionsOwnedByAnInactiveOperator() {
        Long ownerId = newOperator(990143, "Inactive scope owner");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID pendingRequestId = insertPendingRequest(ownerId, tenantId, UUID.randomUUID());
        UUID sessionId = UUID.randomUUID();
        insertExactSession(sessionId, ownerId, tenantId, uniqueTokenHash(),
                "statement_timestamp()", "statement_timestamp() + INTERVAL '30 minutes'",
                "statement_timestamp()");
        suspendWithoutAutomaticContainment(ownerId);

        try {
            jdbc.update("""
                    UPDATE prv_support_scope_catalog SET lifecycle_state = 'RETIRED'
                     WHERE scope_code = 'TENANT_EXPERIENCE_PREVIEW'
                    """);

            assertThat(jdbc.queryForMap("""
                    SELECT lifecycle_state, cancellation_origin
                      FROM prv_support_access_requests
                     WHERE support_access_request_id = ?
                    """, pendingRequestId))
                    .containsEntry("lifecycle_state", "CANCELLED")
                    .containsEntry("cancellation_origin", "AUTOMATIC_SCOPE_RETIREMENT");
            assertThat(jdbc.queryForMap("""
                    SELECT lifecycle_state, revocation_origin
                      FROM prv_support_sessions WHERE support_session_id = ?
                    """, sessionId))
                    .containsEntry("lifecycle_state", "REVOKED")
                    .containsEntry("revocation_origin", "AUTOMATIC_SCOPE_RETIREMENT");
        } finally {
            jdbc.update("""
                    UPDATE prv_support_scope_catalog SET lifecycle_state = 'ACTIVE'
                     WHERE scope_code = 'TENANT_EXPERIENCE_PREVIEW'
                    """);
        }
    }

    @Test
    void tenantContainmentRevokesAnInactiveOwnersSessionWithoutBlockingTenantState() {
        Long ownerId = newOperator(990144, "Inactive tenant owner");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID sessionId = UUID.randomUUID();
        insertExactSession(sessionId, ownerId, tenantId, uniqueTokenHash(),
                "statement_timestamp()", "statement_timestamp() + INTERVAL '30 minutes'",
                "statement_timestamp()");
        suspendWithoutAutomaticContainment(ownerId);

        try {
            jdbc.update("""
                    UPDATE prv_tenants SET onboarding_state = 'FAILED'
                     WHERE provider_tenant_id = ?
                    """, tenantId);

            assertThat(jdbc.queryForObject("""
                    SELECT onboarding_state FROM prv_tenants WHERE provider_tenant_id = ?
                    """, String.class, tenantId)).isEqualTo("FAILED");
            assertThat(jdbc.queryForMap("""
                    SELECT lifecycle_state, revocation_origin
                      FROM prv_support_sessions WHERE support_session_id = ?
                    """, sessionId))
                    .containsEntry("lifecycle_state", "REVOKED")
                    .containsEntry("revocation_origin", "AUTOMATIC_TENANT_CONTAINMENT");
        } finally {
            jdbc.update("""
                    UPDATE prv_tenants SET onboarding_state = 'READY'
                     WHERE provider_tenant_id = ?
                    """, tenantId);
        }
    }

    @Test
    void operatorSuspensionAtomicallyContainsOwnedRequestsAndSessions() {
        Long ownerId = newOperator(990145, "Operator containment owner");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID pendingRequestId = insertPendingRequest(ownerId, tenantId, UUID.randomUUID());
        UUID sessionId = UUID.randomUUID();
        insertExactSession(sessionId, ownerId, tenantId, uniqueTokenHash(),
                "statement_timestamp()", "statement_timestamp() + INTERVAL '30 minutes'",
                "statement_timestamp()");

        jdbc.update("""
                UPDATE prv_operators SET lifecycle_state = 'SUSPENDED'
                 WHERE provider_operator_id = ?
                """, ownerId);

        assertThat(jdbc.queryForMap("""
                SELECT lifecycle_state, cancellation_origin
                  FROM prv_support_access_requests WHERE support_access_request_id = ?
                """, pendingRequestId))
                .containsEntry("lifecycle_state", "CANCELLED")
                .containsEntry("cancellation_origin", "AUTOMATIC_OPERATOR_CONTAINMENT");
        assertThat(jdbc.queryForMap("""
                SELECT lifecycle_state, revocation_origin
                  FROM prv_support_sessions WHERE support_session_id = ?
                """, sessionId))
                .containsEntry("lifecycle_state", "REVOKED")
                .containsEntry("revocation_origin", "AUTOMATIC_OPERATOR_CONTAINMENT");
        assertThat(jdbc.queryForObject("""
                SELECT outbox.payload ->> 'actorType' = 'SYSTEM'
                       AND outbox.payload -> 'actorRoles' = '[]'::jsonb
                       AND outbox.payload #>> '{metadata,providerActorKind}' =
                           'SYSTEM_CONTAINMENT'
                       AND outbox.payload #>> '{metadata,transitionOrigin}' =
                           'AUTOMATIC_OPERATOR_CONTAINMENT'
                  FROM prv_audit_events event
                  JOIN sys_audit_outbox outbox ON outbox.event_id = event.audit_event_id
                 WHERE event.action = 'provider.support-session.revoked-for-operator-state'
                   AND event.target_id = ?
                """, Boolean.class, sessionId.toString())).isTrue();
    }

    @Test
    void directSqlCannotBorrowTheSystemContainmentActor() {
        Long ownerId = newOperator(990146, "System actor spoof owner");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID requestId = insertPendingRequest(ownerId, tenantId, UUID.randomUUID());
        UUID sessionId = UUID.randomUUID();
        insertExactSession(sessionId, ownerId, tenantId, uniqueTokenHash(),
                "statement_timestamp()", "statement_timestamp() + INTERVAL '30 minutes'",
                "statement_timestamp()");
        Long systemActorId = jdbc.queryForObject("""
                SELECT provider_operator_id FROM prv_operators
                 WHERE auth_tenant_id = -1 AND auth_user_id = -5100001
                """, Long.class);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE prv_support_access_requests
                   SET lifecycle_state = 'CANCELLED', cancelled_at = statement_timestamp(),
                       cancelled_by = ?, cancellation_reason = 'Spoofed containment',
                       updated_by = ?, version = version + 1
                 WHERE support_access_request_id = ?
                """, systemActorId, systemActorId, requestId)).rootCause()
                .hasMessageContaining("system containment actor requires request provenance");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE prv_support_sessions
                   SET lifecycle_state = 'REVOKED', revoked_at = statement_timestamp(),
                       revoked_by = ?, updated_by = ?, version = version + 1
                 WHERE support_session_id = ?
                """, systemActorId, systemActorId, sessionId)).rootCause()
                .hasMessageContaining("system containment actor requires session provenance");
    }

    @Test
    void containmentIdentityHasNoInteractiveAuthorityAndCannotBeGrantedAny() {
        Long systemActorId = jdbc.queryForObject("""
                SELECT provider_operator_id FROM prv_operators
                 WHERE auth_tenant_id = -1 AND auth_user_id = -5100001
                """, Long.class);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_operator_role_assignments
                 WHERE provider_operator_id = ?
                """, Integer.class, systemActorId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_operator_role_permissions
                 WHERE role_code = 'PROVIDER_SYSTEM_CONTAINMENT'
                """, Integer.class)).isZero();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO prv_operator_role_assignments (
                    provider_operator_id, role_code, lifecycle_state, created_by)
                VALUES (?, 'PROVIDER_ADMIN', 'ACTIVE', ?)
                """, systemActorId, systemActorId)).rootCause()
                .hasMessageContaining("cannot receive role assignments");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO prv_operator_role_permissions (role_code, permission_code)
                VALUES ('PROVIDER_SYSTEM_CONTAINMENT', 'SUPPORT_ACCESS_REVIEW')
                """)).rootCause().hasMessageContaining("cannot receive permissions");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO prv_operators (
                    auth_tenant_id, auth_user_id, display_name, role_code, lifecycle_state)
                VALUES (1, 990199, 'Human system-role squat',
                        'PROVIDER_SYSTEM_CONTAINMENT', 'ACTIVE')
                """)).rootCause().hasMessageContaining("system identity is immutable");
        Long humanOperatorId = newOperator(990198, "Human reserved-role mutation");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE prv_operators
                   SET role_code = 'PROVIDER_SYSTEM_CONTAINMENT'
                 WHERE provider_operator_id = ?
                """, humanOperatorId)).rootCause()
                .hasMessageContaining("system identity is immutable");
    }

    @Test
    void topLevelSqlCannotForgeAutomaticContainmentWithThePrivateOriginSetting() {
        Long ownerId = newOperator(990148, "System provenance spoof owner");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID requestId = insertPendingRequest(ownerId, tenantId, UUID.randomUUID());
        UUID sessionId = UUID.randomUUID();
        insertExactSession(sessionId, ownerId, tenantId, uniqueTokenHash(),
                "statement_timestamp()", "statement_timestamp() + INTERVAL '30 minutes'",
                "statement_timestamp()");
        suspendWithoutAutomaticContainment(ownerId);
        Long systemActorId = jdbc.queryForObject("""
                SELECT provider_operator_id FROM prv_operators
                 WHERE auth_tenant_id = -1 AND auth_user_id = -5100001
                """, Long.class);

        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            jdbc.execute("SELECT set_config('dwp.support_containment_origin', "
                    + "'operator-unavailable', TRUE)");
            jdbc.update("""
                    UPDATE prv_support_access_requests
                       SET lifecycle_state = 'CANCELLED',
                           cancelled_at = statement_timestamp(), cancelled_by = ?,
                           cancellation_reason = 'Support requester became unavailable',
                           cancellation_origin = 'AUTOMATIC_OPERATOR_CONTAINMENT',
                           updated_by = ?, version = version + 1
                     WHERE support_access_request_id = ?
                    """, systemActorId, systemActorId, requestId);
        })).rootCause().hasMessageContaining("support request containment provenance is invalid");

        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            jdbc.execute("SELECT set_config('dwp.support_containment_origin', "
                    + "'operator-unavailable', TRUE)");
            jdbc.update("""
                    UPDATE prv_support_sessions
                       SET lifecycle_state = 'REVOKED',
                           revoked_at = statement_timestamp(), revoked_by = ?,
                           revocation_origin = 'AUTOMATIC_OPERATOR_CONTAINMENT',
                           updated_by = ?, version = version + 1
                     WHERE support_session_id = ?
                    """, systemActorId, systemActorId, sessionId);
        })).rootCause().hasMessageContaining("support session containment provenance is invalid");
    }

    @Test
    void exactSessionProjectionDoesNotDependOnTheNewestTwoHundredLedgerRows() {
        Long ownerId = newOperator(990147, "Exact projection owner");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID targetSessionId = UUID.randomUUID();
        insertExactSession(targetSessionId, ownerId, tenantId, uniqueTokenHash(),
                "statement_timestamp()",
                "statement_timestamp() + INTERVAL '30 minutes'",
                "statement_timestamp()");
        transactions.executeWithoutResult(ignored -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    UPDATE prv_support_sessions
                       SET created_at = statement_timestamp() - INTERVAL '2 days'
                     WHERE support_session_id = ?
                    """, targetSessionId);
            jdbc.update("""
                    INSERT INTO prv_support_sessions (
                        support_session_id, provider_tenant_id, provider_operator_id,
                        support_access_request_id, lifecycle_state, justification,
                        token_hash, started_at, expires_at, last_used_at, access_mode,
                        approval_reference, customer_approval_required, risk_tier,
                        origin_auth_session_id, created_at, updated_at,
                        created_by, updated_by, version)
                    SELECT gen_random_uuid(), ?, ?, gen_random_uuid(), 'EXPIRED',
                           'Historical exact projection fixture',
                           md5('exact-projection-' || series)
                               || md5('exact-projection-second-' || series),
                           statement_timestamp() - INTERVAL '1 day',
                           statement_timestamp() - INTERVAL '23 hours',
                           statement_timestamp() - INTERVAL '23 hours',
                           'STANDARD', 'CUSTOMER-APPROVAL-TEST', TRUE, 'L1',
                           gen_random_uuid(), statement_timestamp(), statement_timestamp(),
                           ?, ?, 1
                      FROM generate_series(1, 201) series
                    """, tenantId, ownerId, ownerId, ownerId);
        });

        assertThat(sessionRepository.sessions(tenantId))
                .extracting(ProviderDtos.SupportSessionSummary::supportSessionId)
                .doesNotContain(targetSessionId);
        assertThat(sessionRepository.summary(targetSessionId).supportSessionId())
                .isEqualTo(targetSessionId);
    }

    @Test
    void activeSupportRowsRequireAnAuthSessionBinding() {
        Long operatorId = jdbc.queryForObject("""
                INSERT INTO prv_operators (
                    auth_tenant_id, auth_user_id, display_name, role_code, lifecycle_state)
                VALUES (1, 990041, 'Binding test operator', 'PROVIDER_SUPPORT', 'ACTIVE')
                RETURNING provider_operator_id
                """, Long.class);
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO prv_support_sessions (
                    support_session_id, provider_tenant_id, provider_operator_id,
                    justification, token_hash, expires_at,
                    access_mode, approval_reference,
                    customer_approval_required, risk_tier)
                VALUES (?, ?, ?, 'Unbound support test', ?,
                        CURRENT_TIMESTAMP + INTERVAL '15 minutes',
                        'STANDARD', 'CUSTOMER-APPROVAL-TEST', TRUE, 'L1')
                """, UUID.randomUUID(), tenantId, operatorId, "d".repeat(64)))
                .rootCause()
                .hasMessageContaining("requires an existing approved request");
    }

    @Test
    void automaticRequestExpiryAndCompletionProduceRetainedAuditEvidence() {
        Long operatorId = operatorId();
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID expiryRequestId = UUID.randomUUID();
        insertElapsedPendingRequest(expiryRequestId, operatorId, tenantId);
        lifecycleService.expireElapsedSessions();

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_audit_events
                 WHERE action = 'provider.support-access.expired-automatically'
                   AND target_id = ?
                   AND redacted_snapshot ->> 'fromState' = 'PENDING_APPROVAL'
                   AND redacted_snapshot ->> 'toState' = 'EXPIRED'
                """, Integer.class, expiryRequestId.toString())).isEqualTo(1);

        UUID sessionId = UUID.randomUUID();
        UUID completionRequestId = insertExactSession(
                sessionId, operatorId, tenantId, "1".repeat(64),
                "CURRENT_TIMESTAMP", "CURRENT_TIMESTAMP + INTERVAL '15 minutes'",
                "CURRENT_TIMESTAMP");
        assertThat(sessionRepository.revoke(sessionId, operatorId, 0)).isTrue();

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_audit_events
                 WHERE action = 'provider.support-access.completed-after-session-end'
                   AND target_id = ?
                   AND redacted_snapshot ->> 'supportSessionId' = ?
                """, Integer.class, completionRequestId.toString(), sessionId.toString()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT payload ->> 'retentionClass'
                  FROM sys_audit_outbox outbox
                  JOIN prv_audit_events event ON event.audit_event_id = outbox.event_id
                 WHERE event.action = 'provider.support-access.completed-after-session-end'
                   AND event.target_id = ?
                """, String.class, completionRequestId.toString())).isEqualTo("EXTENDED");
    }

    @Test
    void supportRequestReconciliationQualifiesColumnsSharedWithSessions() {
        assertThat(repository.byKey(operatorId(), "missing-runtime-verification-key")).isEmpty();
    }

    private static Long operatorId() {
        return jdbc.queryForObject("""
                SELECT provider_operator_id FROM prv_operators
                 WHERE auth_tenant_id = 1 AND auth_user_id = 900001
                """, Long.class);
    }

    private static Long newOperator(long authUserId, String displayName) {
        Long operatorId = jdbc.queryForObject("""
                INSERT INTO prv_operators (
                    auth_tenant_id, auth_user_id, display_name,
                    role_code, lifecycle_state)
                VALUES (1, ?, ?, 'PROVIDER_ADMIN', 'ACTIVE')
                RETURNING provider_operator_id
                """, Long.class, authUserId, displayName);
        jdbc.update("""
                INSERT INTO prv_operator_role_assignments (
                    provider_operator_id, role_code, lifecycle_state, created_by)
                VALUES (?, 'PROVIDER_ADMIN', 'ACTIVE', ?)
                """, operatorId, operatorId);
        return operatorId;
    }

    private static void suspendWithoutAutomaticContainment(Long operatorId) {
        // Deliberately create a poison-row fixture so the independent scope and
        // tenant containment paths remain regression-tested after V53.
        jdbc.execute("""
                ALTER TABLE prv_operators
                DISABLE TRIGGER trg_prv_revoke_support_for_unavailable_operator
                """);
        jdbc.execute("""
                ALTER TABLE prv_operators
                DISABLE TRIGGER trg_prv_reconcile_support_authority_operator
                """);
        try {
            jdbc.update("""
                    UPDATE prv_operators SET lifecycle_state = 'SUSPENDED'
                     WHERE provider_operator_id = ?
                    """, operatorId);
        } finally {
            jdbc.execute("""
                    ALTER TABLE prv_operators
                    ENABLE TRIGGER trg_prv_reconcile_support_authority_operator
                    """);
            jdbc.execute("""
                    ALTER TABLE prv_operators
                    ENABLE TRIGGER trg_prv_revoke_support_for_unavailable_operator
                    """);
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for the concurrent database step");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the concurrent database step",
                    exception);
        }
    }

    private static void awaitDatabaseLockWait(int backendPid) {
        long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbc.query("""
                    SELECT wait_event_type = 'Lock'
                      FROM pg_stat_activity
                     WHERE pid = ?
                    """, resultSet -> resultSet.next() && resultSet.getBoolean(1), backendPid);
            if (Boolean.TRUE.equals(waiting)) return;
            java.util.concurrent.locks.LockSupport.parkNanos(
                    java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("Concurrent database session did not block on the authority row");
    }

    private static String uniqueTokenHash() {
        return UUID.randomUUID().toString().replace("-", "").repeat(2);
    }

    private static void insertActive(Long operatorId, UUID tenantId, String tokenHash) {
        insertExactSession(
                UUID.randomUUID(), operatorId, tenantId, tokenHash,
                "CURRENT_TIMESTAMP", "CURRENT_TIMESTAMP + INTERVAL '30 minutes'",
                "CURRENT_TIMESTAMP");
    }

    private static UUID insertExactSession(
            UUID sessionId,
            Long operatorId,
            UUID tenantId,
            String tokenHash,
            String startedAtExpression,
            String expiresAtExpression,
            String lastUsedAtExpression) {
        return transactions.execute(ignored -> {
            UUID requestId = UUID.randomUUID();
            UUID authSessionId = UUID.randomUUID();
            Long approverId = newOperator(
                    Math.abs(UUID.randomUUID().getLeastSignificantBits() % 800_000_000L)
                            + 100_000_000L,
                    "Fixture independent approver");
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    WITH fixture AS (
                        SELECT %s AS started_at, %s AS expires_at, %s AS last_used_at
                    ), request_row AS (
                        INSERT INTO prv_support_access_requests (
                            support_access_request_id, provider_tenant_id,
                            requester_operator_id, requester_auth_session_id,
                            lifecycle_state, justification, duration_minutes,
                            approval_reference, customer_approval_required, risk_tier,
                            request_key, request_fingerprint, decision_due_at,
                            decided_at, decided_by, decision_reason, activated_at,
                            created_at, updated_at, created_by, updated_by, version)
                        SELECT ?, ?, ?, ?, 'ACTIVATED',
                               'Executable support integration test',
                               ROUND(EXTRACT(EPOCH FROM (expires_at - started_at)) / 60)::integer,
                               'CUSTOMER-APPROVAL-TEST', TRUE, 'L1', ?, ?,
                               started_at + INTERVAL '1 hour',
                               started_at - INTERVAL '1 second', ?,
                               'Independent integration approval', started_at,
                               started_at - INTERVAL '1 minute', started_at,
                               ?, ?, 2
                          FROM fixture
                        RETURNING support_access_request_id
                    ), request_scope AS (
                        INSERT INTO prv_support_access_request_scopes (
                            support_access_request_id, scope_code)
                        SELECT support_access_request_id, 'TENANT_EXPERIENCE_PREVIEW'
                          FROM request_row
                        RETURNING support_access_request_id
                    ), session_row AS (
                        INSERT INTO prv_support_sessions (
                            support_session_id, provider_tenant_id,
                            provider_operator_id, support_access_request_id,
                            lifecycle_state, justification, token_hash,
                            started_at, expires_at, last_used_at, access_mode,
                            approval_reference, customer_approval_required, risk_tier,
                            origin_auth_session_id, created_at, updated_at,
                            created_by, updated_by, version)
                        SELECT ?, ?, ?, request_scope.support_access_request_id,
                               'ACTIVE', 'Executable support integration test', ?,
                               fixture.started_at, fixture.expires_at, fixture.last_used_at,
                               'STANDARD', 'CUSTOMER-APPROVAL-TEST', TRUE, 'L1', ?,
                               fixture.started_at, fixture.started_at, ?, ?, 0
                          FROM request_scope CROSS JOIN fixture
                        RETURNING support_session_id
                    )
                    INSERT INTO prv_support_session_scopes (support_session_id, scope_code)
                    SELECT support_session_id, 'TENANT_EXPERIENCE_PREVIEW' FROM session_row
                    """.formatted(
                    startedAtExpression, expiresAtExpression, lastUsedAtExpression),
                    requestId, tenantId, operatorId, authSessionId,
                    "request-" + requestId, "9".repeat(64), approverId,
                    operatorId, operatorId, sessionId, tenantId, operatorId,
                    tokenHash, authSessionId, operatorId, operatorId);
            return requestId;
        });
    }

    private static UUID insertApprovedRequest(
            Long operatorId,
            UUID tenantId,
            UUID authSessionId) {
        UUID requestId = insertPendingRequest(operatorId, tenantId, authSessionId);
        Long approverId = newOperator(
                Math.abs(UUID.randomUUID().getMostSignificantBits() % 800_000_000L)
                        + 100_000_000L,
                "Direct SQL independent approver");
        transactions.executeWithoutResult(ignored -> {
            jdbc.update("""
                    UPDATE prv_support_access_requests
                       SET lifecycle_state = 'APPROVED', decided_by = ?,
                           decided_at = statement_timestamp(),
                           decision_reason = 'Independent integration approval',
                           updated_at = statement_timestamp(), updated_by = ?,
                           version = version + 1
                     WHERE support_access_request_id = ?
                    """, approverId, approverId, requestId);
        });
        return requestId;
    }

    private static UUID insertPendingRequest(
            Long operatorId,
            UUID tenantId,
            UUID authSessionId) {
        UUID requestId = UUID.randomUUID();
        transactions.executeWithoutResult(ignored -> {
            jdbc.update("""
                    INSERT INTO prv_support_access_requests (
                        support_access_request_id, provider_tenant_id,
                        requester_operator_id, requester_auth_session_id,
                        lifecycle_state, justification, duration_minutes,
                        approval_reference, customer_approval_required, risk_tier,
                        request_key, request_fingerprint, decision_due_at,
                        created_by, updated_by)
                    VALUES (?, ?, ?, ?, 'PENDING_APPROVAL',
                            'Direct SQL exact grant test', 30,
                            'CUSTOMER-APPROVAL-TEST', TRUE, 'L1', ?, ?,
                            statement_timestamp() + INTERVAL '1 hour', ?, ?)
                    """, requestId, tenantId, operatorId, authSessionId,
                    "request-" + requestId, "9".repeat(64), operatorId, operatorId);
            jdbc.update("""
                    INSERT INTO prv_support_access_request_scopes (
                        support_access_request_id, scope_code)
                    VALUES (?, 'TENANT_EXPERIENCE_PREVIEW')
                    """, requestId);
        });
        return requestId;
    }

    private static void insertElapsedPendingRequest(
            UUID requestId, Long operatorId, UUID tenantId) {
        transactions.executeWithoutResult(ignored -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                INSERT INTO prv_support_access_requests (
                    support_access_request_id, provider_tenant_id,
                    requester_operator_id, requester_auth_session_id,
                    lifecycle_state, justification, duration_minutes,
                    approval_reference, customer_approval_required, risk_tier,
                    request_key, request_fingerprint, decision_due_at,
                    created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, ?, ?, 'PENDING_APPROVAL', 'Expiry audit test', 15,
                        'CUSTOMER-APPROVAL-TEST', TRUE, 'L1', ?, ?,
                        statement_timestamp() - INTERVAL '1 minute',
                        statement_timestamp() - INTERVAL '25 hours',
                        statement_timestamp() - INTERVAL '25 hours', ?, ?)
                """, requestId, tenantId, operatorId, UUID.randomUUID(),
                    "expiry-" + requestId, "e".repeat(64), operatorId, operatorId);
            jdbc.update("""
                INSERT INTO prv_support_access_request_scopes (
                    support_access_request_id, scope_code)
                VALUES (?, 'TENANT_EXPERIENCE_PREVIEW')
                """, requestId);
        });
    }

    private static void assertRetainedAudit(String action, String targetId) {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_audit_events event
                  JOIN sys_audit_outbox outbox ON outbox.event_id = event.audit_event_id
                 WHERE event.action = ? AND event.target_id = ?
                   AND outbox.payload ->> 'retentionClass' = 'EXTENDED'
                """, Integer.class, action, targetId)).isEqualTo(1);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionTestConfiguration {
    }
}
