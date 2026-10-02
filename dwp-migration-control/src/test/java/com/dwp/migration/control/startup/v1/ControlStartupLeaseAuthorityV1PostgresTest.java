package com.dwp.migration.control.startup.v1;

import java.io.PrintWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.stream.Stream;

import javax.sql.DataSource;

import com.dwp.core.database.authority.RuntimeStartupFreshnessPort.Activation;
import com.dwp.core.database.authority.RuntimeStartupFreshnessPort.Reservation;
import com.dwp.core.database.authority.RuntimeStartupSealJson;
import com.dwp.core.database.authority.RuntimeStartupSealSignatureVerifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/** Owned disposable native catalog only; no mocked JDBC/epoch. Transport remains explicit fixture. */
@Testcontainers
class ControlStartupLeaseAuthorityV1PostgresTest {
    private static final Instant NOW = Instant.parse("2026-09-14T05:00:00Z");
    @Container private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(image())
            .withDatabaseName("startup_control_fixture").withUsername("startup_fixture_bootstrap")
            .withPassword("isolated_startup_fixture_password").withReuse(false);
    private static KeyPair key;

    private static String image() {
        String image = System.getenv().getOrDefault("DWP_CONTROL_POSTGRES_TEST_IMAGE", "postgres:18.4-alpine");
        if (!List.of("postgres:16-alpine", "postgres:18.4-alpine").contains(image)) throw new IllegalStateException("unapproved test image");
        return image;
    }

    @BeforeAll static void provisionOwnedCatalog() throws Exception {
        key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        try (Connection connection = admin(); var sql = connection.createStatement()) {
            sql.execute("CREATE ROLE startup_fixture_authority LOGIN PASSWORD 'isolated_authority_password' "
                    + "NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            sql.execute("CREATE ROLE startup_fixture_runtime LOGIN PASSWORD 'isolated_runtime_password' "
                    + "NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            sql.execute("CREATE SCHEMA dwp_deployment_startup AUTHORIZATION startup_fixture_bootstrap");
            sql.execute("REVOKE ALL ON SCHEMA dwp_deployment_startup FROM PUBLIC");
            sql.execute("CREATE TABLE dwp_deployment_startup.current_deployments (service text NOT NULL, "
                    + "deployment_id uuid NOT NULL,epoch bigint NOT NULL CHECK(epoch>0),key_revision bigint NOT NULL CHECK(key_revision>0),"
                    + "fence_state text NOT NULL CHECK(fence_state IN ('SERVING','DRAINING')), PRIMARY KEY(service,deployment_id))");
            sql.execute("CREATE TABLE dwp_deployment_startup.startup_permits (permit_id uuid PRIMARY KEY,service text NOT NULL,"
                    + "deployment_id uuid NOT NULL,application_instance_id uuid NOT NULL,epoch bigint NOT NULL CHECK(epoch>0),"
                    + "startup_challenge_sha256 text NOT NULL CHECK(startup_challenge_sha256 ~ '^[0-9a-f]{64}$'),"
                    + "seal_sha256 text NOT NULL CHECK(seal_sha256 ~ '^[0-9a-f]{64}$'),phase text NOT NULL "
                    + "CHECK(phase IN ('ISSUED','RESERVED','CONSUMED','REVOKED')),not_before timestamptz NOT NULL,expires_at timestamptz NOT NULL,"
                    + "CHECK(not_before<expires_at),UNIQUE(service,deployment_id,application_instance_id,epoch),"
                    + "FOREIGN KEY(service,deployment_id) REFERENCES dwp_deployment_startup.current_deployments)");
            sql.execute("CREATE TABLE dwp_deployment_startup.startup_leases (permit_id uuid PRIMARY KEY REFERENCES "
                    + "dwp_deployment_startup.startup_permits,lease_id uuid NOT NULL UNIQUE,phase text NOT NULL "
                    + "CHECK(phase IN ('RESERVED','ACTIVE','RELEASED')),issued_at timestamptz NOT NULL,expires_at timestamptz NOT NULL,"
                    + "CHECK(issued_at<expires_at))");
            sql.execute("GRANT USAGE ON SCHEMA dwp_deployment_startup TO startup_fixture_authority");
            sql.execute("GRANT SELECT ON ALL TABLES IN SCHEMA dwp_deployment_startup TO startup_fixture_authority");
            sql.execute("GRANT UPDATE(epoch,fence_state) ON dwp_deployment_startup.current_deployments TO startup_fixture_authority");
            sql.execute("GRANT UPDATE(phase) ON dwp_deployment_startup.startup_permits TO startup_fixture_authority");
            sql.execute("GRANT INSERT,UPDATE(phase,issued_at,expires_at) ON dwp_deployment_startup.startup_leases TO startup_fixture_authority");
        }
    }

    @Test void nativeReservedActiveCurrentAreCanonicalSignedAndCurrentDoesNotRenew() throws Exception {
        Fixture f = new Fixture();
        String reserved = f.authority.reserve(f.request).orElseThrow();
        var initial = RuntimeStartupSealJson.lease(reserved);
        RuntimeStartupSealSignatureVerifier.verifyLease(initial, Map.of("fixture-key", key.getPublic()));
        assertEquals("RESERVED", initial.claims().phase());
        assertEquals(reserved, f.authority.reserve(f.request).orElseThrow());
        f.now.set(NOW.plusSeconds(2));
        Activation activate = new Activation(f.request, initial.claims().leaseId());
        String active = f.authority.activate(activate).orElseThrow();
        RuntimeStartupSealSignatureVerifier.verifyLease(RuntimeStartupSealJson.lease(active), Map.of("fixture-key", key.getPublic()));
        assertEquals("ACTIVE", RuntimeStartupSealJson.lease(active).claims().phase());
        f.now.set(NOW.plusSeconds(3));
        assertEquals(active, f.authority.current(activate).orElseThrow());
        assertThrows(IllegalStateException.class, () -> f.authority.activate(activate));
        assertThrows(IllegalStateException.class, () -> f.authority.reserve(f.request));
    }

    static Stream<String> mismatches() { return Stream.of("service", "deployment", "instance", "epoch", "permit", "challenge", "seal"); }
    @ParameterizedTest(name = "native approval binding rejects {0}") @MethodSource("mismatches")
    void exactNativeApprovalRejectsAnyChangedBinding(String kind) throws Exception {
        Fixture f = new Fixture(); Reservation r = f.request;
        Reservation wrong = new Reservation(kind.equals("service") ? "people" : r.service(),
                kind.equals("deployment") ? UUID.randomUUID().toString() : r.deploymentId(),
                kind.equals("instance") ? UUID.randomUUID().toString() : r.applicationInstanceId(),
                kind.equals("epoch") ? 2 : r.epoch(), kind.equals("permit") ? UUID.randomUUID().toString() : r.permitId(),
                kind.equals("challenge") ? "c".repeat(64) : r.startupChallengeSha256(), kind.equals("seal") ? "d".repeat(64) : r.sealSha256());
        assertThrows(IllegalStateException.class, () -> f.authority.reserve(wrong));
        assertEquals(0, f.countLeases());
    }

    @Test void missingAuthenticatedInvocationRejectsBeforeOpeningControlPool() throws Exception {
        Fixture f = new Fixture(); f.invocation.set(null);
        int opened = f.pool.opens.get();
        assertThrows(IllegalStateException.class, () -> f.authority.reserve(f.request));
        assertEquals(opened, f.pool.opens.get());
    }

    @Test void crashRestartUsesNativeEpochAndDrainRevokesOldReservationAndActiveLease() throws Exception {
        Fixture f = new Fixture();
        var lease = RuntimeStartupSealJson.lease(f.authority.reserve(f.request).orElseThrow());
        Activation activate = new Activation(f.request, lease.claims().leaseId());
        f.authority.activate(activate);
        assertEquals(2, f.newAuthority().beginDrain(f.request.service(), f.request.deploymentId(), 1));
        assertThrows(IllegalStateException.class, () -> f.newAuthority().current(activate));
        assertThrows(IllegalStateException.class, () -> f.newAuthority().activate(activate));
        assertThrows(IllegalStateException.class, () -> f.newAuthority().reserve(f.request));
        assertThrows(IllegalStateException.class, () -> f.newAuthority().beginDrain(f.request.service(), f.request.deploymentId(), 1));
    }

    @Test void concurrentReservationsLinearizeAndMintOnlyOneNativeLease() throws Exception {
        Fixture f = new Fixture();
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<String> reserve = () -> RuntimeStartupSealJson.lease(f.newAuthority().reserve(f.request).orElseThrow()).claims().leaseId();
            var results = executor.invokeAll(List.of(reserve, reserve));
            assertEquals(results.get(0).get(), results.get(1).get()); assertEquals(1, f.countLeases());
        }
    }

    @Test void releasedPermitCannotMintASecondLease() throws Exception {
        Fixture f = new Fixture();
        var lease = RuntimeStartupSealJson.lease(f.authority.reserve(f.request).orElseThrow());
        f.authority.release(f.request, lease.claims().leaseId());
        assertThrows(IllegalStateException.class, () -> f.authority.reserve(f.request)); assertEquals(1, f.countLeases());
    }

    @Test void exactExpiryRejectsAndNeverRenewsExpiredActiveLease() throws Exception {
        Fixture f = new Fixture();
        var lease = RuntimeStartupSealJson.lease(f.authority.reserve(f.request).orElseThrow());
        Activation a = new Activation(f.request, lease.claims().leaseId());
        f.authority.activate(a); f.now.set(NOW.plusSeconds(10));
        assertThrows(IllegalStateException.class, () -> f.authority.current(a));
        assertThrows(IllegalStateException.class, () -> f.authority.activate(a));
    }

    @Test void authorityCannotPublishOrRebindPermitsAndRuntimeCannotReadApprovals() throws Exception {
        Fixture f = new Fixture();
        try (Connection c = f.pool.getConnection(); var s = c.createStatement()) {
            for (String sql : List.of("INSERT INTO dwp_deployment_startup.startup_permits(permit_id) VALUES(gen_random_uuid())",
                    "UPDATE dwp_deployment_startup.startup_permits SET seal_sha256='" + "a".repeat(64) + "'",
                    "ALTER TABLE dwp_deployment_startup.startup_permits ADD COLUMN unsafe int")) {
                assertEquals("42501", assertThrows(SQLException.class, () -> s.execute(sql)).getSQLState());
            }
        }
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "startup_fixture_runtime", "isolated_runtime_password");
                var s = c.createStatement()) {
            assertEquals("42501", assertThrows(SQLException.class,
                    () -> s.executeQuery("SELECT * FROM dwp_deployment_startup.startup_permits")).getSQLState());
        }
    }

    @Test void mismatchedSignerKeyPairAndWrongRevisionReject() throws Exception {
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertThrows(IllegalStateException.class,
                () -> new ControlStartupLeaseSignerV1("fixture-key", 1, key.getPrivate(), other.getPublic()));
        Fixture f = new Fixture();
        var wrongSigner = new ControlStartupLeaseSignerV1("fixture-key", 2, key.getPrivate(), key.getPublic());
        var wrong = new ControlStartupLeaseAuthorityV1(f.pool, POSTGRES.getDatabaseName(), "startup_fixture_authority",
                () -> Optional.ofNullable(f.invocation.get()), wrongSigner, f.clock, Duration.ofSeconds(10));
        assertThrows(IllegalStateException.class, () -> wrong.reserve(f.request)); assertEquals(0, f.countLeases());
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
    private static final class Fixture {
        final Reservation request = new Reservation("auth", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                1, UUID.randomUUID().toString(), "a".repeat(64), "b".repeat(64));
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        final AtomicReference<ControlStartupLeaseAuthorityV1.Invocation> invocation = new AtomicReference<>(
                new ControlStartupLeaseAuthorityV1.Invocation(request.service(), request.deploymentId(),
                        request.applicationInstanceId(), request.startupChallengeSha256()));
        final Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        final SqlPool pool = new SqlPool();
        final ControlStartupLeaseAuthorityV1 authority;
        Fixture() throws SQLException {
            try (Connection c = admin(); var deployment = c.prepareStatement(
                    "INSERT INTO dwp_deployment_startup.current_deployments VALUES(?,?,1,1,'SERVING')");
                    var permit = c.prepareStatement("INSERT INTO dwp_deployment_startup.startup_permits "
                            + "VALUES(?,?,?,?,1,?,?,'ISSUED',?,?)")) {
                deployment.setString(1, request.service()); deployment.setObject(2, UUID.fromString(request.deploymentId())); deployment.executeUpdate();
                permit.setObject(1, UUID.fromString(request.permitId())); permit.setString(2, request.service());
                permit.setObject(3, UUID.fromString(request.deploymentId())); permit.setObject(4, UUID.fromString(request.applicationInstanceId()));
                permit.setString(5, request.startupChallengeSha256()); permit.setString(6, request.sealSha256());
                permit.setObject(7, NOW.atOffset(ZoneOffset.UTC)); permit.setObject(8, NOW.plusSeconds(120).atOffset(ZoneOffset.UTC)); permit.executeUpdate();
            }
            authority = newAuthority();
        }
        ControlStartupLeaseAuthorityV1 newAuthority() {
            return new ControlStartupLeaseAuthorityV1(pool, POSTGRES.getDatabaseName(), "startup_fixture_authority",
                    () -> Optional.ofNullable(invocation.get()), new ControlStartupLeaseSignerV1("fixture-key", 1,
                    key.getPrivate(), key.getPublic()), clock, Duration.ofSeconds(10));
        }
        long countLeases() throws SQLException {
            try (Connection c = admin(); var s = c.prepareStatement("SELECT count(*) FROM dwp_deployment_startup.startup_leases WHERE permit_id=?")) {
                s.setObject(1, UUID.fromString(request.permitId())); try (var r = s.executeQuery()) { r.next(); return r.getLong(1); }
            }
        }
    }
    private static final class SqlPool implements DataSource {
        final AtomicInteger opens = new AtomicInteger();
        public Connection getConnection() throws SQLException { opens.incrementAndGet(); return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "startup_fixture_authority", "isolated_authority_password"); }
        public Connection getConnection(String username, String password) { throw new UnsupportedOperationException(); }
        public PrintWriter getLogWriter() { return null; }
        public void setLogWriter(PrintWriter writer) { throw new UnsupportedOperationException(); }
        public int getLoginTimeout() { return 5; }
        public void setLoginTimeout(int timeout) { throw new UnsupportedOperationException(); }
        public Logger getParentLogger() { return Logger.getLogger("isolated-startup-fixture"); }
        public <T> T unwrap(Class<T> type) { throw new UnsupportedOperationException(); }
        public boolean isWrapperFor(Class<?> type) { return false; }
    }
}
