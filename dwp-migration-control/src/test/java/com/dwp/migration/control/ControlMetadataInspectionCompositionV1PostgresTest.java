package com.dwp.migration.control;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import javax.sql.DataSource;

import com.dwp.core.database.authority.JdbcRuntimeMetadataPoolInspectionV1;
import com.dwp.core.database.authority.PrivilegeSurfaceCompilerV1;
import com.dwp.core.database.authority.RuntimeMetadataCatalogReadEvidenceJsonV1;
import com.dwp.core.database.authority.RuntimeMetadataCatalogReadEvidenceV1;
import com.dwp.core.database.authority.RuntimeMetadataPoolInspectionPortV1.NativeObservation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual frozen core consumer, only this container's roles/databases changed. Baseline expectations
 * are independently assembled TEST_ONLY before Control/mutations, not production approvals.
 * The recording wrapper delegates flags unchanged; it never prepares readOnly or switches role.
 * No signed issuer/current vector, Main hook, serving restoration admission or app wiring exists here.
 */
@Testcontainers(disabledWithoutDocker = false)
class ControlMetadataInspectionCompositionV1PostgresTest {
    private static final String ADMIN = "composition_admin";
    private static final String MIGRATION = "composition_migration";
    private static final String RUNTIME = "composition_runtime";
    private static final String METADATA = "dwp_provider_metadata_auth";
    private static final String DATABASE = "dwp_auth_inspection";
    private static final String PASSWORD = "owned-composition-fixture-password";
    private static final String GENERATION = "b".repeat(64);
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault("DWP_CONTROL_POSTGRES_TEST_IMAGE", "postgres:16-alpine"))
            .withDatabaseName("composition_bootstrap").withUsername(ADMIN).withPassword(PASSWORD);
    private ControlEnvironment environment;
    private RuntimeMetadataCatalogReadEvidenceV1.ExpectedSource approval;
    private PrivilegeSurfaceCompilerV1.Policy policy;

    @BeforeEach
    void independentlyPrepareOwnedBaselineBeforeAnyControlOrMutation() throws Exception {
        try (Connection bootstrap = POSTGRES.createConnection("")) {
            sql(bootstrap, "DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            // Foreign CONNECT is cluster ACL state; clear only our fixture role before dropping it.
            sql(bootstrap, "DO $fixture$ BEGIN IF EXISTS(SELECT 1 FROM pg_catalog.pg_roles WHERE rolname='"
                    + METADATA + "') THEN REVOKE ALL ON DATABASE postgres,template1,composition_bootstrap FROM "
                    + METADATA + "; END IF; END $fixture$");
            for (String role : List.of(MIGRATION, RUNTIME, METADATA, "composition_scope")) {
                sql(bootstrap, "DROP ROLE IF EXISTS " + role);
            }
            sql(bootstrap, "CREATE ROLE composition_scope NOLOGIN NOINHERIT");
            for (String role : List.of(MIGRATION, RUNTIME, METADATA)) {
                sql(bootstrap, "CREATE ROLE " + role + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE "
                        + "NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '" + PASSWORD + "'");
            }
            sql(bootstrap, "CREATE DATABASE " + DATABASE + " OWNER " + ADMIN);
            for (String catalog : List.of("postgres", "template1", POSTGRES.getDatabaseName())) {
                sql(bootstrap, "REVOKE CONNECT,TEMPORARY ON DATABASE " + catalog + " FROM PUBLIC");
            }
        }
        String url = POSTGRES.getJdbcUrl().replace("/composition_bootstrap", "/" + DATABASE);
        environment = new ControlEnvironment(ControlEnvironment.Mode.STRICT_FRESH,
                ControlPlan.forService("auth"), url, DATABASE, ADMIN, PASSWORD,
                MIGRATION, PASSWORD, RUNTIME, PASSWORD, "dwp-migration-control-v2:" + "a".repeat(64),
                Map.of(), "", Map.of(), "");
        policy = new PrivilegeSurfaceCompilerV1.Policy(PrivilegeSurfaceCompilerV1.VERSION,
                List.of(new PrivilegeSurfaceCompilerV1.HistoryRelation("auth-main", DATABASE,
                        "public", "flyway_schema_history")));
        try (Connection control = admin()) {
            sql(control, "REVOKE ALL ON DATABASE " + DATABASE + " FROM PUBLIC");
            sql(control, "GRANT CONNECT ON DATABASE " + DATABASE + " TO " + MIGRATION + "," + RUNTIME + "," + METADATA);
            sql(control, "REVOKE ALL ON SCHEMA public FROM PUBLIC");
            sql(control, "GRANT USAGE,CREATE ON SCHEMA public TO " + MIGRATION);
            sql(control, "GRANT USAGE ON SCHEMA public TO " + RUNTIME);
            for (String role : List.of(MIGRATION, RUNTIME)) {
                sql(control, "ALTER ROLE " + role + " IN DATABASE " + DATABASE + " SET search_path TO pg_catalog,public");
            }
            sql(control, "ALTER ROLE " + METADATA + " IN DATABASE " + DATABASE + " SET search_path TO pg_catalog");
            sql(control, "CREATE TABLE public.flyway_schema_history(installed_rank integer PRIMARY KEY,secret text)");
            sql(control, "INSERT INTO public.flyway_schema_history VALUES(1,'never-read-history-rows')");
            sql(control, "ALTER TABLE public.flyway_schema_history OWNER TO " + MIGRATION);
            sql(control, "CREATE TABLE public.domain_rows(id bigint,payload text)");
            sql(control, "GRANT SELECT,INSERT,UPDATE,DELETE ON public.domain_rows TO " + RUNTIME);
            sql(control, "CREATE SEQUENCE public.domain_sequence");
            sql(control, "CREATE TYPE public.domain_code AS ENUM('READY','CLOSED')");
            sql(control, "CREATE FUNCTION public.secret_reader() RETURNS text LANGUAGE sql SECURITY DEFINER "
                    + "SET search_path=pg_catalog AS 'SELECT ''not-readable''::text'");
            sql(control, "REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC");
            var compiler = new PrivilegeSurfaceCompilerV1();
            String surface = compiler.compile(policy, compiler.capture(control, policy, DATABASE, List.of(METADATA)));
            try (var statement = control.createStatement(); var result = statement.executeQuery(
                    "SELECT pg_catalog.host(pg_catalog.inet_server_addr()),pg_catalog.inet_server_port(),"
                    + "pg_catalog.current_setting('server_version')")) {
                result.next();
                approval = new RuntimeMetadataCatalogReadEvidenceV1.ExpectedSource("auth", "auth", "authMetadataPool",
                        METADATA, DATABASE, "owned-composition-postgres", result.getString(1), result.getInt(2),
                        result.getString(3).split("\\s+", 2)[0], true, true, List.of("pg_catalog"),
                        PrivilegeSurfaceCompilerV1.VERSION,
                        RuntimeMetadataCatalogReadEvidenceJsonV1.historyPolicySha256(policy), surface);
            }
        }
        // Validate fixture privileges independently, so a baseline error is not called a bridge bug.
        RecordingPool baseline = new RecordingPool(new FixturePool(METADATA, true));
        assertTrue(inspector(baseline).inspect(approval, baseline).isPresent(), "TEST_ONLY baseline must be healthy");
    }

    @Test
    void actualFrozenConsumerObservesMetadataAndRestoresOriginalPurposePosture() throws Exception {
        try (Connection control = admin()) {
            ControlCredentials credentials = active(control);
            Observed result = compose(control, credentials);
            requireActive(control); // First failing assertion is after successful fence restoration.
            assertTrue(result.proof().isPresent(), "actual frozen metadata consumer must return NativeObservation.present; initial readOnly="
                    + result.pool().initialReadOnly);
            assertTrue(result.pool().initialReadOnly, "metadata original pool must already be readOnly");
            NativeObservation observation = result.proof().orElseThrow();
            assertEquals(METADATA, observation.originalJdbcLogin());
            assertEquals(METADATA, observation.currentUser());
            assertEquals(METADATA, observation.sessionUser());
            assertEquals(approval.privilegeSurfaceSha256(), observation.privilegeSurfaceSha256());
            assertRestored(result.pool());
            ServiceConnectionControl.restoreServiceConnections(control, environment, credentials);
        }
    }

    @Test
    void wrongRegisteredDataSourceIsRejectedWithoutOpeningItAndCannotReplaceActualPool() throws Exception {
        try (Connection control = admin()) {
            ControlCredentials credentials = active(control);
            AtomicInteger wrongOpens = new AtomicInteger();
            Optional<NativeObservation>[] wrong = optionalArray();
            Observed result = ControlInspectionFenceBridgeV1.inspect(control, environment, credentials, request(),
                    expected -> new ControlInspectionFenceBridgeV1.MetadataGeneration(expected, PASSWORD), original -> {
                        RecordingPool pool = new RecordingPool(original);
                        var actual = inspector(pool);
                        DataSource wrongPool = new FixturePool(METADATA, true) {
                            @Override public Connection getConnection() throws SQLException {
                                wrongOpens.incrementAndGet(); return super.getConnection();
                            }
                        };
                        wrong[0] = actual.inspect(approval, wrongPool);
                        return new Observed(actual.inspect(approval, pool), pool);
                    }).orElseThrow();
            requireActive(control); assertEquals(0, wrongOpens.get()); assertTrue(wrong[0].isEmpty());
            assertTrue(result.proof().isPresent()); assertRestored(result.pool());
            ServiceConnectionControl.restoreServiceConnections(control, environment, credentials);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreignConnect", "column", "function", "publicFunction", "defaultAcl", "grantOption", "sequence", "history", "catalogMutation"})
    void nativePrivilegesRejectPinnedIndependentExpectationAndStillRestoreActive(String kind) throws Exception {
        try (Connection control = admin()) {
            ControlCredentials credentials = active(control);
            sql(control, switch (kind) {
                case "foreignConnect" -> "GRANT CONNECT ON DATABASE postgres TO " + METADATA;
                case "column" -> "GRANT SELECT(payload) ON public.domain_rows TO " + METADATA;
                case "function" -> "GRANT EXECUTE ON FUNCTION public.secret_reader() TO " + METADATA;
                case "publicFunction" -> "GRANT EXECUTE ON FUNCTION public.secret_reader() TO PUBLIC";
                case "defaultAcl" -> "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO " + METADATA;
                case "grantOption" -> "GRANT USAGE ON TYPE public.domain_code TO " + METADATA + " WITH GRANT OPTION";
                case "sequence" -> "GRANT USAGE ON SEQUENCE public.domain_sequence TO " + METADATA;
                case "history" -> "GRANT SELECT ON public.flyway_schema_history TO " + METADATA;
                default -> "GRANT UPDATE ON pg_catalog.pg_class TO " + METADATA;
            });
            Observed result = compose(control, credentials);
            requireActive(control); assertTrue(result.pool().initialReadOnly);
            assertTrue(result.proof().isEmpty()); assertRestored(result.pool());
            if (kind.equals("catalogMutation")) {
                assertThrows(IllegalStateException.class,
                        () -> ServiceConnectionControl.restoreServiceConnections(control, environment, credentials));
                DatabaseConnectionFence.requireDatabaseAcl(control, environment, DatabaseConnectionFence.State.FAILED);
                DatabaseConnectionFence.requireNoForeignSessions(control, environment);
            } else {
                ServiceConnectionControl.restoreServiceConnections(control, environment, credentials);
            }
        }
    }

    @Test
    void membershipIsDeniedByExistingFenceBeforePoolOrSecretAccess() throws Exception {
        try (Connection control = admin()) {
            ControlCredentials credentials = active(control);
            sql(control, "GRANT composition_scope TO " + METADATA + " WITH INHERIT FALSE,SET TRUE");
            AtomicInteger secrets = new AtomicInteger(), opens = new AtomicInteger();
            assertThrows(IllegalStateException.class, () -> ControlInspectionFenceBridgeV1.inspect(
                    control, environment, credentials, request(), expected -> {
                        secrets.incrementAndGet(); return new ControlInspectionFenceBridgeV1.MetadataGeneration(expected, PASSWORD);
                    }, original -> { opens.incrementAndGet(); return "not-proof"; }));
            assertEquals(0, secrets.get()); assertEquals(0, opens.get()); requireActive(control);
            sql(control, "REVOKE composition_scope FROM " + METADATA);
            ServiceConnectionControl.restoreServiceConnections(control, environment, credentials);
        }
    }

    @Test
    void primaryRuntimeLabelCannotConstructMetadataApprovalAndDisabledSelectionOpensNothing() {
        assertThrows(IllegalStateException.class, () -> new RuntimeMetadataCatalogReadEvidenceV1.ExpectedSource(
                "auth", "auth", approval.qualifier(), RUNTIME, DATABASE, approval.trustedEndpointId(),
                approval.serverAddress(), approval.serverPort(), approval.postgresVersion(), true, true,
                List.of("pg_catalog"), approval.compilerVersion(), approval.historyPolicySha256(), approval.privilegeSurfaceSha256()));
        AtomicInteger secrets = new AtomicInteger(), opens = new AtomicInteger();
        assertTrue(ControlInspectionFenceBridgeV1.inspect(null, null, null,
                ControlInspectionFenceBridgeV1.Request.disabled(), expected -> { secrets.incrementAndGet(); return null; },
                pool -> { opens.incrementAndGet(); return "not-proof"; }).isEmpty());
        assertEquals(0, secrets.get()); assertEquals(0, opens.get());
    }

    @Test
    void runtimeProbeIsNotGeneralizedToMetadataReadOnlyPreparation() throws Exception {
        try (Connection control = admin()) {
            ControlCredentials credentials = active(control);
            boolean readOnly = ControlInspectionFenceBridgeV1.inspect(control, environment, credentials,
                    new ControlInspectionFenceBridgeV1.Request(true, RUNTIME, null), null, original -> {
                        try (Connection connection = original.getConnection()) { return connection.isReadOnly(); }
                    }).orElseThrow();
            requireActive(control); assertFalse(readOnly);
            ServiceConnectionControl.restoreServiceConnections(control, environment, credentials);
        }
    }

    private record Observed(Optional<NativeObservation> proof, RecordingPool pool) { }
    private Observed compose(Connection control, ControlCredentials credentials) {
        return ControlInspectionFenceBridgeV1.inspect(control, environment, credentials, request(),
                expected -> new ControlInspectionFenceBridgeV1.MetadataGeneration(expected, PASSWORD), original -> {
                    RecordingPool pool = new RecordingPool(original);
                    return new Observed(inspector(pool).inspect(approval, pool), pool);
                }).orElseThrow();
    }
    private JdbcRuntimeMetadataPoolInspectionV1 inspector(DataSource pool) {
        return new JdbcRuntimeMetadataPoolInspectionV1(List.of(
                new JdbcRuntimeMetadataPoolInspectionV1.Binding(approval, pool, policy)));
    }
    private static ControlInspectionFenceBridgeV1.Request request() {
        return new ControlInspectionFenceBridgeV1.Request(true, METADATA, GENERATION);
    }
    private ControlCredentials active(Connection control) throws Exception {
        DatabaseControl.acquireExclusiveControlLock(control, environment);
        return ServiceConnectionControl.activateExclusiveControlFence(control, environment);
    }
    private void requireActive(Connection control) throws Exception {
        DatabaseConnectionFence.requireDatabaseAcl(control, environment, DatabaseConnectionFence.State.ACTIVE);
        DatabaseConnectionFence.requireNoForeignSessions(control, environment);
    }
    private Connection admin() throws SQLException { return fixtureLogin(ADMIN, false); }
    private Connection fixtureLogin(String role, boolean readOnly) throws SQLException {
        Properties properties = new Properties(); properties.setProperty("user", role);
        properties.setProperty("password", PASSWORD); properties.setProperty("readOnly", Boolean.toString(readOnly));
        properties.setProperty("connectTimeout", "5"); properties.setProperty("socketTimeout", "10");
        return DriverManager.getConnection(environment.jdbcUrl(), properties);
    }
    private static void sql(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private static void assertRestored(RecordingPool pool) {
        assertEquals(1, pool.opens); assertTrue(pool.closed); assertTrue(pool.finalAutoCommit);
        assertTrue(pool.finalReadOnly); assertEquals(pool.initialIsolation, pool.finalIsolation);
        assertEquals(pool.initialNetwork, pool.finalNetwork); assertEquals(0, pool.readOnlySetters);
        assertTrue(pool.sql.stream().allMatch(s -> s.stripLeading().startsWith("SELECT") || s.stripLeading().startsWith("WITH")));
        assertTrue(pool.sql.stream().noneMatch(s -> s.matches("(?is).*FROM\\s+(public\\.)?flyway_schema_history(?:\\s|;).*")));
    }
    @SuppressWarnings("unchecked")
    private static Optional<NativeObservation>[] optionalArray() { return (Optional<NativeObservation>[]) new Optional<?>[1]; }

    private abstract static class MinimalPool implements DataSource {
        @Override public Connection getConnection(String user, String password) throws SQLException { throw new SQLFeatureNotSupportedException(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter writer) throws SQLException { throw new SQLFeatureNotSupportedException(); }
        @Override public int getLoginTimeout() { return 5; }
        @Override public void setLoginTimeout(int seconds) throws SQLException { throw new SQLFeatureNotSupportedException(); }
        @Override public Logger getParentLogger() { return Logger.getLogger("test-only-composition"); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLFeatureNotSupportedException(); }
        @Override public boolean isWrapperFor(Class<?> type) { return false; }
    }
    private class FixturePool extends MinimalPool {
        private final String role; private final boolean readOnly;
        FixturePool(String role, boolean readOnly) { this.role = role; this.readOnly = readOnly; }
        @Override public Connection getConnection() throws SQLException { return fixtureLogin(role, readOnly); }
    }
    private static final class RecordingPool extends MinimalPool {
        private final DataSource delegate; private int opens, readOnlySetters;
        private boolean initialReadOnly, finalReadOnly, finalAutoCommit, closed;
        private int initialIsolation, finalIsolation, initialNetwork, finalNetwork;
        private final List<String> sql = new ArrayList<>();
        RecordingPool(DataSource delegate) { this.delegate = delegate; }
        @Override public Connection getConnection() throws SQLException {
            opens++; Connection actual = delegate.getConnection();
            initialReadOnly = actual.isReadOnly(); initialIsolation = actual.getTransactionIsolation();
            initialNetwork = actual.getNetworkTimeout();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("setReadOnly")) readOnlySetters++;
                        if (method.getName().equals("prepareStatement") && arguments[0] instanceof String query) sql.add(query);
                        if (method.getName().equals("close") && !actual.isClosed()) {
                            finalReadOnly = actual.isReadOnly(); finalAutoCommit = actual.getAutoCommit();
                            finalIsolation = actual.getTransactionIsolation(); finalNetwork = actual.getNetworkTimeout(); closed = true;
                        }
                        try { return method.invoke(actual, arguments); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
    }
}
