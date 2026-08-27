package com.dwp.services.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ProviderSupportAuthorityContainmentInvariantPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private ProviderSupportAuthorityContainmentPostgresFixture fixture;

    @BeforeEach
    void migrateLatest() {
        fixture = new ProviderSupportAuthorityContainmentPostgresFixture(POSTGRES);
        fixture.cleanAndMigrate(null);
    }

    @Test
    void revokingTheOnlyAssignmentContainsEveryOwnedGrant() {
        var grants = newOwnerWithPendingAndActiveGrant(990541, "Assignment revoke owner");

        fixture.jdbc.update("""
                UPDATE prv_operator_role_assignments
                   SET lifecycle_state = 'REVOKED'
                 WHERE provider_operator_id = ?
                   AND role_code = 'PROVIDER_SUPPORT'
                """, grants.ownerId());

        assertContained(grants);
    }

    @Test
    void deletingTheOnlyAssignmentContainsEveryOwnedGrant() {
        var grants = newOwnerWithPendingAndActiveGrant(990542, "Assignment delete owner");

        fixture.jdbc.update("""
                DELETE FROM prv_operator_role_assignments
                 WHERE provider_operator_id = ?
                   AND role_code = 'PROVIDER_SUPPORT'
                """, grants.ownerId());

        assertContained(grants);
    }

    @Test
    void expiringTheOnlyAssignmentContainsEveryOwnedGrant() {
        var grants = newOwnerWithPendingAndActiveGrant(990543, "Assignment expiry owner");

        fixture.jdbc.update("""
                UPDATE prv_operator_role_assignments
                   SET valid_to = statement_timestamp() - INTERVAL '1 second'
                 WHERE provider_operator_id = ?
                   AND role_code = 'PROVIDER_SUPPORT'
                """, grants.ownerId());

        assertContained(grants);
    }

    @Test
    void retiringTheOnlyRoleContainsEveryOwnedGrant() {
        var grants = newOwnerWithPendingAndActiveGrant(990544, "Role retirement owner");

        fixture.jdbc.update("""
                UPDATE prv_operator_roles
                   SET lifecycle_state = 'RETIRED'
                 WHERE role_code = 'PROVIDER_SUPPORT'
                """);

        assertContained(grants);
    }

    @Test
    void deletingThePermissionEdgeContainsMultipleOwners() {
        var first = newOwnerWithPendingAndActiveGrant(990545, "Permission owner one");
        var second = newOwnerWithPendingAndActiveGrant(990546, "Permission owner two");

        fixture.jdbc.update("""
                DELETE FROM prv_operator_role_permissions
                 WHERE role_code = 'PROVIDER_SUPPORT'
                   AND permission_code = 'SUPPORT_SESSION_WRITE'
                """);

        assertContained(first);
        assertContained(second);
        assertThat(fixture.jdbc.queryForObject("""
                SELECT event.redacted_snapshot ->> 'cancelledRequestCount' = '2'
                       AND event.redacted_snapshot ->> 'revokedSessionCount' = '2'
                       AND event.redacted_snapshot ->> 'reason' =
                           'Support owner authority became unavailable'
                       AND event.redacted_snapshot ->> 'reasonCode' =
                           'OWNER_SUPPORT_AUTHORITY_UNAVAILABLE'
                       AND outbox.tenant_id > 0
                       AND outbox.payload ->> 'action' = event.action
                       AND outbox.payload -> 'afterState' = event.redacted_snapshot
                       AND outbox.payload ->> 'actorType' = 'SYSTEM'
                  FROM prv_audit_events event
                  JOIN sys_audit_outbox outbox ON outbox.event_id = event.audit_event_id
                 WHERE event.action = 'provider.support-authority.reconciliation-pulsed'
                 ORDER BY event.occurred_at DESC, event.audit_event_id DESC
                 LIMIT 1
                """, Boolean.class)).isTrue();
    }

    @Test
    void retiringThePermissionCatalogEntryContainsEveryOwnedGrant() {
        var grants = newOwnerWithPendingAndActiveGrant(990547, "Catalog retirement owner");

        fixture.jdbc.update("""
                UPDATE prv_operator_permission_catalog
                   SET lifecycle_state = 'RETIRED'
                 WHERE permission_code = 'SUPPORT_SESSION_WRITE'
                """);

        assertContained(grants);
    }

    @Test
    void loweringThePermissionCatalogRiskTierContainsEveryOwnedGrant() {
        var grants = newOwnerWithPendingAndActiveGrant(990548, "Catalog risk owner");

        fixture.jdbc.update("""
                UPDATE prv_operator_permission_catalog
                   SET risk_tier = 'L2'
                 WHERE permission_code = 'SUPPORT_SESSION_WRITE'
                """);

        assertContained(grants);
    }

    @Test
    void removingTheHumanIdentityBoundaryContainsEveryOwnedGrant() {
        var grants = newOwnerWithPendingAndActiveGrant(990558, "Identity boundary owner");

        fixture.jdbc.update("""
                UPDATE prv_operators SET auth_user_id = 0
                 WHERE provider_operator_id = ?
                """, grants.ownerId());

        assertContained(grants);
    }

    @Test
    void anAlternativeCurrentRolePreventsAnUnnecessaryTransition() {
        var grants = newOwnerWithPendingAndActiveGrant(990549, "Alternative authority owner");
        fixture.jdbc.update("""
                INSERT INTO prv_operator_role_assignments (
                    provider_operator_id, role_code, lifecycle_state, created_by)
                VALUES (?, 'PROVIDER_ADMIN', 'ACTIVE', ?)
                """, grants.ownerId(), fixture.seededAdminId());

        fixture.jdbc.update("""
                UPDATE prv_operator_role_assignments
                   SET lifecycle_state = 'REVOKED'
                 WHERE provider_operator_id = ?
                   AND role_code = 'PROVIDER_SUPPORT'
                """, grants.ownerId());

        assertThat(fixture.jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, String.class, grants.pendingRequestId())).isEqualTo("PENDING_APPROVAL");
        assertThat(fixture.jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_support_sessions
                 WHERE support_session_id = ?
                """, String.class, grants.activeGrant().sessionId())).isEqualTo("ACTIVE");
        assertThat(fixture.jdbc.queryForObject("""
                SELECT prv_operator_has_effective_support_authority(?)
                """, Boolean.class, grants.ownerId())).isTrue();
    }

    @Test
    void aDatabaseTimePulseContainsNaturallyExpiredAuthority() {
        var grants = newOwnerWithPendingAndActiveGrant(990550, "Natural expiry owner");
        fixture.disableTriggersAndRun("prv_operator_role_assignments", () ->
                fixture.jdbc.update("""
                        UPDATE prv_operator_role_assignments
                           SET valid_to = statement_timestamp() - INTERVAL '1 minute'
                         WHERE provider_operator_id = ?
                           AND role_code = 'PROVIDER_SUPPORT'
                        """, grants.ownerId()));
        Instant beforePulse = fixture.jdbc.queryForObject("""
                SELECT authority_reconciled_at
                  FROM prv_support_activation_control
                 WHERE control_key = 'STANDARD_JIT'
                """, Timestamp.class).toInstant();

        fixture.jdbc.update("""
                UPDATE prv_support_activation_control
                   SET authority_reconciled_at = TIMESTAMPTZ '2000-01-01 00:00:00+00'
                 WHERE control_key = 'STANDARD_JIT'
                """);

        assertContained(grants);
        Instant afterPulse = fixture.jdbc.queryForObject("""
                SELECT authority_reconciled_at
                  FROM prv_support_activation_control
                 WHERE control_key = 'STANDARD_JIT'
                """, Timestamp.class).toInstant();
        assertThat(afterPulse).isAfter(beforePulse);
        assertThat(afterPulse).isAfter(Instant.parse("2020-01-01T00:00:00Z"));
    }

    @Test
    void aNoOpPulseAdvancesDatabaseTimeWithoutWritingAuditOrOutbox() {
        int auditCount = fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM prv_audit_events", Integer.class);
        int outboxCount = fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_audit_outbox", Integer.class);
        Instant beforePulse = fixture.jdbc.queryForObject("""
                SELECT authority_reconciled_at
                  FROM prv_support_activation_control
                 WHERE control_key = 'STANDARD_JIT'
                """, Timestamp.class).toInstant();

        fixture.jdbc.update("""
                UPDATE prv_support_activation_control
                   SET authority_reconciled_at = TIMESTAMPTZ '2000-01-01 00:00:00+00'
                 WHERE control_key = 'STANDARD_JIT'
                """);

        assertThat(fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM prv_audit_events", Integer.class)).isEqualTo(auditCount);
        assertThat(fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_audit_outbox", Integer.class)).isEqualTo(outboxCount);
        assertThat(fixture.jdbc.queryForObject("""
                SELECT authority_reconciled_at
                  FROM prv_support_activation_control
                 WHERE control_key = 'STANDARD_JIT'
                """, Timestamp.class).toInstant()).isAfter(beforePulse);
    }

    @Test
    void aTopLevelCallerCannotSpoofTheAuthorityContainmentOrigin() {
        var grants = newOwnerWithPendingAndActiveGrant(990551, "Authority spoof owner");
        fixture.disableTriggersAndRun("prv_operator_role_assignments", () ->
                fixture.jdbc.update("""
                        UPDATE prv_operator_role_assignments
                           SET lifecycle_state = 'REVOKED'
                         WHERE provider_operator_id = ?
                           AND role_code = 'PROVIDER_SUPPORT'
                        """, grants.ownerId()));
        long systemActorId = systemActorId();
        var transaction = new TransactionTemplate(
                new DataSourceTransactionManager(fixture.dataSource));

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
            fixture.jdbc.execute("""
                    SELECT set_config(
                        'dwp.support_containment_origin', 'authority-unavailable', TRUE)
                    """);
            fixture.jdbc.update("""
                    UPDATE prv_support_access_requests
                       SET lifecycle_state = 'CANCELLED',
                           cancelled_at = statement_timestamp(), cancelled_by = ?,
                           cancellation_reason =
                               'Support owner authority became unavailable',
                           cancellation_origin = 'AUTOMATIC_AUTHORITY_CONTAINMENT',
                           updated_by = ?, version = version + 1
                     WHERE support_access_request_id = ?
                    """, systemActorId, systemActorId, grants.pendingRequestId());
        })).rootCause().hasMessageContaining("containment provenance is invalid");

        assertThatThrownBy(() -> fixture.jdbc.queryForObject(
                "SELECT prv_reconcile_support_authority_pulse()", Object.class))
                .rootCause()
                .hasMessageContaining("trigger functions can only be called as triggers");
    }

    @Test
    void anAuditFailureRollsBackAuthorityRetirementAndContainment() {
        assertEvidenceFailureRollsBack("prv_audit_events", """
                CREATE OR REPLACE FUNCTION test_fail_authority_audit()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.action =
                       'provider.support-access.cancelled-for-authority-loss' THEN
                        RAISE EXCEPTION 'test authority audit failure';
                    END IF;
                    RETURN NEW;
                END;
                $$;
                CREATE TRIGGER trg_test_fail_authority_audit
                BEFORE INSERT ON prv_audit_events
                FOR EACH ROW EXECUTE FUNCTION test_fail_authority_audit();
                """, "test authority audit failure");
    }

    @Test
    void anOutboxFailureRollsBackAuthorityRetirementAndContainment() {
        assertEvidenceFailureRollsBack("sys_audit_outbox", """
                CREATE OR REPLACE FUNCTION test_fail_authority_outbox()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.payload ->> 'action' =
                       'provider.support-access.cancelled-for-authority-loss' THEN
                        RAISE EXCEPTION 'test authority outbox failure';
                    END IF;
                    RETURN NEW;
                END;
                $$;
                CREATE TRIGGER trg_test_fail_authority_outbox
                BEFORE INSERT ON sys_audit_outbox
                FOR EACH ROW EXECUTE FUNCTION test_fail_authority_outbox();
                """, "test authority outbox failure");
    }

    private void assertEvidenceFailureRollsBack(
            String failureTable,
            String faultSql,
            String expectedFailure) {
        var grants = newOwnerWithPendingAndActiveGrant(
                990560 + Math.abs(failureTable.hashCode() % 20),
                "Evidence rollback owner " + failureTable);
        fixture.jdbc.execute(faultSql);

        assertThatThrownBy(() -> fixture.jdbc.update("""
                UPDATE prv_operator_role_assignments
                   SET lifecycle_state = 'REVOKED'
                 WHERE provider_operator_id = ?
                   AND role_code = 'PROVIDER_SUPPORT'
                """, grants.ownerId())).rootCause().hasMessageContaining(expectedFailure);

        assertThat(fixture.jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_operator_role_assignments
                 WHERE provider_operator_id = ? AND role_code = 'PROVIDER_SUPPORT'
                """, String.class, grants.ownerId())).isEqualTo("ACTIVE");
        assertThat(fixture.jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, String.class, grants.pendingRequestId())).isEqualTo("PENDING_APPROVAL");
        assertThat(fixture.jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_support_sessions
                 WHERE support_session_id = ?
                """, String.class, grants.activeGrant().sessionId())).isEqualTo("ACTIVE");
    }

    private OwnerGrants newOwnerWithPendingAndActiveGrant(long authUserId, String name) {
        long ownerId = fixture.newOwner(authUserId, name);
        return new OwnerGrants(
                ownerId,
                fixture.insertPendingRequest(ownerId),
                fixture.insertActiveGrant(ownerId));
    }

    private void assertContained(OwnerGrants grants) {
        assertThat(fixture.jdbc.queryForMap("""
                SELECT lifecycle_state, cancellation_origin
                  FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, grants.pendingRequestId()))
                .containsEntry("lifecycle_state", "CANCELLED")
                .containsEntry("cancellation_origin", "AUTOMATIC_AUTHORITY_CONTAINMENT");
        assertThat(fixture.jdbc.queryForMap("""
                SELECT lifecycle_state, revocation_origin
                  FROM prv_support_sessions
                 WHERE support_session_id = ?
                """, grants.activeGrant().sessionId()))
                .containsEntry("lifecycle_state", "REVOKED")
                .containsEntry("revocation_origin", "AUTOMATIC_AUTHORITY_CONTAINMENT");
        assertThat(fixture.jdbc.queryForMap("""
                SELECT lifecycle_state, post_review_state
                  FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, grants.activeGrant().requestId()))
                .containsEntry("lifecycle_state", "COMPLETED")
                .containsEntry("post_review_state", "PENDING");
        assertSystemEvidence(
                "provider.support-access.cancelled-for-authority-loss",
                grants.pendingRequestId().toString());
        assertSystemEvidence(
                "provider.support-session.revoked-for-authority-loss",
                grants.activeGrant().sessionId().toString());
    }

    private void assertSystemEvidence(String action, String targetId) {
        assertThat(fixture.jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_audit_events event
                  JOIN sys_audit_outbox outbox ON outbox.event_id = event.audit_event_id
                 WHERE event.action = ? AND event.target_id = ?
                   AND event.actor_id = -5100001
                   AND event.redacted_snapshot ->> 'requiredPermission' =
                       'SUPPORT_SESSION_WRITE'
                   AND event.redacted_snapshot ->> 'requiredPermissionRiskTier' = 'L3'
                   AND event.redacted_snapshot ->> 'reason' =
                       'Support owner authority became unavailable'
                   AND event.redacted_snapshot ->> 'reasonCode' =
                       'OWNER_SUPPORT_AUTHORITY_UNAVAILABLE'
                   AND event.redacted_snapshot ->> 'transitionOrigin' =
                       'AUTOMATIC_AUTHORITY_CONTAINMENT'
                   AND outbox.tenant_id > 0
                   AND outbox.payload ->> 'action' = event.action
                   AND outbox.payload -> 'afterState' = event.redacted_snapshot
                   AND outbox.payload ->> 'actorType' = 'SYSTEM'
                   AND outbox.payload -> 'actorRoles' = '[]'::jsonb
                   AND outbox.payload #>> '{metadata,systemPrincipal}' =
                       'provider-support-containment'
                   AND outbox.payload ->> 'retentionClass' = 'EXTENDED'
                """, Integer.class, action, targetId)).isEqualTo(1);
    }

    private long systemActorId() {
        return fixture.jdbc.queryForObject("""
                SELECT provider_operator_id FROM prv_operators
                 WHERE auth_tenant_id = -1 AND auth_user_id = -5100001
                """, Long.class);
    }

    private record OwnerGrants(
            long ownerId,
            java.util.UUID pendingRequestId,
            ProviderSupportAuthorityContainmentPostgresFixture.ActiveGrant activeGrant) {
    }
}
