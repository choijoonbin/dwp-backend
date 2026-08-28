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
class ProviderSupportAutomaticContainmentMigrationPostgresTest {

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
    void upgradingV50ReconcilesInactiveOwnerPoisonRowsWithRetainedSystemEvidence() {
        flyway("50").migrate();
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID pendingRequestId = UUID.randomUUID();
        UUID approvedRequestId = UUID.randomUUID();
        UUID activatedRequestId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Long approverId = seededOperatorId();
        Long ownerId = jdbc.queryForObject("""
                INSERT INTO prv_operators (
                    auth_tenant_id, auth_user_id, display_name, role_code, lifecycle_state)
                VALUES (1, 990159, 'Pre-V51 inactive support owner',
                        'PROVIDER_SUPPORT', 'SUSPENDED')
                RETURNING provider_operator_id
                """, Long.class);
        jdbc.update("""
                INSERT INTO prv_operator_role_assignments (
                    provider_operator_id, role_code, lifecycle_state, created_by)
                VALUES (?, 'PROVIDER_SUPPORT', 'ACTIVE', ?)
                """, ownerId, approverId);

        TransactionTemplate transaction =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(ignored -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            insertRequest(pendingRequestId, tenantId, ownerId, null, "PENDING_APPROVAL", 0);
            insertRequest(approvedRequestId, tenantId, ownerId, approverId, "APPROVED", 1);
            insertRequest(activatedRequestId, tenantId, ownerId, approverId, "ACTIVATED", 2);
            jdbc.update("""
                    INSERT INTO prv_support_sessions (
                        support_session_id, provider_tenant_id, provider_operator_id,
                        support_access_request_id, lifecycle_state, justification,
                        token_hash, started_at, expires_at, last_used_at, access_mode,
                        approval_reference, customer_approval_required, risk_tier,
                        origin_auth_session_id, created_at, updated_at,
                        created_by, updated_by, version)
                    VALUES (?, ?, ?, ?, 'ACTIVE', 'Pre-V51 executable poison row', ?,
                            statement_timestamp() - INTERVAL '5 minutes',
                            statement_timestamp() + INTERVAL '25 minutes',
                            statement_timestamp(), 'STANDARD', 'CUSTOMER-APPROVAL-TEST',
                            TRUE, 'L1', ?, statement_timestamp() - INTERVAL '5 minutes',
                            statement_timestamp(), ?, ?, 0)
                    """, sessionId, tenantId, ownerId, activatedRequestId,
                    UUID.randomUUID().toString().replace("-", "").repeat(2),
                    UUID.randomUUID(), ownerId, ownerId);
            jdbc.update("""
                    INSERT INTO prv_support_session_scopes (support_session_id, scope_code)
                    VALUES (?, 'TENANT_EXPERIENCE_PREVIEW')
                    """, sessionId);
        });

        flyway(null).migrate();

        assertThat(jdbc.queryForMap("""
                SELECT lifecycle_state, cancellation_origin
                  FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, pendingRequestId))
                .containsEntry("lifecycle_state", "CANCELLED")
                .containsEntry("cancellation_origin", "AUTOMATIC_OPERATOR_CONTAINMENT");
        assertThat(jdbc.queryForMap("""
                SELECT lifecycle_state, cancellation_origin
                  FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, approvedRequestId))
                .containsEntry("lifecycle_state", "CANCELLED")
                .containsEntry("cancellation_origin", "AUTOMATIC_OPERATOR_CONTAINMENT");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, String.class, activatedRequestId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForMap("""
                SELECT lifecycle_state, revocation_origin
                  FROM prv_support_sessions WHERE support_session_id = ?
                """, sessionId))
                .containsEntry("lifecycle_state", "REVOKED")
                .containsEntry("revocation_origin", "AUTOMATIC_OPERATOR_CONTAINMENT");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_support_sessions WHERE lifecycle_state = 'ACTIVE'
                """, Integer.class)).isZero();

        assertRetainedSystemEvidence(
                "provider.support-access.cancelled-for-operator-state",
                pendingRequestId.toString());
        assertRetainedSystemEvidence(
                "provider.support-access.cancelled-for-operator-state",
                approvedRequestId.toString());
        assertRetainedSystemEvidence(
                "provider.support-session.revoked-for-operator-state",
                sessionId.toString());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_operator_role_assignments assignment
                  JOIN prv_operators operator
                    ON operator.provider_operator_id = assignment.provider_operator_id
                 WHERE operator.auth_tenant_id = -1
                   AND operator.auth_user_id = -5100001
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_operator_role_permissions
                 WHERE role_code = 'PROVIDER_SYSTEM_CONTAINMENT'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_audit_events event
                  JOIN sys_audit_outbox outbox ON outbox.event_id = event.audit_event_id
                 WHERE event.action =
                       'provider.support-containment.v51-admin-authority-retired'
                   AND event.redacted_snapshot ->> 'removedRoleCode' = 'PROVIDER_ADMIN'
                   AND event.redacted_snapshot ->> 'affectedSystemEventCount' = '3'
                   AND event.redacted_snapshot ->> 'misclassifiedSystemEventCount' = '3'
                   AND outbox.tenant_id > 0
                   AND outbox.payload ->> 'actorType' = 'SYSTEM'
                   AND outbox.payload -> 'actorRoles' = '[]'::jsonb
                   AND outbox.payload #>> '{metadata,systemPrincipal}' =
                       'provider-support-containment'
                """, Integer.class)).isEqualTo(1);
        flyway(null).validate();
    }

    @Test
    void upgradingV51AllowsNormallyPrunedOutboxAndRecordsTheEvidenceGap() {
        flyway("50").migrate();
        UUID originalAuditEventId = migrateV51WithAutomaticRequestCancellation(
                990169, "Pre-V51 pruned-outbox owner");
        assertThat(jdbc.update("DELETE FROM sys_audit_outbox WHERE event_id = ?",
                originalAuditEventId)).isEqualTo(1);

        flyway(null).migrate();

        assertThat(latestSuccessfulVersion()).isEqualTo(55);
        assertThat(systemAdminAssignmentCount()).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT redacted_snapshot ->> 'affectedSystemEventCount' AS affected,
                       redacted_snapshot ->> 'misclassifiedSystemEventCount' AS misclassified,
                       redacted_snapshot ->> 'missingOrPrunedLocalOutboxCount' AS missing
                  FROM prv_audit_events
                 WHERE action =
                       'provider.support-containment.v51-admin-authority-retired'
                """))
                .containsEntry("affected", "1")
                .containsEntry("misclassified", "0")
                .containsEntry("missing", "1");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_audit_events
                 WHERE action =
                       'provider.support-containment.audit-classification-compensated'
                   AND target_id = ?
                """, Integer.class, originalAuditEventId.toString())).isZero();
        flyway(null).validate();
    }

    @Test
    void upgradingV51RejectsTamperedRetainedOutboxEvidence() {
        flyway("50").migrate();
        UUID originalAuditEventId = migrateV51WithAutomaticRequestCancellation(
                990179, "Pre-V51 retained-outbox tamper owner");

        jdbc.update("""
                UPDATE sys_audit_outbox SET payload = payload - 'actorType'
                 WHERE event_id = ?
                """, originalAuditEventId);
        assertV52RejectsIncompleteRequestEvidence();
        jdbc.update("""
                UPDATE sys_audit_outbox
                   SET payload = payload || '{"actorType":"USER"}'::jsonb
                 WHERE event_id = ?
                """, originalAuditEventId);

        jdbc.update("""
                UPDATE sys_audit_outbox SET payload = payload - 'actorRoles'
                 WHERE event_id = ?
                """, originalAuditEventId);
        assertV52RejectsIncompleteRequestEvidence();
        jdbc.update("""
                UPDATE sys_audit_outbox
                   SET payload = jsonb_set(
                       payload, '{actorRoles}',
                       '["PROVIDER_SYSTEM_CONTAINMENT"]'::jsonb, TRUE)
                 WHERE event_id = ?
                """, originalAuditEventId);

        jdbc.update("""
                UPDATE sys_audit_outbox
                   SET payload = jsonb_set(
                       payload, '{actorRoles}', '["PROVIDER_ADMIN"]'::jsonb, FALSE)
                 WHERE event_id = ?
                """, originalAuditEventId);
        assertV52RejectsIncompleteRequestEvidence();

        jdbc.update("""
                UPDATE sys_audit_outbox
                   SET payload = jsonb_set(
                       payload, '{actorRoles}',
                       '["PROVIDER_SYSTEM_CONTAINMENT"]'::jsonb, FALSE)
                 WHERE event_id = ?
                """, originalAuditEventId);
        jdbc.update("""
                UPDATE sys_audit_outbox
                   SET payload = jsonb_set(
                       payload, '{afterState,reasonCode}', '"TAMPERED"'::jsonb, TRUE)
                 WHERE event_id = ?
                """, originalAuditEventId);
        assertV52RejectsIncompleteRequestEvidence();

        jdbc.update("""
                UPDATE sys_audit_outbox outbox
                   SET payload = jsonb_set(
                       outbox.payload, '{afterState}', event.redacted_snapshot, FALSE)
                  FROM prv_audit_events event
                 WHERE outbox.event_id = event.audit_event_id
                   AND outbox.event_id = ?
                """, originalAuditEventId);
        flyway(null).migrate();
        assertThat(latestSuccessfulVersion()).isEqualTo(55);
        assertThat(systemAdminAssignmentCount()).isZero();
        flyway(null).validate();
    }

    @Test
    void upgradingV50RejectsSquattedExactPrincipalAuthorityEdges() {
        flyway("50").migrate();
        Long creatorId = seededOperatorId();
        jdbc.update("""
                INSERT INTO prv_operator_roles (
                    role_code, display_name, description, lifecycle_state)
                VALUES ('PROVIDER_SYSTEM_CONTAINMENT', 'Squatted role',
                        'Pre-V51 collision with interactive authority', 'ACTIVE')
                """);
        Long squattedOperatorId = jdbc.queryForObject("""
                INSERT INTO prv_operators (
                    auth_tenant_id, auth_user_id, display_name, role_code, lifecycle_state)
                VALUES (-1, -5100001, 'Squatted negative identity',
                        'PROVIDER_SYSTEM_CONTAINMENT', 'ACTIVE')
                RETURNING provider_operator_id
                """, Long.class);
        jdbc.update("""
                INSERT INTO prv_operator_role_permissions (role_code, permission_code)
                VALUES ('PROVIDER_SYSTEM_CONTAINMENT', 'SUPPORT_SESSION_WRITE')
                """);
        jdbc.update("""
                INSERT INTO prv_operator_role_assignments (
                    provider_operator_id, role_code, lifecycle_state, created_by)
                VALUES (?, 'PROVIDER_SYSTEM_CONTAINMENT', 'ACTIVE', ?)
                """, squattedOperatorId, creatorId);

        assertThatThrownBy(() -> flyway(null).migrate())
                .rootCause()
                .hasMessageContaining(
                        "V51 support containment authority edges are not canonical");
        assertThat(latestSuccessfulVersion()).isEqualTo(51);
    }

    @Test
    void upgradingV50RejectsHumanUseOfTheReservedContainmentRole() {
        flyway("50").migrate();
        jdbc.update("""
                INSERT INTO prv_operator_roles (
                    role_code, display_name, description, lifecycle_state)
                VALUES ('PROVIDER_SYSTEM_CONTAINMENT', 'Squatted role',
                        'Pre-V51 human role collision', 'ACTIVE')
                """);
        jdbc.update("""
                INSERT INTO prv_operators (
                    auth_tenant_id, auth_user_id, display_name, role_code, lifecycle_state)
                VALUES (1, 990199, 'Human reserved-role squat',
                        'PROVIDER_SYSTEM_CONTAINMENT', 'ACTIVE')
                """);

        assertThatThrownBy(() -> flyway(null).migrate())
                .rootCause()
                .hasMessageContaining(
                        "reserved support containment identity is used by a human operator");
        assertThat(latestSuccessfulVersion()).isEqualTo(51);
    }

    @Test
    void upgradingV51RollsBackAuthorityRetirementWhenAuditWriteFails() {
        flyway("51").migrate();
        assertThat(systemAdminAssignmentCount()).isEqualTo(1);

        jdbc.execute("""
                CREATE FUNCTION prv_test_fail_containment_retirement_audit()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.action =
                       'provider.support-containment.v51-admin-authority-retired' THEN
                        RAISE EXCEPTION 'injected containment retirement audit failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_prv_test_fail_containment_retirement_audit
                BEFORE INSERT ON prv_audit_events
                FOR EACH ROW
                EXECUTE FUNCTION prv_test_fail_containment_retirement_audit()
                """);

        assertThatThrownBy(() -> flyway(null).migrate())
                .rootCause()
                .hasMessageContaining("injected containment retirement audit failure");
        assertThat(latestSuccessfulVersion()).isEqualTo(51);
        assertThat(systemAdminAssignmentCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_audit_events
                 WHERE action =
                       'provider.support-containment.v51-admin-authority-retired'
                """, Integer.class)).isZero();

        jdbc.execute("""
                DROP TRIGGER trg_prv_test_fail_containment_retirement_audit
                    ON prv_audit_events
                """);
        jdbc.execute("DROP FUNCTION prv_test_fail_containment_retirement_audit()");

        flyway(null).migrate();
        assertThat(latestSuccessfulVersion()).isEqualTo(55);
        assertThat(systemAdminAssignmentCount()).isZero();
        flyway(null).validate();
    }

    @Test
    void upgradingV51RejectsForgedAutomaticOriginWithoutAuditEvidence() {
        flyway("50").migrate();
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID requestId = UUID.randomUUID();
        Long requesterId = seededOperatorId();
        TransactionTemplate transaction =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(ignored -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            insertRequest(requestId, tenantId, requesterId, null, "PENDING_APPROVAL", 0);
        });
        flyway("51").migrate();

        Long containmentOperatorId = jdbc.queryForObject("""
                SELECT provider_operator_id FROM prv_operators
                 WHERE auth_tenant_id = -1 AND auth_user_id = -5100001
                """, Long.class);
        transaction.executeWithoutResult(ignored -> {
            jdbc.execute("""
                    SET LOCAL dwp.support_containment_origin = 'operator-unavailable'
                    """);
            jdbc.update("""
                    UPDATE prv_support_access_requests
                       SET lifecycle_state = 'CANCELLED',
                           cancelled_at = statement_timestamp(),
                           cancelled_by = ?,
                           cancellation_reason = 'Support requester became unavailable',
                           cancellation_origin = 'AUTOMATIC_OPERATOR_CONTAINMENT',
                           updated_at = statement_timestamp(),
                           updated_by = ?,
                           version = version + 1
                     WHERE support_access_request_id = ?
                    """, containmentOperatorId, containmentOperatorId, requestId);
        });

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_audit_events
                 WHERE action = 'provider.support-access.cancelled-for-operator-state'
                   AND target_id = ?
                """, Integer.class, requestId.toString())).isZero();
        assertThatThrownBy(() -> flyway(null).migrate())
                .rootCause()
                .hasMessageContaining(
                        "V51 support request containment evidence is incomplete or forged");
        assertThat(latestSuccessfulVersion()).isEqualTo(51);
        assertThat(jdbc.queryForObject("""
                SELECT cancellation_origin FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, String.class, requestId))
                .isEqualTo("AUTOMATIC_OPERATOR_CONTAINMENT");
    }

    private Integer latestSuccessfulVersion() {
        return jdbc.queryForObject("""
                SELECT MAX(version_rank) FROM (
                    SELECT CAST(version AS INTEGER) AS version_rank
                      FROM flyway_schema_history
                     WHERE success = TRUE AND version IS NOT NULL
                ) applied
                """, Integer.class);
    }

    private int systemAdminAssignmentCount() {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_operator_role_assignments assignment
                  JOIN prv_operators operator
                    ON operator.provider_operator_id = assignment.provider_operator_id
                 WHERE operator.auth_tenant_id = -1
                   AND operator.auth_user_id = -5100001
                   AND assignment.role_code = 'PROVIDER_ADMIN'
                   AND assignment.lifecycle_state = 'ACTIVE'
                """, Integer.class);
    }

    private void assertV52RejectsIncompleteRequestEvidence() {
        assertThatThrownBy(() -> flyway(null).migrate())
                .rootCause()
                .hasMessageContaining(
                        "V51 support request containment evidence is incomplete or forged");
        assertThat(latestSuccessfulVersion()).isEqualTo(51);
    }

    private UUID migrateV51WithAutomaticRequestCancellation(
            long ownerAuthUserId,
            String ownerDisplayName) {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID requestId = UUID.randomUUID();
        Long creatorId = seededOperatorId();
        Long ownerId = jdbc.queryForObject("""
                INSERT INTO prv_operators (
                    auth_tenant_id, auth_user_id, display_name, role_code, lifecycle_state)
                VALUES (1, ?, ?, 'PROVIDER_SUPPORT', 'SUSPENDED')
                RETURNING provider_operator_id
                """, Long.class, ownerAuthUserId, ownerDisplayName);
        jdbc.update("""
                INSERT INTO prv_operator_role_assignments (
                    provider_operator_id, role_code, lifecycle_state, created_by)
                VALUES (?, 'PROVIDER_SUPPORT', 'ACTIVE', ?)
                """, ownerId, creatorId);
        TransactionTemplate transaction =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(ignored -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            insertRequest(requestId, tenantId, ownerId, null, "PENDING_APPROVAL", 0);
        });

        flyway("51").migrate();
        return jdbc.queryForObject("""
                SELECT audit_event_id FROM prv_audit_events
                 WHERE action = 'provider.support-access.cancelled-for-operator-state'
                   AND target_id = ?
                """, UUID.class, requestId.toString());
    }

    private void insertRequest(
            UUID requestId,
            UUID tenantId,
            Long ownerId,
            Long approverId,
            String lifecycleState,
            int version) {
        boolean decided = approverId != null;
        boolean activated = "ACTIVATED".equals(lifecycleState);
        jdbc.update("""
                INSERT INTO prv_support_access_requests (
                    support_access_request_id, provider_tenant_id,
                    requester_operator_id, requester_auth_session_id,
                    lifecycle_state, justification, duration_minutes,
                    approval_reference, customer_approval_required, risk_tier,
                    request_key, request_fingerprint, decision_due_at,
                    decided_at, decided_by, decision_reason, activated_at,
                    created_at, updated_at, created_by, updated_by, version)
                VALUES (?, ?, ?, ?, ?, 'Pre-V51 automatic containment fixture', 30,
                        'CUSTOMER-APPROVAL-TEST', TRUE, 'L1', ?, ?,
                        statement_timestamp() + INTERVAL '1 hour',
                        CASE WHEN ? THEN statement_timestamp() - INTERVAL '10 minutes' END,
                        ?, CASE WHEN ? THEN 'Independent migration fixture approval' END,
                        CASE WHEN ? THEN statement_timestamp() - INTERVAL '5 minutes' END,
                        statement_timestamp() - INTERVAL '15 minutes',
                        statement_timestamp(), ?, ?, ?)
                """, requestId, tenantId, ownerId, UUID.randomUUID(), lifecycleState,
                "request-" + requestId, "9".repeat(64), decided, approverId, decided,
                activated, ownerId, ownerId, version);
        jdbc.update("""
                INSERT INTO prv_support_access_request_scopes (
                    support_access_request_id, scope_code)
                VALUES (?, 'TENANT_EXPERIENCE_PREVIEW')
                """, requestId);
    }

    private void assertRetainedSystemEvidence(String action, String targetId) {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_audit_events original_event
                  JOIN prv_operators operator
                    ON operator.provider_operator_id = original_event.provider_operator_id
                  JOIN sys_audit_outbox original_outbox
                    ON original_outbox.event_id = original_event.audit_event_id
                  JOIN prv_audit_events compensation
                    ON compensation.action =
                       'provider.support-containment.audit-classification-compensated'
                   AND compensation.target_type = 'AUDIT_EVENT'
                   AND compensation.target_id = original_event.audit_event_id::TEXT
                  JOIN sys_audit_outbox compensation_outbox
                    ON compensation_outbox.event_id = compensation.audit_event_id
                 WHERE original_event.action = ? AND original_event.target_id = ?
                   AND operator.auth_tenant_id = -1
                   AND operator.auth_user_id = -5100001
                   AND original_event.redacted_snapshot ->> 'transitionOrigin' =
                       'AUTOMATIC_OPERATOR_CONTAINMENT'
                   AND original_outbox.payload ->> 'actorType' = 'USER'
                   AND compensation.redacted_snapshot ->> 'originalAuditEventId' =
                       original_event.audit_event_id::TEXT
                   AND compensation.redacted_snapshot ->> 'originalAction' =
                       original_event.action
                   AND compensation_outbox.payload ->> 'actorType' = 'SYSTEM'
                   AND compensation_outbox.payload ->> 'actorId' = '-5100001'
                   AND compensation_outbox.payload -> 'actorRoles' = '[]'::jsonb
                   AND compensation_outbox.payload #>> '{metadata,providerActorKind}' =
                       'SYSTEM_CONTAINMENT'
                   AND compensation_outbox.payload #>> '{metadata,systemPrincipal}' =
                       'provider-support-containment'
                   AND compensation_outbox.payload #>> '{metadata,transitionOrigin}' =
                       'AUTOMATIC_AUDIT_CLASSIFICATION_COMPENSATION'
                   AND compensation_outbox.payload ->> 'retentionClass' = 'EXTENDED'
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
