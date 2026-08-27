package com.dwp.services.provider;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

final class ProviderSupportAuthorityContainmentPostgresFixture {

    static final UUID TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    final PGSimpleDataSource dataSource;
    final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    ProviderSupportAuthorityContainmentPostgresFixture(PostgreSQLContainer<?> postgres) {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    Flyway flyway(String target) {
        return flyway(target, true);
    }

    Flyway flyway(String target, boolean validateOnMigrate) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false)
                .validateOnMigrate(validateOnMigrate);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    void cleanAndMigrate(String target) {
        flyway(null).clean();
        flyway(target).migrate();
        prepareExactPreviewBoundary();
    }

    void prepareExactPreviewBoundary() {
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
                 WHERE provider_tenant_id = ?
                """, TENANT_ID);
    }

    long seededAdminId() {
        return jdbc.queryForObject("""
                SELECT provider_operator_id
                  FROM prv_operators
                 WHERE auth_tenant_id = 1 AND auth_user_id = 900001
                """, Long.class);
    }

    long newOwner(long authUserId, String displayName) {
        long ownerId = jdbc.queryForObject("""
                INSERT INTO prv_operators (
                    auth_tenant_id, auth_user_id, display_name,
                    role_code, lifecycle_state)
                VALUES (1, ?, ?, 'PROVIDER_SUPPORT', 'ACTIVE')
                RETURNING provider_operator_id
                """, Long.class, authUserId, displayName);
        jdbc.update("""
                INSERT INTO prv_operator_role_assignments (
                    provider_operator_id, role_code, lifecycle_state, created_by)
                VALUES (?, 'PROVIDER_SUPPORT', 'ACTIVE', ?)
                """, ownerId, seededAdminId());
        return ownerId;
    }

    UUID insertPendingRequest(long ownerId) {
        UUID requestId = UUID.randomUUID();
        transactions.executeWithoutResult(ignored -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            insertRequest(requestId, ownerId, "PENDING_APPROVAL", null, 0);
        });
        return requestId;
    }

    UUID insertApprovedRequest(long ownerId) {
        UUID requestId = UUID.randomUUID();
        transactions.executeWithoutResult(ignored -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            insertRequest(requestId, ownerId, "APPROVED", seededAdminId(), 1);
        });
        return requestId;
    }

    void enableActivation() {
        jdbc.update("""
                UPDATE prv_support_activation_control
                   SET activation_enabled = TRUE,
                       change_reason = 'Enable exact authority containment fixture',
                       change_correlation_id = 'test:authority-containment',
                       changed_by = ?, version = version + 1
                 WHERE control_key = 'STANDARD_JIT'
                   AND NOT activation_enabled
                """, seededAdminId());
    }

    ActiveGrant insertActiveGrant(long ownerId) {
        UUID requestId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        transactions.executeWithoutResult(ignored -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            insertRequest(requestId, ownerId, "ACTIVATED", seededAdminId(), 2);
            jdbc.update("""
                    INSERT INTO prv_support_sessions (
                        support_session_id, provider_tenant_id, provider_operator_id,
                        support_access_request_id, lifecycle_state, justification,
                        token_hash, started_at, expires_at, last_used_at, access_mode,
                        approval_reference, customer_approval_required, risk_tier,
                        origin_auth_session_id, created_at, updated_at,
                        created_by, updated_by, version)
                    VALUES (?, ?, ?, ?, 'ACTIVE', 'Authority containment fixture', ?,
                            statement_timestamp() - INTERVAL '5 minutes',
                            statement_timestamp() + INTERVAL '25 minutes',
                            statement_timestamp() - INTERVAL '1 minute',
                            'STANDARD', 'CUSTOMER-APPROVAL-AUTHORITY', TRUE, 'L1', ?,
                            statement_timestamp() - INTERVAL '5 minutes',
                            statement_timestamp(), ?, ?, 0)
                    """, sessionId, TENANT_ID, ownerId, requestId,
                    uniqueTokenHash(), UUID.randomUUID(), ownerId, ownerId);
            jdbc.update("""
                    INSERT INTO prv_support_session_scopes (
                        support_session_id, scope_code)
                    VALUES (?, 'TENANT_EXPERIENCE_PREVIEW')
                    """, sessionId);
        });
        return new ActiveGrant(requestId, sessionId);
    }

    void disableTriggersAndRun(String tableName, Runnable mutation) {
        transactions.executeWithoutResult(ignored -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            mutation.run();
        });
    }

    private void insertRequest(
            UUID requestId,
            long ownerId,
            String lifecycleState,
            Long approverId,
            long version) {
        boolean decided = approverId != null;
        boolean activated = "ACTIVATED".equals(lifecycleState);
        jdbc.update("""
                INSERT INTO prv_support_access_requests (
                    support_access_request_id, provider_tenant_id,
                    requester_operator_id, requester_auth_session_id,
                    lifecycle_state, access_mode, justification, duration_minutes,
                    approval_reference, customer_approval_required, risk_tier,
                    request_key, request_fingerprint, decision_due_at,
                    decided_at, decided_by, decision_reason, activated_at,
                    post_review_state, version, created_at, created_by,
                    updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 'STANDARD', 'Authority containment fixture', 30,
                        'CUSTOMER-APPROVAL-AUTHORITY', TRUE, 'L1', ?, ?,
                        statement_timestamp() + INTERVAL '1 hour',
                        CASE WHEN ? THEN statement_timestamp() - INTERVAL '10 minutes' END,
                        ?, CASE WHEN ? THEN 'Independent authority fixture approval' END,
                        CASE WHEN ? THEN statement_timestamp() - INTERVAL '5 minutes' END,
                        'NOT_REQUIRED', ?, statement_timestamp() - INTERVAL '15 minutes',
                        ?, statement_timestamp(), ?)
                """, requestId, TENANT_ID, ownerId, UUID.randomUUID(), lifecycleState,
                "authority-" + requestId, "a".repeat(64), decided, approverId, decided,
                activated, version, ownerId, ownerId);
        jdbc.update("""
                INSERT INTO prv_support_access_request_scopes (
                    support_access_request_id, scope_code)
                VALUES (?, 'TENANT_EXPERIENCE_PREVIEW')
                """, requestId);
    }

    private static String uniqueTokenHash() {
        return UUID.randomUUID().toString().replace("-", "").repeat(2);
    }

    record ActiveGrant(UUID requestId, UUID sessionId) {
    }
}
