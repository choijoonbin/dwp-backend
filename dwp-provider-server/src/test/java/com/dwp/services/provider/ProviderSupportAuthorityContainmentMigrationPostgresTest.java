package com.dwp.services.provider;

import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ProviderSupportAuthorityContainmentMigrationPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private ProviderSupportAuthorityContainmentPostgresFixture fixture;

    @BeforeEach
    void configureDatabase() {
        fixture = new ProviderSupportAuthorityContainmentPostgresFixture(POSTGRES);
    }

    @Test
    void cleanLatestBuildsTheAuthorityContainmentBoundary() {
        fixture.cleanAndMigrate(null);

        assertThat(latestVersion()).isEqualTo(55);
        assertThat(fixture.jdbc.queryForObject("""
                SELECT authority_reconciled_at IS NOT NULL
                  FROM prv_support_activation_control
                 WHERE control_key = 'STANDARD_JIT'
                """, Boolean.class)).isTrue();
        assertThat(fixture.jdbc.queryForObject("""
                SELECT prv_operator_has_effective_support_authority(?)
                """, Boolean.class, fixture.seededAdminId())).isTrue();
        assertZeroSystemAuthority();
        fixture.flyway(null).validate();
    }

    @Test
    void exactV52UpgradeContainsDirtyAuthorityRowsAndAppendsSystemEvidence() {
        fixture.cleanAndMigrate("52");
        long ownerId = fixture.newOwner(990531, "V52 dirty authority owner");
        var grant = fixture.insertActiveGrant(ownerId);
        var pendingRequestId = fixture.insertPendingRequest(ownerId);
        fixture.disableTriggersAndRun("prv_operator_role_assignments", () ->
                fixture.jdbc.update("""
                        UPDATE prv_operator_role_assignments
                           SET valid_to = statement_timestamp() - INTERVAL '1 minute'
                         WHERE provider_operator_id = ?
                           AND role_code = 'PROVIDER_SUPPORT'
                        """, ownerId));

        fixture.flyway(null).migrate();

        assertThat(latestVersion()).isEqualTo(55);
        assertThat(fixture.jdbc.queryForMap("""
                SELECT lifecycle_state, cancellation_origin
                  FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, pendingRequestId))
                .containsEntry("lifecycle_state", "CANCELLED")
                .containsEntry("cancellation_origin", "AUTOMATIC_AUTHORITY_CONTAINMENT");
        assertThat(fixture.jdbc.queryForMap("""
                SELECT lifecycle_state, revocation_origin
                  FROM prv_support_sessions
                 WHERE support_session_id = ?
                """, grant.sessionId()))
                .containsEntry("lifecycle_state", "REVOKED")
                .containsEntry("revocation_origin", "AUTOMATIC_AUTHORITY_CONTAINMENT");
        assertThat(fixture.jdbc.queryForMap("""
                SELECT lifecycle_state, post_review_state
                  FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, grant.requestId()))
                .containsEntry("lifecycle_state", "COMPLETED")
                .containsEntry("post_review_state", "PENDING");
        assertExactSystemEvidence(
                "provider.support-access.cancelled-for-authority-loss",
                pendingRequestId.toString());
        assertExactSystemEvidence(
                "provider.support-session.revoked-for-authority-loss",
                grant.sessionId().toString());
        assertZeroInvalidGrants();
        assertZeroSystemAuthority();
        fixture.flyway(null).validate();
    }

    @Test
    void upgradeFailsClosedWhenTheRecordedV52ChecksumIsNotExact() {
        fixture.cleanAndMigrate("52");
        fixture.jdbc.update("""
                UPDATE flyway_schema_history
                   SET checksum = 1542396036
                 WHERE version = '52'
                """);

        assertThatThrownBy(() -> fixture.flyway(null, false).migrate())
                .isInstanceOf(FlywayException.class)
                .rootCause()
                .hasMessageContaining("exact applied V52 support containment baseline");
        assertThat(latestVersion()).isEqualTo(52);
    }

    @Test
    void upgradeFailsClosedWhenTheSystemPrincipalRegainsAuthority() {
        fixture.cleanAndMigrate("52");
        long systemId = fixture.jdbc.queryForObject("""
                SELECT provider_operator_id FROM prv_operators
                 WHERE auth_tenant_id = -1 AND auth_user_id = -5100001
                """, Long.class);
        fixture.disableTriggersAndRun("prv_operator_role_assignments", () ->
                fixture.jdbc.update("""
                        INSERT INTO prv_operator_role_assignments (
                            provider_operator_id, role_code, lifecycle_state, created_by)
                        VALUES (?, 'PROVIDER_ADMIN', 'ACTIVE', -5100001)
                        """, systemId));

        assertThatThrownBy(() -> fixture.flyway(null).migrate())
                .isInstanceOf(FlywayException.class)
                .rootCause()
                .hasMessageContaining("zero-authority principal is not canonical");
        assertThat(latestVersion()).isEqualTo(52);
    }

    private int latestVersion() {
        return fixture.jdbc.queryForObject("""
                SELECT MAX(version::INTEGER) FROM flyway_schema_history WHERE success
                """, Integer.class);
    }

    private void assertZeroSystemAuthority() {
        assertThat(fixture.jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_operator_role_assignments assignment
                  JOIN prv_operators operator
                    ON operator.provider_operator_id = assignment.provider_operator_id
                 WHERE operator.auth_tenant_id = -1
                    OR assignment.role_code = 'PROVIDER_SYSTEM_CONTAINMENT'
                """, Integer.class)).isZero();
        assertThat(fixture.jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_operator_role_permissions
                 WHERE role_code = 'PROVIDER_SYSTEM_CONTAINMENT'
                """, Integer.class)).isZero();
    }

    private void assertZeroInvalidGrants() {
        assertThat(fixture.jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_support_access_requests request
                 WHERE request.lifecycle_state IN (
                           'PENDING_APPROVAL', 'APPROVED', 'ACTIVATED')
                   AND NOT prv_operator_has_effective_support_authority(
                           request.requester_operator_id)
                """, Integer.class)).isZero();
        assertThat(fixture.jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_support_sessions session
                 WHERE session.lifecycle_state = 'ACTIVE'
                   AND NOT prv_operator_has_effective_support_authority(
                           session.provider_operator_id)
                """, Integer.class)).isZero();
    }

    private void assertExactSystemEvidence(String action, String targetId) {
        assertThat(fixture.jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_audit_events event
                  JOIN prv_operators actor
                    ON actor.provider_operator_id = event.provider_operator_id
                  JOIN sys_audit_outbox outbox ON outbox.event_id = event.audit_event_id
                 WHERE event.action = ?
                   AND event.target_id = ?
                   AND event.actor_id = -5100001
                   AND actor.auth_tenant_id = -1
                   AND actor.auth_user_id = -5100001
                   AND event.outcome = 'SUCCESS'
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
                   AND outbox.payload #>> '{metadata,transitionOrigin}' =
                       'AUTOMATIC_AUTHORITY_CONTAINMENT'
                   AND outbox.payload ->> 'retentionClass' = 'EXTENDED'
                """, Integer.class, action, targetId)).isEqualTo(1);
    }
}
