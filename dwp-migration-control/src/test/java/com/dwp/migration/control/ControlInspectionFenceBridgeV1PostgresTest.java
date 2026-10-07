package com.dwp.migration.control;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Only this owned container's database/roles are changed. Credential registry and probe invocation
 * are explicit TEST seams, not published independent approval, current issuer, Spring wiring,
 * durable INSPECTION/lease publication or full own+metadata native proof.
 */
@Testcontainers(disabledWithoutDocker = false)
class ControlInspectionFenceBridgeV1PostgresTest {
    private static final String ADMIN = "inspection_admin";
    private static final String MIGRATION = "inspection_migration";
    private static final String RUNTIME = "inspection_runtime";
    private static final String METADATA = "dwp_provider_metadata_auth";
    private static final String PASSWORD = "owned-inspection-fixture-password";
    private static final String GENERATION = "b".repeat(64);
    private static final String DATABASE = "inspection_target";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault("DWP_CONTROL_POSTGRES_TEST_IMAGE", "postgres:16-alpine"))
            .withDatabaseName("inspection_bootstrap")
            .withUsername(ADMIN).withPassword(PASSWORD);

    private ControlEnvironment environment;

    @BeforeEach
    void provisionOnlyOwnedFixture() throws Exception {
        try (Connection bootstrap = POSTGRES.createConnection("")) {
            sql(bootstrap, "DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            for (String role : new String[] {MIGRATION, RUNTIME, METADATA, "inspection_unknown"}) {
                sql(bootstrap, "DROP ROLE IF EXISTS " + role);
            }
            for (String role : new String[] {MIGRATION, RUNTIME, METADATA}) {
                sql(bootstrap, "CREATE ROLE " + role + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE "
                        + "NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '" + PASSWORD + "'");
            }
            sql(bootstrap, "CREATE DATABASE " + DATABASE + " OWNER " + ADMIN);
        }
        String jdbcUrl = POSTGRES.getJdbcUrl().replace("/inspection_bootstrap", "/" + DATABASE);
        environment = new ControlEnvironment(ControlEnvironment.Mode.STRICT_FRESH,
                ControlPlan.forService("auth"), jdbcUrl, DATABASE, ADMIN, PASSWORD,
                MIGRATION, PASSWORD, RUNTIME, PASSWORD, "dwp-migration-control-v2:" + "a".repeat(64),
                Map.of(), "", Map.of(), "");
        try (Connection control = admin()) {
            sql(control, "REVOKE ALL ON DATABASE " + DATABASE + " FROM PUBLIC");
            sql(control, "GRANT CONNECT ON DATABASE " + DATABASE + " TO " + MIGRATION + "," + RUNTIME + "," + METADATA);
            sql(control, "REVOKE ALL ON SCHEMA public FROM PUBLIC");
            sql(control, "GRANT USAGE,CREATE ON SCHEMA public TO " + MIGRATION);
            sql(control, "GRANT USAGE ON SCHEMA public TO " + RUNTIME);
            for (String role : new String[] {MIGRATION, RUNTIME}) {
                sql(control, "ALTER ROLE " + role + " IN DATABASE " + DATABASE + " SET search_path TO pg_catalog,public");
            }
            sql(control, "ALTER ROLE " + METADATA + " IN DATABASE " + DATABASE + " SET search_path TO pg_catalog");
            sql(control, "CREATE TABLE public.flyway_schema_history(installed_rank integer,secret text)");
            sql(control, "INSERT INTO public.flyway_schema_history VALUES(1,'history-must-not-be-read')");
            sql(control, "ALTER TABLE public.flyway_schema_history OWNER TO " + MIGRATION);
            sql(control, "CREATE TABLE public.domain_rows(id bigint,payload text)");
            sql(control, "GRANT SELECT,INSERT,UPDATE,DELETE ON public.domain_rows TO " + RUNTIME);
            sql(control, "CREATE FUNCTION public.unlisted_reader() RETURNS text LANGUAGE sql SECURITY DEFINER "
                    + "SET search_path=pg_catalog AS 'SELECT ''domain-must-not-be-read''::text'");
            sql(control, "REVOKE ALL ON FUNCTION public.unlisted_reader() FROM PUBLIC");
        }
    }

    @Test
    void disabledRequestOpensAndRetrievesNothingEvenWithNoOtherInputs() {
        AtomicInteger secrets = new AtomicInteger(), probes = new AtomicInteger();
        Optional<String> result = ControlInspectionFenceBridgeV1.inspect(null, null, null,
                ControlInspectionFenceBridgeV1.Request.disabled(), expected -> {
                    secrets.incrementAndGet(); throw new IllegalStateException("must not retrieve");
                }, pool -> { probes.incrementAndGet(); throw new IllegalStateException("must not open"); });
        assertTrue(result.isEmpty()); assertEquals(0, secrets.get()); assertEquals(0, probes.get());
    }

    @Test
    void missingNativeFenceDeniesWithoutMutationOrSecretRetrieval() throws Exception {
        try (Connection control = admin()) {
            AtomicInteger secrets = new AtomicInteger();
            denied(() -> ControlInspectionFenceBridgeV1.inspect(control, environment,
                    new ControlCredentials("a".repeat(43), "b".repeat(43)), metadataRequest(),
                    expected -> { secrets.incrementAndGet(); return generation(expected, PASSWORD); }, pool -> "fake"));
            assertEquals(0, secrets.get());
            DatabaseConnectionFence.requireDatabaseAcl(control, environment, DatabaseConnectionFence.State.BASELINE);
        }
        try (Connection original = login(RUNTIME, PASSWORD)) { assertEquals(RUNTIME, who(original)); }
    }

    @Test
    void baselineWithAdvisoryLockIsNotActiveInspectionAuthority() throws Exception {
        try (Connection control = admin()) {
            DatabaseControl.acquireExclusiveControlLock(control, environment);
            denied(() -> ControlInspectionFenceBridgeV1.inspect(control, environment,
                    new ControlCredentials("a".repeat(43), "b".repeat(43)), runtimeRequest(), null, pool -> "fake"));
            DatabaseConnectionFence.requireDatabaseAcl(control, environment, DatabaseConnectionFence.State.BASELINE);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {ADMIN, MIGRATION, "inspection_unknown"})
    void unregisteredAndOwnerSelectionsAreDeniedBeforeMutation(String selected) throws Exception {
        try (Connection control = admin()) {
            ControlCredentials credentials = active(control);
            denied(() -> ControlInspectionFenceBridgeV1.inspect(control, environment, credentials,
                    new ControlInspectionFenceBridgeV1.Request(true, selected, GENERATION), null, pool -> "fake"));
            requireActive(control);
            rejected(RUNTIME, credentials.runtimePassword(), "42501");
            ServiceConnectionControl.restoreServiceConnections(control, environment, credentials);
        }
    }

    @Test
    void missingMetadataGenerationPortDeniesBeforeAnyProbe() throws Exception {
        try (Connection control = admin()) {
            ControlCredentials credentials = active(control);
            AtomicInteger probes = new AtomicInteger();
            denied(() -> ControlInspectionFenceBridgeV1.inspect(control, environment, credentials,
                    metadataRequest(), null, pool -> { probes.incrementAndGet(); return "fake"; }));
            assertEquals(0, probes.get()); requireActive(control); rejected(METADATA, PASSWORD, "42501");
            ServiceConnectionControl.restoreServiceConnections(control, environment, credentials);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"service", "database", "jdbcUrl", "principal", "controlReference", "generation"})
    void generationSourceMustMatchEveryApprovedSlot(String changed) throws Exception {
        try (Connection control = admin()) {
            ControlCredentials credentials = active(control);
            AtomicInteger probes = new AtomicInteger();
            denied(() -> ControlInspectionFenceBridgeV1.inspect(control, environment, credentials,
                    metadataRequest(), expected -> generation(new ControlInspectionFenceBridgeV1.SourceIdentity(
                            changed.equals("service") ? "people" : expected.service(),
                            changed.equals("database") ? "other_database" : expected.database(),
                            changed.equals("jdbcUrl") ? expected.jdbcUrl() + "&wrong=1" : expected.jdbcUrl(),
                            changed.equals("principal") ? RUNTIME : expected.principal(),
                            changed.equals("controlReference") ? "dwp-migration-control-v2:" + "c".repeat(64) : expected.controlReference(),
                            changed.equals("generation") ? "c".repeat(64) : expected.generationReference()), PASSWORD),
                    pool -> { probes.incrementAndGet(); return "fake"; }));
            assertEquals(0, probes.get()); requireActive(control); rejected(METADATA, PASSWORD, "42501");
            ServiceConnectionControl.restoreServiceConnections(control, environment, credentials);
        }
    }

    @Test
    void correctTypedBindingWithWrongPasswordFailsNativeAuthenticationBeforeMutation() throws Exception {
        try (Connection control = admin()) {
            ControlCredentials credentials = active(control);
            denied(() -> ControlInspectionFenceBridgeV1.inspect(control, environment, credentials,
                    metadataRequest(), expected -> generation(expected, "not-the-real-password"), pool -> "fake"));
            requireActive(control); rejected(METADATA, PASSWORD, "42501");
            ServiceConnectionControl.restoreServiceConnections(control, environment, credentials);
        }
    }

    @Test
    void originalRuntimeProbeRevokesMigrationAndRestoresTheActivePrivateGeneration() throws Exception {
        try (Connection control = admin()) {
            ControlCredentials credentials = active(control);
            AtomicInteger secrets = new AtomicInteger();
            String observation = ControlInspectionFenceBridgeV1.inspect(control, environment, credentials,
                    runtimeRequest(), expected -> { secrets.incrementAndGet(); throw new IllegalStateException("no metadata"); },
                    pool -> {
                        try (Connection original = pool.getConnection()) {
                            assertOriginalCatalogIdentity(original, RUNTIME);
                            DatabaseConnectionFence.requireInspectionDatabaseAcl(control, environment, RUNTIME);
                            rejected(MIGRATION, credentials.migrationPassword(), "42501");
                            rejected(RUNTIME, PASSWORD, "28P01");
                            assertEquals("42501", assertThrows(SQLException.class,
                                    () -> sql(original, "SELECT * FROM public.flyway_schema_history")).getSQLState());
                            assertEquals("42501", assertThrows(SQLException.class,
                                    () -> sql(original, "CREATE TABLE public.forbidden(id bigint)")).getSQLState());
                            assertEquals("42501", assertThrows(SQLException.class,
                                    () -> sql(original, "SELECT public.unlisted_reader()")).getSQLState());
                            assertThrows(SQLException.class, pool::getConnection);
                            assertThrows(SQLException.class, () -> pool.getConnection(MIGRATION, credentials.migrationPassword()));
                            try (Connection concurrent = admin()) {
                                assertThrows(IllegalStateException.class,
                                        () -> DatabaseControl.acquireExclusiveControlLock(concurrent, environment));
                            }
                            return who(original);
                        }
                    }).orElseThrow();
            assertEquals(RUNTIME, observation); assertEquals(0, secrets.get()); requireActive(control);
            rejected(RUNTIME, credentials.runtimePassword(), "42501");
            ServiceConnectionControl.restoreServiceConnections(control, environment, credentials);
        }
        try (Connection original = login(RUNTIME, PASSWORD)) { assertEquals(RUNTIME, who(original)); }
    }

    @Test
    void metadataOldGenerationIsNativeAuthenticatedRotatedAndRestoredWithoutHistoryRead() throws Exception {
        try (Connection control = admin()) {
            ControlCredentials credentials = active(control);
            String observation = ControlInspectionFenceBridgeV1.inspect(control, environment, credentials,
                    metadataRequest(), expected -> generation(expected, PASSWORD), pool -> {
                        try (Connection original = pool.getConnection()) {
                            assertOriginalCatalogIdentity(original, METADATA);
                            DatabaseConnectionFence.requireInspectionDatabaseAcl(control, environment, METADATA);
                            rejected(MIGRATION, credentials.migrationPassword(), "42501");
                            rejected(RUNTIME, credentials.runtimePassword(), "42501");
                            rejected(METADATA, PASSWORD, "28P01");
                            assertEquals("42501", assertThrows(SQLException.class,
                                    () -> sql(original, "SELECT * FROM public.domain_rows")).getSQLState());
                            assertEquals("42501", assertThrows(SQLException.class,
                                    () -> sql(original, "SELECT * FROM public.flyway_schema_history")).getSQLState());
                            return who(original);
                        }
                    }).orElseThrow();
            assertEquals(METADATA, observation); requireActive(control); rejected(METADATA, PASSWORD, "42501");
            ServiceConnectionControl.restoreServiceConnections(control, environment, credentials);
        }
        try (Connection metadata = login(METADATA, PASSWORD)) { assertEquals(METADATA, who(metadata)); }
    }

    @Test
    void providerAndProbeExceptionsExposeNoSecretOrCauseAndFailClosedAfterMutation() throws Exception {
        try (Connection control = admin()) {
            ControlCredentials credentials = active(control);
            denied(() -> ControlInspectionFenceBridgeV1.inspect(control, environment, credentials,
                    metadataRequest(), expected -> generation(expected, PASSWORD), pool -> {
                        try (Connection original = pool.getConnection()) { assertOriginalCatalogIdentity(original, METADATA); }
                        throw new IllegalStateException("password-secret-" + PASSWORD);
                    }));
            DatabaseConnectionFence.requireDatabaseAcl(control, environment, DatabaseConnectionFence.State.FAILED);
            DatabaseConnectionFence.requireNoForeignSessions(control, environment);
            rejected(METADATA, PASSWORD, "28P01");
            rejected(MIGRATION, credentials.migrationPassword(), "28P01");
            rejected(RUNTIME, credentials.runtimePassword(), "28P01");
        }
    }

    @Test
    void returningAnObservationWithoutAnyNativeBorrowIsNotProof() throws Exception {
        try (Connection control = admin()) {
            ControlCredentials credentials = active(control);
            denied(() -> ControlInspectionFenceBridgeV1.inspect(control, environment, credentials,
                    runtimeRequest(), null, pool -> "self-consistent-but-no-native-probe"));
            DatabaseConnectionFence.requireDatabaseAcl(control, environment, DatabaseConnectionFence.State.FAILED);
        }
    }

    @Test
    void foreignLiveSessionPreventsInspectionBeforeSecretRetrieval() throws Exception {
        try (Connection control = admin()) {
            ControlCredentials credentials = active(control);
            AtomicInteger secrets = new AtomicInteger();
            try (Connection foreign = admin()) {
                denied(() -> ControlInspectionFenceBridgeV1.inspect(control, environment, credentials,
                        metadataRequest(), expected -> { secrets.incrementAndGet(); return generation(expected, PASSWORD); },
                        pool -> "fake"));
                assertFalse(foreign.isClosed()); assertEquals(0, secrets.get()); requireActive(control);
            }
            ServiceConnectionControl.restoreServiceConnections(control, environment, credentials);
        }
    }

    @Test
    void inspectionStateCannotBeUsedWithoutAnExplicitRegisteredPrincipal() throws Exception {
        try (Connection control = admin()) {
            ControlCredentials credentials = active(control);
            assertThrows(IllegalStateException.class, () -> DatabaseConnectionFence.requireDatabaseAcl(
                    control, environment, DatabaseConnectionFence.State.INSPECTION));
            assertThrows(IllegalStateException.class, () -> DatabaseConnectionFence.requireInspectionDatabaseAcl(
                    control, environment, MIGRATION));
            requireActive(control); ServiceConnectionControl.restoreServiceConnections(control, environment, credentials);
        }
    }

    private ControlCredentials active(Connection control) throws Exception {
        DatabaseControl.acquireExclusiveControlLock(control, environment);
        return ServiceConnectionControl.activateExclusiveControlFence(control, environment);
    }

    private static ControlInspectionFenceBridgeV1.Request runtimeRequest() {
        return new ControlInspectionFenceBridgeV1.Request(true, RUNTIME, null);
    }

    private static ControlInspectionFenceBridgeV1.Request metadataRequest() {
        return new ControlInspectionFenceBridgeV1.Request(true, METADATA, GENERATION);
    }

    private static ControlInspectionFenceBridgeV1.MetadataGeneration generation(
            ControlInspectionFenceBridgeV1.SourceIdentity expected, String password) {
        return new ControlInspectionFenceBridgeV1.MetadataGeneration(expected, password);
    }

    private void requireActive(Connection control) throws Exception {
        DatabaseConnectionFence.requireDatabaseAcl(control, environment, DatabaseConnectionFence.State.ACTIVE);
    }

    private static void assertOriginalCatalogIdentity(Connection original, String role) throws Exception {
        assertEquals(role, who(original));
        assertEquals(role, scalar(original, "SELECT session_user"));
        assertEquals(DATABASE, scalar(original, "SELECT pg_catalog.current_database()"));
        assertEquals("none", scalar(original, "SELECT pg_catalog.current_setting('role')"));
        assertEquals("origin", scalar(original, "SELECT pg_catalog.current_setting('session_replication_role')"));
        assertTrue(original.getAutoCommit());
        assertNotNull(scalar(original, "SELECT pg_catalog.count(*)::text FROM pg_catalog.pg_class"));
    }

    private void rejected(String role, String password, String state) {
        assertEquals(state, assertThrows(SQLException.class, () -> login(role, password)).getSQLState());
    }

    private static void denied(org.junit.jupiter.api.function.Executable work) {
        IllegalStateException failure = assertThrows(IllegalStateException.class, work);
        assertEquals("Migration Control inspection was denied", failure.getMessage());
        assertNull(failure.getCause()); assertEquals(0, failure.getSuppressed().length);
    }

    private Connection admin() throws SQLException { return login(ADMIN, PASSWORD); }
    private Connection login(String role, String password) throws SQLException {
        return DriverManager.getConnection(environment.jdbcUrl(), role, password);
    }
    private static String who(Connection connection) throws SQLException { return scalar(connection, "SELECT current_user"); }
    private static String scalar(Connection connection, String query) throws SQLException {
        try (var sql = connection.createStatement(); var result = sql.executeQuery(query)) { result.next(); return result.getString(1); }
    }
    private static void sql(Connection connection, String query) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(query); }
    }
}
