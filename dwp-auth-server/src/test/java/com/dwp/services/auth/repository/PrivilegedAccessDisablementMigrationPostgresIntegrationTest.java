package com.dwp.services.auth.repository;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PostgreSQL proof for the V108 privileged-activation kill switch and upgrade cleanup. */
@Testcontainers(disabledWithoutDocker = true)
class PrivilegedAccessDisablementMigrationPostgresIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static Long tenantId;
    private static Long roleId;
    private static Long secondRoleId;
    private static Long requesterId;
    private static UUID pendingRequestId;
    private static UUID activeRequestId;
    private static UUID activeGrantId;

    @BeforeAll
    static void migrateThroughTheDisabledRolloutBoundary() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        migrate(dataSource, "107", true);
        jdbc = new JdbcTemplate(dataSource);
        seedOpenPrivilegedAccess();
        migrate(dataSource, "108", false);
    }

    @Test
    void v108RevokesLiveGrantsCancelsOpenRequestsAndDisablesPolicies() {
        assertThat(jdbc.queryForMap("""
                SELECT activation_mode, emergency_mode, version
                  FROM com_privileged_access_policies
                 WHERE tenant_id = ? AND role_id = ?
                """, tenantId, roleId))
                .containsEntry("activation_mode", "DISABLED")
                .containsEntry("emergency_mode", "DISABLED")
                .containsEntry("version", 1L);
        assertThat(jdbc.queryForMap("""
                SELECT lifecycle_state, decided_at IS NOT NULL AS decided, version
                  FROM com_privileged_access_requests
                 WHERE privileged_access_request_id = ?
                """, pendingRequestId))
                .containsEntry("lifecycle_state", "CANCELLED")
                .containsEntry("decided", true)
                .containsEntry("version", 1L);
        assertThat(jdbc.queryForMap("""
                SELECT lifecycle_state, revoked_at IS NOT NULL AS revoked, version
                  FROM com_privileged_access_requests
                 WHERE privileged_access_request_id = ?
                """, activeRequestId))
                .containsEntry("lifecycle_state", "REVOKED")
                .containsEntry("revoked", true)
                .containsEntry("version", 1L);
        assertThat(jdbc.queryForMap("""
                SELECT revoked_at IS NOT NULL AS revoked, revoke_reason
                  FROM com_active_privileged_grants
                 WHERE active_privileged_grant_id = ?
                """, activeGrantId))
                .containsEntry("revoked", true)
                .containsEntry(
                        "revoke_reason",
                        "Privileged access activation is disabled for this release.");
    }

    @Test
    void policyTriggerRejectsEveryAttemptToEnableActivation() {
        assertCheckViolation(() -> jdbc.update("""
                UPDATE com_privileged_access_policies
                   SET activation_mode = 'APPROVAL'
                 WHERE tenant_id = ? AND role_id = ?
                """, tenantId, roleId),
                "Privileged access activation is disabled for this release");
        assertCheckViolation(() -> jdbc.update("""
                INSERT INTO com_privileged_access_policies (
                    tenant_id, role_id, activation_mode, maximum_duration_minutes,
                    assurance_level, approval_quorum, emergency_mode, ticket_required,
                    lifecycle_state)
                VALUES (?, ?, 'DISABLED', 120, 'MFA', 1,
                        'REGISTERED_PRINCIPAL', TRUE, 'ACTIVE')
                """, tenantId, secondRoleId),
                "Privileged access activation is disabled for this release");
    }

    @Test
    void requestTriggerRejectsBothPendingAndActiveLifecycleStates() {
        assertRequestStateRejected("PENDING_APPROVAL");
        assertRequestStateRejected("ACTIVE");
    }

    @Test
    void grantTriggerRejectsReturningARevokedGrantToLiveState() {
        assertCheckViolation(() -> jdbc.update("""
                UPDATE com_active_privileged_grants
                   SET revoked_at = NULL, revoke_reason = NULL
                 WHERE active_privileged_grant_id = ?
                """, activeGrantId),
                "Active privileged grants are disabled for this release");
    }

    private static void seedOpenPrivilegedAccess() {
        tenantId = jdbc.queryForObject("""
                INSERT INTO com_tenants (code, name, status)
                VALUES ('v108-integration', 'V108 integration', 'ACTIVE')
                RETURNING tenant_id
                """, Long.class);
        requesterId = user("v108-requester");
        Long secondRequesterId = user("v108-second-requester");
        roleId = role("V108_PRIVILEGED");
        secondRoleId = role("V108_SECOND_PRIVILEGED");
        jdbc.update("""
                INSERT INTO com_privileged_access_policies (
                    tenant_id, role_id, activation_mode, maximum_duration_minutes,
                    assurance_level, approval_quorum, emergency_mode, ticket_required,
                    lifecycle_state)
                VALUES (?, ?, 'APPROVAL', 120, 'MFA', 1,
                        'REGISTERED_PRINCIPAL', TRUE, 'ACTIVE')
                """, tenantId, roleId);
        pendingRequestId = request(requesterId, "PENDING_APPROVAL");
        activeRequestId = request(secondRequesterId, "ACTIVE");
        activeGrantId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_active_privileged_grants (
                    active_privileged_grant_id, privileged_access_request_id,
                    tenant_id, user_id, role_id, scope_type,
                    activated_at, expires_at)
                VALUES (?, ?, ?, ?, ?, 'TENANT',
                        CURRENT_TIMESTAMP - INTERVAL '5 minutes',
                        CURRENT_TIMESTAMP + INTERVAL '55 minutes')
                """, activeGrantId, activeRequestId, tenantId, secondRequesterId, roleId);
    }

    private static Long user(String name) {
        return jdbc.queryForObject("""
                INSERT INTO com_users (tenant_id, display_name, email, status)
                VALUES (?, ?, ?, 'ACTIVE') RETURNING user_id
                """, Long.class, tenantId, name, name + "@v108.test");
    }

    private static Long role(String code) {
        return jdbc.queryForObject("""
                INSERT INTO com_roles (
                    tenant_id, code, name, description, status,
                    role_type, privileged, assignable_to_groups)
                VALUES (?, ?, ?, 'V108 PostgreSQL integration role', 'ACTIVE',
                        'CUSTOM', TRUE, FALSE)
                RETURNING role_id
                """, Long.class, tenantId, code, code);
    }

    private static UUID request(Long userId, String lifecycleState) {
        UUID requestId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_privileged_access_requests (
                    privileged_access_request_id, tenant_id, requester_user_id,
                    role_id, request_type, scope_type, duration_minutes,
                    justification, assurance_level, approval_quorum,
                    lifecycle_state, activated_at, expires_at)
                VALUES (?, ?, ?, ?, 'JIT', 'TENANT', 60,
                        'V108 PostgreSQL integration request', 'MFA', 1, ?,
                        CASE WHEN ? = 'ACTIVE' THEN CURRENT_TIMESTAMP ELSE NULL END,
                        CASE WHEN ? = 'ACTIVE'
                             THEN CURRENT_TIMESTAMP + INTERVAL '1 hour' ELSE NULL END)
                """, requestId, tenantId, userId, roleId, lifecycleState,
                lifecycleState, lifecycleState);
        return requestId;
    }

    private static void assertRequestStateRejected(String lifecycleState) {
        assertCheckViolation(() -> request(requesterId, lifecycleState),
                "Privileged access requests are disabled for this release");
    }

    private static void assertCheckViolation(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            String message) {
        assertThatThrownBy(operation)
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining(message)
                .extracting(error -> ((PSQLException) error).getSQLState())
                .isEqualTo("23514");
    }

    private static void migrate(
            PGSimpleDataSource dataSource,
            String target,
            boolean clean) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target(target)
                .cleanDisabled(false)
                .load();
        if (clean) flyway.clean();
        flyway.migrate();
    }
}
