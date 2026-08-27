package com.dwp.services.provider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ProviderSupportAuthorityContainmentConcurrencyPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private ProviderSupportAuthorityContainmentPostgresFixture fixture;
    private ExecutorService executor;

    @BeforeEach
    void migrateLatest() {
        fixture = new ProviderSupportAuthorityContainmentPostgresFixture(POSTGRES);
        fixture.cleanAndMigrate(null);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void activationFirstThenOperatorRetirementConvergesWithoutDeadlock()
            throws Exception {
        fixture.enableActivation();
        long ownerId = fixture.newOwner(990571, "Concurrent activation owner");
        UUID requestId = fixture.insertApprovedRequest(ownerId);
        UUID sessionId = UUID.randomUUID();
        CountDownLatch sessionActivated = new CountDownLatch(1);
        CountDownLatch allowActivationCommit = new CountDownLatch(1);
        CountDownLatch retirementReady = new CountDownLatch(1);
        AtomicInteger retirementPid = new AtomicInteger();

        Future<Void> activation = executor.submit(() -> {
            try (Connection connection = fixture.dataSource.getConnection()) {
                beginBounded(connection);
                activateSession(connection, requestId, ownerId, sessionId);
                sessionActivated.countDown();
                await(allowActivationCommit);
                connection.commit();
            }
            return null;
        });
        await(sessionActivated);

        Future<Void> retirement = executor.submit(() -> {
            try (Connection connection = fixture.dataSource.getConnection()) {
                beginBounded(connection);
                retirementPid.set(backendPid(connection));
                retirementReady.countDown();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE prv_operators SET lifecycle_state = 'SUSPENDED'
                         WHERE provider_operator_id = ?
                        """)) {
                    statement.setLong(1, ownerId);
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
                connection.commit();
            }
            return null;
        });
        await(retirementReady);
        awaitDatabaseLockWait(retirementPid.get());
        allowActivationCommit.countDown();

        activation.get(15, TimeUnit.SECONDS);
        retirement.get(15, TimeUnit.SECONDS);
        assertThat(fixture.jdbc.queryForMap("""
                SELECT lifecycle_state, revocation_origin
                  FROM prv_support_sessions WHERE support_session_id = ?
                """, sessionId))
                .containsEntry("lifecycle_state", "REVOKED")
                .containsEntry("revocation_origin", "AUTOMATIC_OPERATOR_CONTAINMENT");
        assertThat(fixture.jdbc.queryForMap("""
                SELECT lifecycle_state, post_review_state
                  FROM prv_support_access_requests WHERE support_access_request_id = ?
                """, requestId))
                .containsEntry("lifecycle_state", "COMPLETED")
                .containsEntry("post_review_state", "PENDING");
    }

    @Test
    void operatorRetirementFirstThenActivationFailsClosedAfterTheControlWait()
            throws Exception {
        fixture.enableActivation();
        long ownerId = fixture.newOwner(990574, "Retirement first activation owner");
        UUID requestId = fixture.insertApprovedRequest(ownerId);
        UUID sessionId = UUID.randomUUID();
        CountDownLatch retirementApplied = new CountDownLatch(1);
        CountDownLatch allowRetirementCommit = new CountDownLatch(1);
        CountDownLatch activationReady = new CountDownLatch(1);
        AtomicInteger activationPid = new AtomicInteger();

        Future<Void> retirement = executor.submit(() -> {
            try (Connection connection = fixture.dataSource.getConnection()) {
                beginBounded(connection);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE prv_operators SET lifecycle_state = 'SUSPENDED'
                         WHERE provider_operator_id = ?
                        """)) {
                    statement.setLong(1, ownerId);
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
                retirementApplied.countDown();
                await(allowRetirementCommit);
                connection.commit();
            }
            return null;
        });
        await(retirementApplied);

        Future<String> activation = executor.submit(() -> {
            try (Connection connection = fixture.dataSource.getConnection()) {
                beginBounded(connection);
                activationPid.set(backendPid(connection));
                activationReady.countDown();
                try {
                    activateSession(connection, requestId, ownerId, sessionId);
                    connection.commit();
                    return "unexpected activation success";
                } catch (Throwable failure) {
                    connection.rollback();
                    return deepestMessage(failure);
                }
            }
        });
        await(activationReady);
        awaitDatabaseLockWait(activationPid.get());
        allowRetirementCommit.countDown();

        retirement.get(15, TimeUnit.SECONDS);
        assertThat(activation.get(15, TimeUnit.SECONDS))
                .contains("lacks effective support authority");
        assertThat(fixture.jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_support_sessions WHERE support_session_id = ?
                """, Integer.class, sessionId)).isZero();
        assertThat(fixture.jdbc.queryForMap("""
                SELECT lifecycle_state, cancellation_origin
                  FROM prv_support_access_requests WHERE support_access_request_id = ?
                """, requestId))
                .containsEntry("lifecycle_state", "CANCELLED")
                .containsEntry("cancellation_origin", "AUTOMATIC_OPERATOR_CONTAINMENT");
    }

    @Test
    void concurrentAssignmentExpiryAndPeriodicPulseUseTheControlRowOrder()
            throws Exception {
        long ownerId = fixture.newOwner(990572, "Concurrent expiry owner");
        var grant = fixture.insertActiveGrant(ownerId);
        CountDownLatch expiryUpdated = new CountDownLatch(1);
        CountDownLatch allowExpiryCommit = new CountDownLatch(1);
        CountDownLatch pulseReady = new CountDownLatch(1);
        AtomicInteger pulsePid = new AtomicInteger();

        Future<Void> expiry = executor.submit(() -> {
            try (Connection connection = fixture.dataSource.getConnection()) {
                beginBounded(connection);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE prv_operator_role_assignments
                           SET valid_to = statement_timestamp() - INTERVAL '1 second'
                         WHERE provider_operator_id = ?
                           AND role_code = 'PROVIDER_SUPPORT'
                        """)) {
                    statement.setLong(1, ownerId);
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
                expiryUpdated.countDown();
                await(allowExpiryCommit);
                connection.commit();
            }
            return null;
        });
        await(expiryUpdated);

        Future<Void> pulse = executor.submit(() -> {
            try (Connection connection = fixture.dataSource.getConnection()) {
                beginBounded(connection);
                pulsePid.set(backendPid(connection));
                pulseReady.countDown();
                try (Statement statement = connection.createStatement()) {
                    assertThat(statement.executeUpdate("""
                            UPDATE prv_support_activation_control
                               SET authority_reconciled_at = statement_timestamp()
                             WHERE control_key = 'STANDARD_JIT'
                            """)).isEqualTo(1);
                }
                connection.commit();
            }
            return null;
        });
        await(pulseReady);
        awaitDatabaseLockWait(pulsePid.get());
        allowExpiryCommit.countDown();

        expiry.get(15, TimeUnit.SECONDS);
        pulse.get(15, TimeUnit.SECONDS);
        assertThat(fixture.jdbc.queryForMap("""
                SELECT lifecycle_state, revocation_origin
                  FROM prv_support_sessions WHERE support_session_id = ?
                """, grant.sessionId()))
                .containsEntry("lifecycle_state", "REVOKED")
                .containsEntry("revocation_origin", "AUTOMATIC_AUTHORITY_CONTAINMENT");
    }

    @Test
    void killSwitchFirstThenOperatorRetirementRemainsBoundedAndFailClosed()
            throws Exception {
        fixture.enableActivation();
        long ownerId = fixture.newOwner(990573, "Concurrent kill switch owner");
        var pendingRequestId = fixture.insertPendingRequest(ownerId);
        var grant = fixture.insertActiveGrant(ownerId);
        CountDownLatch killSwitchApplied = new CountDownLatch(1);
        CountDownLatch allowKillSwitchCommit = new CountDownLatch(1);
        CountDownLatch retirementReady = new CountDownLatch(1);
        AtomicInteger retirementPid = new AtomicInteger();

        Future<Void> killSwitch = executor.submit(() -> {
            try (Connection connection = fixture.dataSource.getConnection()) {
                beginBounded(connection);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE prv_support_activation_control
                           SET activation_enabled = FALSE,
                               change_reason = 'Concurrent bounded kill switch test',
                               change_correlation_id = 'test:concurrent-kill-switch',
                               changed_by = ?, version = version + 1
                         WHERE control_key = 'STANDARD_JIT'
                           AND activation_enabled
                        """)) {
                    statement.setLong(1, fixture.seededAdminId());
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
                killSwitchApplied.countDown();
                await(allowKillSwitchCommit);
                connection.commit();
            }
            return null;
        });
        await(killSwitchApplied);

        Future<Void> retirement = executor.submit(() -> {
            try (Connection connection = fixture.dataSource.getConnection()) {
                beginBounded(connection);
                retirementPid.set(backendPid(connection));
                retirementReady.countDown();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE prv_operators SET lifecycle_state = 'SUSPENDED'
                         WHERE provider_operator_id = ?
                        """)) {
                    statement.setLong(1, ownerId);
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
                connection.commit();
            }
            return null;
        });
        await(retirementReady);
        awaitDatabaseLockWait(retirementPid.get());
        allowKillSwitchCommit.countDown();

        killSwitch.get(15, TimeUnit.SECONDS);
        retirement.get(15, TimeUnit.SECONDS);
        assertThat(fixture.jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_support_sessions
                 WHERE support_session_id = ?
                """, String.class, grant.sessionId())).isEqualTo("REVOKED");
        assertThat(fixture.jdbc.queryForMap("""
                SELECT lifecycle_state, cancellation_origin
                  FROM prv_support_access_requests
                 WHERE support_access_request_id = ?
                """, pendingRequestId))
                .containsEntry("lifecycle_state", "CANCELLED")
                .containsEntry("cancellation_origin", "AUTOMATIC_OPERATOR_CONTAINMENT");
        assertThat(fixture.jdbc.queryForObject("""
                SELECT activation_enabled FROM prv_support_activation_control
                 WHERE control_key = 'STANDARD_JIT'
                """, Boolean.class)).isFalse();
    }

    @Test
    void operatorRetirementFirstThenKillSwitchRemainsBoundedAndFailClosed()
            throws Exception {
        fixture.enableActivation();
        long ownerId = fixture.newOwner(990575, "Retirement first kill switch owner");
        var pendingRequestId = fixture.insertPendingRequest(ownerId);
        var grant = fixture.insertActiveGrant(ownerId);
        CountDownLatch retirementApplied = new CountDownLatch(1);
        CountDownLatch allowRetirementCommit = new CountDownLatch(1);
        CountDownLatch killSwitchReady = new CountDownLatch(1);
        AtomicInteger killSwitchPid = new AtomicInteger();

        Future<Void> retirement = executor.submit(() -> {
            try (Connection connection = fixture.dataSource.getConnection()) {
                beginBounded(connection);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE prv_operators SET lifecycle_state = 'SUSPENDED'
                         WHERE provider_operator_id = ?
                        """)) {
                    statement.setLong(1, ownerId);
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
                retirementApplied.countDown();
                await(allowRetirementCommit);
                connection.commit();
            }
            return null;
        });
        await(retirementApplied);

        Future<Void> killSwitch = executor.submit(() -> {
            try (Connection connection = fixture.dataSource.getConnection()) {
                beginBounded(connection);
                killSwitchPid.set(backendPid(connection));
                killSwitchReady.countDown();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE prv_support_activation_control
                           SET activation_enabled = FALSE,
                               change_reason = 'Retirement first bounded kill switch test',
                               change_correlation_id = 'test:retirement-first-kill-switch',
                               changed_by = ?, version = version + 1
                         WHERE control_key = 'STANDARD_JIT'
                           AND activation_enabled
                        """)) {
                    statement.setLong(1, fixture.seededAdminId());
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
                connection.commit();
            }
            return null;
        });
        await(killSwitchReady);
        awaitDatabaseLockWait(killSwitchPid.get());
        allowRetirementCommit.countDown();

        retirement.get(15, TimeUnit.SECONDS);
        killSwitch.get(15, TimeUnit.SECONDS);
        assertThat(fixture.jdbc.queryForMap("""
                SELECT lifecycle_state, cancellation_origin
                  FROM prv_support_access_requests WHERE support_access_request_id = ?
                """, pendingRequestId))
                .containsEntry("lifecycle_state", "CANCELLED")
                .containsEntry("cancellation_origin", "AUTOMATIC_OPERATOR_CONTAINMENT");
        assertThat(fixture.jdbc.queryForMap("""
                SELECT lifecycle_state, revocation_origin
                  FROM prv_support_sessions WHERE support_session_id = ?
                """, grant.sessionId()))
                .containsEntry("lifecycle_state", "REVOKED")
                .containsEntry("revocation_origin", "AUTOMATIC_OPERATOR_CONTAINMENT");
        assertThat(fixture.jdbc.queryForObject("""
                SELECT activation_enabled FROM prv_support_activation_control
                 WHERE control_key = 'STANDARD_JIT'
                """, Boolean.class)).isFalse();
    }

    private static void beginBounded(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL statement_timeout = '10s'");
        }
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT pg_backend_pid()")) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private static String deepestMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return String.valueOf(current.getMessage());
    }

    private static void activateSession(
            Connection connection,
            UUID requestId,
            long ownerId,
            UUID sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH inserted_session AS (
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
                           statement_timestamp()
                               + make_interval(mins => request.duration_minutes),
                           statement_timestamp(), request.access_mode,
                           request.approval_reference,
                           request.customer_approval_required, request.risk_tier,
                           request.requester_auth_session_id,
                           request.requester_operator_id,
                           request.requester_operator_id
                      FROM prv_support_access_requests request
                     WHERE request.support_access_request_id = ?
                       AND request.requester_operator_id = ?
                       AND request.lifecycle_state = 'APPROVED'
                    RETURNING support_session_id, support_access_request_id, started_at
                ), inserted_scope AS (
                    INSERT INTO prv_support_session_scopes (
                        support_session_id, scope_code)
                    SELECT session.support_session_id, 'TENANT_EXPERIENCE_PREVIEW'
                      FROM inserted_session session
                    RETURNING support_session_id
                )
                UPDATE prv_support_access_requests request
                   SET lifecycle_state = 'ACTIVATED',
                       activated_at = session.started_at,
                       updated_by = request.requester_operator_id,
                       version = request.version + 1
                  FROM inserted_session session, inserted_scope scope
                 WHERE request.support_access_request_id =
                       session.support_access_request_id
                   AND scope.support_session_id = session.support_session_id
                   AND request.lifecycle_state = 'APPROVED'
                """)) {
            statement.setObject(1, sessionId);
            statement.setString(2, UUID.randomUUID().toString().replace("-", "").repeat(2));
            statement.setObject(3, requestId);
            statement.setLong(4, ownerId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting the concurrent DB step", exception);
        }
    }

    private void awaitDatabaseLockWait(int backendPid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Boolean waiting = fixture.jdbc.query("""
                    SELECT activity.wait_event_type = 'Lock'
                           AND cardinality(pg_blocking_pids(activity.pid)) > 0
                           AND EXISTS (
                               SELECT 1
                                 FROM pg_locks blocker_lock
                                 JOIN pg_class relation
                                   ON relation.oid = blocker_lock.relation
                                WHERE blocker_lock.pid = ANY (
                                          pg_blocking_pids(activity.pid))
                                  AND blocker_lock.granted
                                  AND relation.relname =
                                      'prv_support_activation_control')
                      FROM pg_stat_activity activity
                     WHERE activity.pid = ?
                    """, resultSet -> resultSet.next() && resultSet.getBoolean(1), backendPid);
            if (Boolean.TRUE.equals(waiting)) {
                return;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(
                    TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("Concurrent DB session did not block on the control row");
    }
}
