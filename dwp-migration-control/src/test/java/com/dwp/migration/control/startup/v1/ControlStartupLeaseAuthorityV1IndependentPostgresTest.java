package com.dwp.migration.control.startup.v1;

import com.dwp.core.database.authority.RuntimeStartupFreshnessPort.Activation;
import com.dwp.core.database.authority.RuntimeStartupFreshnessPort.Reservation;
import com.dwp.core.database.authority.RuntimeStartupSealJson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.*;
import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Independent disposable Control catalog; explicit native SELECT/row locks, no production bootstrap/transport/approval publication. */
@Testcontainers
class ControlStartupLeaseAuthorityV1IndependentPostgresTest {
    static final Instant NOW=Instant.parse("2026-09-14T05:00:00Z");
    static final String ROLE="independent_startup_authority";
    static final String PASSWORD=UUID.randomUUID().toString();
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(image())
            .withDatabaseName("independent_startup_catalog").withReuse(false);
    static KeyPair key;

    static String image() {
        String value=System.getenv().getOrDefault("DWP_CONTROL_POSTGRES_TEST_IMAGE","postgres:16-alpine");
        if(!List.of("postgres:16-alpine","postgres:18.4-alpine").contains(value)) throw new IllegalStateException("unapproved owned test image");
        return value;
    }

    @BeforeAll static void ownedNativeSchemaAndLeastDmlRole() throws Exception {
        key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        try(Connection connection=admin()) {
            try(var password=connection.prepareStatement("SELECT set_config('dwp.test_password',?,false)")) { password.setString(1,PASSWORD); password.execute(); }
            try(var sql=connection.createStatement()) {
                sql.execute("""
                        DO $role$ BEGIN EXECUTE format('CREATE ROLE independent_startup_authority LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
                        current_setting('dwp.test_password')); END $role$;
                        """);
                sql.execute("CREATE SCHEMA dwp_deployment_startup");
                sql.execute("REVOKE ALL ON SCHEMA dwp_deployment_startup FROM PUBLIC");
                sql.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
                sql.execute("REVOKE CREATE,TEMPORARY ON DATABASE "+POSTGRES.getDatabaseName()+" FROM PUBLIC");
                sql.execute("CREATE TABLE dwp_deployment_startup.current_deployments(service text NOT NULL,deployment_id uuid NOT NULL,epoch bigint NOT NULL CHECK(epoch>0),key_revision bigint NOT NULL CHECK(key_revision>0),fence_state text NOT NULL CHECK(fence_state IN ('SERVING','DRAINING')),PRIMARY KEY(service,deployment_id))");
                sql.execute("CREATE TABLE dwp_deployment_startup.startup_permits(permit_id uuid PRIMARY KEY,service text NOT NULL,deployment_id uuid NOT NULL,application_instance_id uuid NOT NULL,epoch bigint NOT NULL CHECK(epoch>0),startup_challenge_sha256 text NOT NULL,seal_sha256 text NOT NULL,phase text NOT NULL CHECK(phase IN ('ISSUED','RESERVED','CONSUMED','REVOKED')),not_before timestamptz NOT NULL,expires_at timestamptz NOT NULL,CHECK(not_before<expires_at),UNIQUE(service,deployment_id,application_instance_id,epoch),FOREIGN KEY(service,deployment_id) REFERENCES dwp_deployment_startup.current_deployments)");
                sql.execute("CREATE TABLE dwp_deployment_startup.startup_leases(permit_id uuid PRIMARY KEY REFERENCES dwp_deployment_startup.startup_permits,lease_id uuid NOT NULL UNIQUE,phase text NOT NULL CHECK(phase IN ('RESERVED','ACTIVE','RELEASED')),issued_at timestamptz NOT NULL,expires_at timestamptz NOT NULL,CHECK(issued_at<expires_at))");
                sql.execute("GRANT USAGE ON SCHEMA dwp_deployment_startup TO "+ROLE);
                sql.execute("GRANT SELECT ON ALL TABLES IN SCHEMA dwp_deployment_startup TO "+ROLE);
                sql.execute("GRANT UPDATE(epoch,fence_state) ON dwp_deployment_startup.current_deployments TO "+ROLE);
                sql.execute("GRANT UPDATE(phase) ON dwp_deployment_startup.startup_permits TO "+ROLE);
                sql.execute("GRANT INSERT,UPDATE(phase,issued_at,expires_at) ON dwp_deployment_startup.startup_leases TO "+ROLE);
                sql.execute("CREATE SCHEMA startup_shadow_fixture");
                sql.execute("CREATE FUNCTION startup_shadow_fixture.current_database() RETURNS name LANGUAGE SQL IMMUTABLE AS 'SELECT ''forged_startup_catalog''::name'");
                sql.execute("GRANT USAGE ON SCHEMA startup_shadow_fixture TO "+ROLE);
            }
        }
    }

    @Test void searchPathRoutineCannotSpoofPhysicalConfiguredControlCatalog() throws Exception {
        Fixture f=new Fixture();
        DataSource contaminated=f.pool(true);
        try(Connection c=contaminated.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT current_database(),pg_catalog.current_database()")) {
            assertTrue(r.next()); assertEquals("forged_startup_catalog",r.getString(1)); assertEquals(POSTGRES.getDatabaseName(),r.getString(2));
        }
        var authority=f.authority(contaminated,"forged_startup_catalog",ROLE,f.clock);
        var rejected=assertThrows(IllegalStateException.class,() -> authority.reserve(f.request));
        assertEquals(ControlStartupLeaseAuthorityV1IndependentRegressionTest.GENERIC,rejected.getMessage());
        assertNull(rejected.getCause()); assertEquals(0,f.countLeases()); assertEquals("ISSUED",f.permitPhase());
    }

    @Test void persistedIssuedAtRejectsClockRegressionAcrossAuthorityCalls() throws Exception {
        Fixture f=new Fixture(); var authority=f.authority(f.pool(false),POSTGRES.getDatabaseName(),ROLE,f.clock);
        var lease=RuntimeStartupSealJson.lease(authority.reserve(f.request).orElseThrow());
        f.now.set(NOW.plusSeconds(2));
        Activation a=new Activation(f.request,lease.claims().leaseId());
        var active=RuntimeStartupSealJson.lease(authority.activate(a).orElseThrow());
        assertEquals(NOW.plusSeconds(2).toString(),active.claims().issuedAt());
        f.now.set(NOW.plusSeconds(1));
        assertThrows(IllegalStateException.class,() -> authority.current(a));
        assertEquals(1,f.countLeases()); assertEquals("CONSUMED",f.permitPhase());
    }

    enum PrecisionOperation { RESERVE_RETRY,ACTIVE_CURRENT }

    @ParameterizedTest(name="{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(PrecisionOperation.class)
    void nanosecondClockMustRoundTripToIdenticalCanonicalNativeLease(PrecisionOperation operation) throws Exception {
        Fixture f=new Fixture();
        f.now.set(NOW.plusNanos(123456789));
        var authority=f.authority(f.pool(false),POSTGRES.getDatabaseName(),ROLE,f.clock);
        String reserved=authority.reserve(f.request).orElseThrow();
        if(operation==PrecisionOperation.RESERVE_RETRY) {
            assertEquals(reserved,authority.reserve(f.request).orElseThrow());
        } else {
            var lease=RuntimeStartupSealJson.lease(reserved);
            f.now.set(NOW.plusSeconds(2).plusNanos(987654321));
            Activation activation=new Activation(f.request,lease.claims().leaseId());
            String active=authority.activate(activation).orElseThrow();
            f.now.set(NOW.plusSeconds(3));
            assertEquals(active,authority.current(activation).orElseThrow());
        }
        assertEquals(1,f.countLeases());
    }

    @Test void expiryDuringSigningRollsBackLeaseAndPermitChangesTogether() throws Exception {
        Fixture f=new Fixture();
        Clock advancing=new Clock() {
            int reads;
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId z) { return this; }
            public Instant instant() { return ++reads==3 ? NOW.plusSeconds(10) : NOW; }
        };
        var authority=f.authority(f.pool(false),POSTGRES.getDatabaseName(),ROLE,advancing);
        assertThrows(IllegalStateException.class,() -> authority.reserve(f.request));
        assertEquals(0,f.countLeases()); assertEquals("ISSUED",f.permitPhase());
    }

    @Test void nativeOriginalLoginMismatchRejectsBeforeAnyLeaseMutation() throws Exception {
        Fixture f=new Fixture();
        var authority=f.authority(f.pool(false),POSTGRES.getDatabaseName(),"wrong_native_control_login",f.clock);
        assertThrows(IllegalStateException.class,() -> authority.reserve(f.request));
        assertEquals(0,f.countLeases()); assertEquals("ISSUED",f.permitPhase());
    }

    @Test void activationAndDrainRaceAlwaysLeavesDurableEpochAdvancedAndOldCurrentDenied() throws Exception {
        Fixture f=new Fixture(); var authority=f.authority(f.pool(false),POSTGRES.getDatabaseName(),ROLE,f.clock);
        var reserved=RuntimeStartupSealJson.lease(authority.reserve(f.request).orElseThrow());
        Activation request=new Activation(f.request,reserved.claims().leaseId());
        try(var executor=Executors.newFixedThreadPool(2)) {
            var activation=executor.submit(() -> {
                try { authority.activate(request); return true; }
                catch(IllegalStateException drainWon) { return false; }
            });
            var drain=executor.submit(() -> authority.beginDrain(f.request.service(),f.request.deploymentId(),1));
            assertNotNull(activation.get(15,TimeUnit.SECONDS));
            assertEquals(2L,drain.get(15,TimeUnit.SECONDS));
        }
        try(Connection c=admin();var s=c.prepareStatement("SELECT epoch,fence_state FROM dwp_deployment_startup.current_deployments WHERE service=? AND deployment_id=?")) {
            s.setString(1,f.request.service()); s.setObject(2,UUID.fromString(f.request.deploymentId()));
            try(var r=s.executeQuery()) { assertTrue(r.next()); assertEquals(2,r.getLong(1)); assertEquals("DRAINING",r.getString(2)); }
        }
        assertEquals("REVOKED",f.permitPhase()); assertEquals(1,f.countLeases());
        assertThrows(IllegalStateException.class,() -> authority.current(request));
        assertThrows(IllegalStateException.class,() -> authority.activate(request));
        // This checks durable linearization, not revocation of an in-flight signed response or native session-offline fencing.
    }

    static Connection admin() throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()); }
    static class Fixture {
        final Reservation request=new Reservation("auth",UUID.randomUUID().toString(),UUID.randomUUID().toString(),1,UUID.randomUUID().toString(),"a".repeat(64),"b".repeat(64));
        final AtomicReference<Instant> now=new AtomicReference<>(NOW);
        final Clock clock=new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        Fixture() throws SQLException {
            try(Connection c=admin();var d=c.prepareStatement("INSERT INTO dwp_deployment_startup.current_deployments VALUES(?,?,1,1,'SERVING')");
                    var p=c.prepareStatement("INSERT INTO dwp_deployment_startup.startup_permits VALUES(?,?,?,?,1,?,?,'ISSUED',?,?)")) {
                d.setString(1,request.service()); d.setObject(2,UUID.fromString(request.deploymentId())); d.executeUpdate();
                p.setObject(1,UUID.fromString(request.permitId())); p.setString(2,request.service()); p.setObject(3,UUID.fromString(request.deploymentId()));
                p.setObject(4,UUID.fromString(request.applicationInstanceId())); p.setString(5,request.startupChallengeSha256()); p.setString(6,request.sealSha256());
                p.setObject(7,NOW.minusSeconds(120).atOffset(ZoneOffset.UTC)); p.setObject(8,NOW.plusSeconds(120).atOffset(ZoneOffset.UTC)); p.executeUpdate();
            }
        }
        DataSource pool(boolean contaminated) {
            return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(proxy,method,args) -> {
                if(method.getName().equals("getConnection") && (args==null || args.length==0)) {
                    Connection c=DriverManager.getConnection(POSTGRES.getJdbcUrl(),ROLE,PASSWORD);
                    if(contaminated) try(var s=c.createStatement()) { s.execute("SET search_path TO startup_shadow_fixture,pg_catalog"); }
                    return c;
                }
                throw new UnsupportedOperationException("no other independent pool call permitted");
            });
        }
        ControlStartupLeaseAuthorityV1 authority(DataSource pool,String catalog,String principal,Clock clock) {
            return new ControlStartupLeaseAuthorityV1(pool,catalog,principal,() -> Optional.of(new ControlStartupLeaseAuthorityV1.Invocation(
                    request.service(),request.deploymentId(),request.applicationInstanceId(),request.startupChallengeSha256())),
                    new ControlStartupLeaseSignerV1("independent-key",1,key.getPrivate(),key.getPublic()),clock,Duration.ofSeconds(10));
        }
        long countLeases() throws SQLException {
            try(Connection c=admin();var s=c.prepareStatement("SELECT count(*) FROM dwp_deployment_startup.startup_leases WHERE permit_id=?")) {
                s.setObject(1,UUID.fromString(request.permitId())); try(var r=s.executeQuery()) { assertTrue(r.next()); return r.getLong(1); }
            }
        }
        String permitPhase() throws SQLException {
            try(Connection c=admin();var s=c.prepareStatement("SELECT phase FROM dwp_deployment_startup.startup_permits WHERE permit_id=?")) {
                s.setObject(1,UUID.fromString(request.permitId())); try(var r=s.executeQuery()) { assertTrue(r.next()); return r.getString(1); }
            }
        }
    }
}
