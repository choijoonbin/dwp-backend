package com.dwp.migration.control;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class MigrationControlPostgresTest {
    private static final String ADMIN = "control_admin";
    private static final String ADMIN_PASSWORD = "control_admin_password";
    private static final String MIGRATION = "control_migration";
    private static final String MIGRATION_PASSWORD = "control_migration_password";
    private static final String RUNTIME = "control_runtime";
    private static final String RUNTIME_PASSWORD = "control_runtime_password";
    private static final String METADATA = "dwp_provider_metadata_auth";
    private static final String POSTGRES_IMAGE = controlPostgresImage();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            POSTGRES_IMAGE)
            .withDatabaseName("control_test")
            .withUsername(ADMIN)
            .withPassword(ADMIN_PASSWORD);

    private static ControlEnvironment environment;

    @BeforeAll
    static void provisionPrincipals() throws Exception {
        environment = new ControlEnvironment(
                ControlEnvironment.Mode.STRICT_FRESH,
                ControlPlan.forService("auth"),
                POSTGRES.getJdbcUrl(),
                POSTGRES.getDatabaseName(),
                ADMIN,
                ADMIN_PASSWORD,
                MIGRATION,
                MIGRATION_PASSWORD,
                RUNTIME,
                RUNTIME_PASSWORD,
                "dwp-migration-control-v2:" + "a".repeat(64),
                Map.of(),
                "",
                Map.of(),
                "");
        try (Connection admin = admin()) {
            DatabaseControl.execute(admin, "CREATE ROLE " + MIGRATION
                    + " LOGIN PASSWORD '" + MIGRATION_PASSWORD + "' "
                    + "NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT "
                    + "NOREPLICATION NOBYPASSRLS");
            DatabaseControl.execute(admin, "CREATE ROLE " + RUNTIME
                    + " LOGIN PASSWORD '" + RUNTIME_PASSWORD + "' "
                    + "NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT "
                    + "NOREPLICATION NOBYPASSRLS");
            DatabaseControl.execute(admin, "CREATE ROLE " + METADATA
                    + " LOGIN PASSWORD 'control_metadata_password' "
                    + "NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT "
                    + "NOREPLICATION NOBYPASSRLS");
            DatabaseControl.execute(admin, "REVOKE CONNECT, TEMPORARY ON DATABASE "
                    + POSTGRES.getDatabaseName() + " FROM PUBLIC");
            DatabaseControl.execute(admin, "GRANT CONNECT ON DATABASE "
                    + POSTGRES.getDatabaseName() + " TO " + MIGRATION + ", " + RUNTIME
                    + ", " + METADATA);
            DatabaseControl.execute(admin, "ALTER ROLE " + MIGRATION + " IN DATABASE "
                    + POSTGRES.getDatabaseName()
                    + " SET search_path TO pg_catalog, public");
            DatabaseControl.execute(admin, "ALTER ROLE " + RUNTIME + " IN DATABASE "
                    + POSTGRES.getDatabaseName()
                    + " SET search_path TO pg_catalog, public");
            DatabaseControl.execute(admin, "ALTER ROLE " + METADATA + " IN DATABASE "
                    + POSTGRES.getDatabaseName() + " SET search_path TO pg_catalog");
        }
    }

    @Test
    void advisoryFenceRejectsAConcurrentControlSession() throws Exception {
        try (Connection first = admin()) {
            DatabaseControl.acquireExclusiveControlLock(first, environment);
            ControlCredentials temporaryCredentials =
                    ServiceConnectionControl.activateExclusiveControlFence(first, environment);
            try (Connection second = admin()) {
                IllegalStateException blocked = assertThrows(
                        IllegalStateException.class,
                        () -> DatabaseControl.acquireExclusiveControlLock(
                                second, environment));
                assertEquals(
                        "Another Migration Control owns the database/service fence",
                        blocked.getMessage());
            }
            for (String[] credential : new String[][] {
                    {MIGRATION, MIGRATION_PASSWORD},
                    {RUNTIME, RUNTIME_PASSWORD}
            }) {
                SQLException denied = assertThrows(
                        SQLException.class,
                        () -> DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(), credential[0], credential[1]));
                assertEquals("28P01", denied.getSQLState());
            }
            SQLException metadataDenied = assertThrows(
                    SQLException.class,
                    () -> DriverManager.getConnection(
                            POSTGRES.getJdbcUrl(), METADATA, "control_metadata_password"));
            assertEquals("42501", metadataDenied.getSQLState());
            try (Connection migration = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), MIGRATION,
                    temporaryCredentials.migrationPassword())) {
                assertEquals(
                        MIGRATION,
                        DatabaseControl.scalar(migration, "SELECT current_user"));
                assertEquals(
                        MIGRATION,
                        DatabaseControl.scalar(migration, "SELECT session_user"));
                DatabaseControl.execute(migration, "RESET ROLE");
                assertEquals(
                        MIGRATION,
                        DatabaseControl.scalar(migration, "SELECT current_user"));
                SQLException elevation = assertThrows(
                        SQLException.class,
                        () -> DatabaseControl.execute(
                                migration,
                                "SET SESSION AUTHORIZATION " + ADMIN));
                assertEquals("42501", elevation.getSQLState());
            }
            SQLException runtimeStillFenced = assertThrows(
                    SQLException.class,
                    () -> DriverManager.getConnection(
                            POSTGRES.getJdbcUrl(), RUNTIME,
                            temporaryCredentials.runtimePassword()));
            assertEquals("42501", runtimeStillFenced.getSQLState());
            ServiceConnectionControl.restoreServiceConnections(
                    first, environment, temporaryCredentials);
        }
        assertDoesNotThrow(() -> {
            try (Connection connection = migration()) {
                assertFalse(connection.isClosed());
            }
        });
    }

    @Test
    void rejectedPreflightLeavesCredentialsAndDatabaseAclUntouched() throws Exception {
        String schema = "control_preflight_tamper";
        ControlEnvironment preflightEnvironment = environmentForSchema(schema);
        try (Connection control = admin()) {
            DatabaseControl.execute(control, "CREATE SCHEMA " + schema
                    + " AUTHORIZATION " + MIGRATION);
            DatabaseControl.execute(control, "REVOKE ALL ON SCHEMA " + schema
                    + " FROM PUBLIC");
            DatabaseControl.execute(control, "GRANT USAGE, CREATE ON SCHEMA " + schema
                    + " TO " + MIGRATION);
        }
        try (Connection migration = migration()) {
            DatabaseControl.execute(migration, "CREATE TABLE " + schema
                    + ".unsealed_object (id bigint PRIMARY KEY)");
        }

        try {
            try (Connection control = admin()) {
                DatabaseControl.acquireExclusiveControlLock(
                        control, preflightEnvironment);
                IllegalStateException rejected = assertThrows(
                        IllegalStateException.class,
                        () -> ControlPreflight.verify(control, preflightEnvironment));
                assertEquals(
                        "Fresh Control refuses a non-empty unmanaged schema",
                        rejected.getMessage());
                DatabaseConnectionFence.requireDatabaseAcl(
                        control,
                        preflightEnvironment,
                        DatabaseConnectionFence.State.BASELINE);
            }
            assertDoesNotThrow(() -> {
                try (Connection ignored = migration()) {
                    assertFalse(ignored.isClosed());
                }
            });
            assertDoesNotThrow(() -> {
                try (Connection ignored = runtime()) {
                    assertFalse(ignored.isClosed());
                }
            });
        } finally {
            try (Connection control = admin()) {
                DatabaseControl.execute(control, "DROP SCHEMA IF EXISTS " + schema
                        + " CASCADE");
            }
        }
    }

    @Test
    void systemCatalogAclDriftIsRejectedBeforeAndAfterFenceMutation() throws Exception {
        String sensitiveRoutine = "pg_catalog.pg_read_file(text)";
        for (String grantee : List.of(RUNTIME, "PUBLIC")) {
            try {
                try (Connection control = admin()) {
                    DatabaseControl.execute(control, "GRANT EXECUTE ON FUNCTION "
                            + sensitiveRoutine + " TO " + grantee);
                    DatabaseControl.acquireExclusiveControlLock(control, environment);
                    IllegalStateException rejected = assertThrows(
                            IllegalStateException.class,
                            () -> ControlPreflight.verify(control, environment));
                    org.junit.jupiter.api.Assertions.assertTrue(
                            rejected.getMessage().contains(
                                    "exceeds PostgreSQL's initial system-catalog ACL"));
                    DatabaseConnectionFence.requireDatabaseAcl(
                            control, environment, DatabaseConnectionFence.State.BASELINE);
                }
                assertDoesNotThrow(() -> {
                    try (Connection ignored = runtime()) {
                        assertFalse(ignored.isClosed());
                    }
                });
            } finally {
                try (Connection control = admin()) {
                    DatabaseControl.execute(control, "REVOKE EXECUTE ON FUNCTION "
                            + sensitiveRoutine + " FROM " + grantee);
                }
            }
        }

        try (Connection control = admin()) {
            DatabaseControl.acquireExclusiveControlLock(control, environment);
            SystemCatalogAuthorityControl.verifyExisting(control, environment);
            ControlCredentials credentials =
                    ServiceConnectionControl.activateExclusiveControlFence(control, environment);
            try {
                DatabaseControl.execute(control, "GRANT EXECUTE ON FUNCTION "
                        + sensitiveRoutine + " TO " + RUNTIME);
                assertThrows(
                        IllegalStateException.class,
                        () -> SystemCatalogAuthorityControl.verifyExisting(
                                control, environment));
            } finally {
                DatabaseControl.execute(control, "REVOKE EXECUTE ON FUNCTION "
                        + sensitiveRoutine + " FROM " + RUNTIME);
                ServiceConnectionControl.restoreServiceConnections(
                        control, environment, credentials);
            }
        }
    }

    @Test
    void controlFenceRejectsUnknownDatabaseAclGranteesAndForeignSessions()
            throws Exception {
        String rogue = "control_rogue_database_access";
        String password = "control_rogue_database_password";
        try (Connection control = admin()) {
            DatabaseControl.execute(control, "CREATE ROLE " + rogue
                    + " LOGIN PASSWORD '" + password + "' NOINHERIT");
            DatabaseControl.execute(control, "GRANT CONNECT ON DATABASE "
                    + POSTGRES.getDatabaseName() + " TO " + rogue);
            IllegalStateException acl = assertThrows(
                    IllegalStateException.class,
                    () -> DatabaseConnectionFence.requireDatabaseAcl(
                            control, environment,
                            DatabaseConnectionFence.State.BASELINE));
            org.junit.jupiter.api.Assertions.assertTrue(acl.getMessage().startsWith(
                    "Migration Control database ACL differs from the exact baseline fence"));
            try (Connection foreign = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), rogue, password)) {
                assertFalse(foreign.isClosed());
                IllegalStateException session = assertThrows(
                        IllegalStateException.class,
                        () -> DatabaseConnectionFence.requireNoForeignSessions(
                                control, environment));
                assertEquals(
                        "Migration Control requires every non-Control database session offline",
                        session.getMessage());
            }
        } finally {
            try (Connection control = admin()) {
                DatabaseControl.execute(control, "REVOKE CONNECT ON DATABASE "
                        + POSTGRES.getDatabaseName() + " FROM " + rogue);
                DatabaseControl.execute(control, "DROP ROLE IF EXISTS " + rogue);
            }
        }
    }

    @Test
    void controlFenceDrainsAConnectionWhoseBackendIsAlreadyClosing()
            throws Exception {
        String rogue = "control_closing_database_session";
        String password = "control_closing_database_password";
        try (Connection control = admin()) {
            DatabaseControl.execute(control, "CREATE ROLE " + rogue
                    + " LOGIN PASSWORD '" + password + "' NOINHERIT");
            DatabaseControl.execute(control, "GRANT CONNECT ON DATABASE "
                    + POSTGRES.getDatabaseName() + " TO " + rogue);
            Connection closing = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), rogue, password);
            CountDownLatch closeStarted = new CountDownLatch(1);
            Thread closer = new Thread(() -> {
                closeStarted.countDown();
                try {
                    Thread.sleep(100L);
                    closing.close();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (SQLException exception) {
                    throw new IllegalStateException(exception);
                }
            }, "migration-control-closing-session");
            closer.start();
            assertTrue(closeStarted.await(1, TimeUnit.SECONDS));
            assertDoesNotThrow(() ->
                    DatabaseConnectionFence.requireNoForeignSessions(
                            control, environment));
            closer.join(2_000L);
            assertFalse(closer.isAlive());
        } finally {
            try (Connection control = admin()) {
                DatabaseControl.execute(control, "REVOKE CONNECT ON DATABASE "
                        + POSTGRES.getDatabaseName() + " FROM " + rogue);
                DatabaseControl.execute(control, "DROP ROLE IF EXISTS " + rogue);
            }
        }
    }

    @Test
    void controlFenceRejectsInboundAndOutgoingServiceRoleMemberships()
            throws Exception {
        String rogue = "control_rogue_membership";
        try (Connection control = admin()) {
            DatabaseControl.execute(control, "CREATE ROLE " + rogue + " NOLOGIN");
            DatabaseControl.execute(control, "GRANT " + rogue + " TO " + MIGRATION
                    + " WITH SET TRUE");
            assertThrows(
                    IllegalStateException.class,
                    () -> RoleMembershipFence.requireExact(control, environment));
            DatabaseControl.execute(control, "REVOKE " + rogue + " FROM " + MIGRATION);

            DatabaseControl.execute(control, "GRANT " + RUNTIME + " TO " + rogue
                    + " WITH SET TRUE");
            assertThrows(
                    IllegalStateException.class,
                    () -> RoleMembershipFence.requireExact(control, environment));
            DatabaseControl.execute(control, "REVOKE " + RUNTIME + " FROM " + rogue);

            DatabaseControl.execute(control, "GRANT " + rogue + " TO " + METADATA
                    + " WITH SET TRUE");
            assertThrows(
                    IllegalStateException.class,
                    () -> RoleMembershipFence.requireExact(control, environment));
            DatabaseControl.execute(control, "REVOKE " + rogue + " FROM " + METADATA);
            assertDoesNotThrow(
                    () -> RoleMembershipFence.requireExact(control, environment));

            DatabaseControl.execute(control, "ALTER ROLE " + METADATA + " CREATEDB");
            assertThrows(
                    IllegalStateException.class,
                    () -> RoleMembershipFence.requireExact(control, environment));
            DatabaseControl.execute(control, "ALTER ROLE " + METADATA + " NOCREATEDB");
            assertDoesNotThrow(
                    () -> RoleMembershipFence.requireExact(control, environment));
            DatabaseControl.execute(control, "DROP ROLE " + rogue);
        }
    }

    @Test
    void controlFenceRejectsElevatedServiceRoleBeforeOpeningItsWindow()
            throws Exception {
        try (Connection control = admin()) {
            DatabaseControl.execute(control, "ALTER ROLE " + MIGRATION + " CREATEROLE");
            DatabaseControl.acquireExclusiveControlLock(control, environment);
            IllegalStateException elevated = assertThrows(
                    IllegalStateException.class,
                    () -> ServiceConnectionControl.activateExclusiveControlFence(
                            control, environment));
            assertEquals(
                    "Migration Control service login attributes are not strict: "
                            + MIGRATION,
                    elevated.getMessage());
            DatabaseControl.execute(control, "ALTER ROLE " + MIGRATION
                    + " NOCREATEROLE");
            assertDoesNotThrow(
                    () -> DatabaseControl.requireStrictServiceLoginRoles(
                            control, environment));
        }
    }

    @Test
    void controlFenceRejectsRoleOrDatabaseSearchPathDrift() throws Exception {
        try (Connection control = admin()) {
            assertDoesNotThrow(() -> RoleSearchPathFence.requireExact(
                    control, environment));

            DatabaseControl.execute(control, "ALTER ROLE " + MIGRATION
                    + " IN DATABASE " + POSTGRES.getDatabaseName()
                    + " SET search_path TO public");
            assertThrows(IllegalStateException.class, () ->
                    RoleSearchPathFence.requireExact(control, environment));
            DatabaseControl.execute(control, "ALTER ROLE " + MIGRATION
                    + " IN DATABASE " + POSTGRES.getDatabaseName()
                    + " SET search_path TO pg_catalog, public");

            DatabaseControl.execute(control, "ALTER ROLE " + METADATA
                    + " SET statement_timeout TO '1s'");
            assertThrows(IllegalStateException.class, () ->
                    RoleSearchPathFence.requireExact(control, environment));
            DatabaseControl.execute(control, "ALTER ROLE " + METADATA
                    + " RESET statement_timeout");

            DatabaseControl.execute(control, "ALTER DATABASE "
                    + POSTGRES.getDatabaseName() + " SET search_path TO public");
            assertThrows(IllegalStateException.class, () ->
                    RoleSearchPathFence.requireExact(control, environment));
            DatabaseControl.execute(control, "ALTER DATABASE "
                    + POSTGRES.getDatabaseName() + " RESET search_path");
            assertDoesNotThrow(() -> RoleSearchPathFence.requireExact(
                    control, environment));
        }
    }

    @Test
    void controlRemovesEverySessionReplicationBypassBeforeMigrationConnects()
            throws Exception {
        ControlCredentials temporaryCredentials = null;
        try (Connection control = admin()) {
            DatabaseControl.execute(control,
                    "GRANT SET ON PARAMETER session_replication_role TO PUBLIC, "
                            + MIGRATION + ", " + RUNTIME + ", " + METADATA);
            DatabaseControl.execute(control, "ALTER ROLE " + MIGRATION
                    + " SET session_replication_role TO replica");
            DatabaseControl.execute(control, "ALTER DATABASE "
                    + POSTGRES.getDatabaseName()
                    + " SET session_replication_role TO replica");

            DatabaseControl.acquireExclusiveControlLock(control, environment);
            temporaryCredentials = ServiceConnectionControl.activateExclusiveControlFence(
                    control, environment);
            assertDoesNotThrow(() -> SessionReplicationRoleControl.requireExact(
                    control, environment));
            try (Connection migration = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), MIGRATION,
                    temporaryCredentials.migrationPassword())) {
                assertEquals("origin", DatabaseControl.scalar(
                        migration, "SELECT current_setting('session_replication_role')"));
                assertPrivilegeDenied(
                        migration, "SET session_replication_role TO replica");
            }
        } finally {
            try (Connection control = admin()) {
                if (temporaryCredentials != null) {
                    ServiceConnectionControl.restoreServiceConnections(
                            control, environment, temporaryCredentials);
                }
                DatabaseControl.execute(control,
                        "REVOKE ALL ON PARAMETER session_replication_role FROM PUBLIC, "
                                + MIGRATION + ", " + RUNTIME + ", " + METADATA);
                DatabaseControl.execute(control, "ALTER ROLE " + MIGRATION
                        + " RESET session_replication_role");
                DatabaseControl.execute(control, "ALTER DATABASE "
                        + POSTGRES.getDatabaseName()
                        + " RESET session_replication_role");
            }
        }
    }

    @Test
    void controlSealRejectsEveryUnapprovedProtectedSchemaAclGrantee()
            throws Exception {
        String schema = "control_acl_fixture";
        String rogue = "control_rogue_acl_grantee";
        ControlEnvironment aclEnvironment = environmentForSchema(schema);
        try (Connection control = admin()) {
            DatabaseControl.execute(control, "CREATE ROLE " + rogue + " NOLOGIN");
            DatabaseControl.execute(control, "CREATE SCHEMA " + schema
                    + " AUTHORIZATION " + MIGRATION);
            DatabaseControl.execute(control, "REVOKE ALL ON SCHEMA " + schema
                    + " FROM PUBLIC");
            DatabaseControl.execute(control, "GRANT USAGE, CREATE ON SCHEMA " + schema
                    + " TO " + MIGRATION);
            DatabaseControl.execute(control, "GRANT USAGE ON SCHEMA " + schema
                    + " TO " + RUNTIME);
        }
        try (Connection migration = migration()) {
            DatabaseControl.execute(migration, "CREATE TABLE " + schema
                    + ".domain_entry (id bigint PRIMARY KEY, value text)");
            DatabaseControl.execute(migration, "CREATE FUNCTION " + schema
                    + ".domain_helper() RETURNS integer LANGUAGE sql AS 'SELECT 1'");
            DatabaseControl.execute(migration, "REVOKE ALL ON FUNCTION " + schema
                    + ".domain_helper() FROM PUBLIC");
        }
        record Mutation(String grant, String revoke) { }
        List<Mutation> mutations = List.of(
                new Mutation(
                        "GRANT USAGE ON SCHEMA " + schema + " TO " + rogue,
                        "REVOKE ALL ON SCHEMA " + schema + " FROM " + rogue),
                new Mutation(
                        "GRANT SELECT ON " + schema + ".domain_entry TO " + rogue,
                        "REVOKE ALL ON " + schema + ".domain_entry FROM " + rogue),
                new Mutation(
                        "GRANT SELECT (value) ON TABLE " + schema
                                + ".domain_entry TO " + rogue,
                        "REVOKE ALL (value) ON TABLE " + schema
                                + ".domain_entry FROM " + rogue),
                new Mutation(
                        "GRANT EXECUTE ON FUNCTION " + schema
                                + ".domain_helper() TO " + rogue,
                        "REVOKE ALL ON FUNCTION " + schema
                                + ".domain_helper() FROM " + rogue),
                new Mutation(
                        "ALTER DEFAULT PRIVILEGES FOR ROLE " + MIGRATION
                                + " IN SCHEMA " + schema
                                + " GRANT SELECT ON TABLES TO " + rogue,
                        "ALTER DEFAULT PRIVILEGES FOR ROLE " + MIGRATION
                                + " IN SCHEMA " + schema
                                + " REVOKE ALL ON TABLES FROM " + rogue));
        try {
            for (Mutation mutation : mutations) {
                try (Connection control = admin()) {
                    DatabaseControl.execute(control, mutation.grant());
                    assertThrows(
                            IllegalStateException.class,
                            () -> ProtectedSchemaAclControl.verify(
                                    control, aclEnvironment));
                    DatabaseControl.execute(control, mutation.revoke());
                    assertDoesNotThrow(() -> ProtectedSchemaAclControl.verify(
                            control, aclEnvironment));
                }
            }
        } finally {
            try (Connection control = admin()) {
                DatabaseControl.execute(control, "DROP SCHEMA IF EXISTS " + schema
                        + " CASCADE");
                DatabaseControl.execute(control, "DROP OWNED BY " + rogue);
                DatabaseControl.execute(control, "DROP ROLE IF EXISTS " + rogue);
            }
        }
    }

    @Test
    void serviceRolesCannotOwnObjectsOrCreateInAnyUnprotectedSchema()
            throws Exception {
        String runtimeSchema = "control_runtime_owned_schema";
        String extensionSchema = "control_runtime_extension_schema";
        try (Connection control = admin()) {
            DatabaseControl.execute(control, "ALTER SCHEMA public OWNER TO " + MIGRATION);
            ServiceRoleDdlBoundaryControl.normalize(control, environment);
            assertDoesNotThrow(() ->
                    ServiceRoleDdlBoundaryControl.requireExact(control, environment));

            DatabaseControl.execute(control,
                    "GRANT CREATE ON SCHEMA pg_catalog TO " + RUNTIME);
            assertThrows(IllegalStateException.class, () ->
                    ServiceRoleDdlBoundaryControl.requireExact(control, environment));
            ServiceRoleDdlBoundaryControl.normalize(control, environment);

            DatabaseControl.execute(control,
                    "GRANT CREATE ON SCHEMA information_schema TO " + MIGRATION);
            assertThrows(IllegalStateException.class, () ->
                    ServiceRoleDdlBoundaryControl.requireExact(control, environment));
            ServiceRoleDdlBoundaryControl.normalize(control, environment);

            DatabaseControl.execute(control, "CREATE SCHEMA " + runtimeSchema
                    + " AUTHORIZATION " + RUNTIME);
            assertThrows(IllegalStateException.class, () ->
                    ServiceRoleDdlBoundaryControl.normalize(control, environment));
            DatabaseControl.execute(control, "ALTER SCHEMA " + runtimeSchema
                    + " OWNER TO " + ADMIN);
            DatabaseControl.execute(control, "DROP SCHEMA " + runtimeSchema);

            DatabaseControl.execute(control, "CREATE SCHEMA " + extensionSchema
                    + " AUTHORIZATION " + RUNTIME);
            DatabaseControl.execute(control, "GRANT CREATE ON DATABASE "
                    + POSTGRES.getDatabaseName() + " TO " + RUNTIME);
            DatabaseControl.execute(control, "SET ROLE " + RUNTIME);
            DatabaseControl.execute(control, "CREATE EXTENSION hstore SCHEMA "
                    + extensionSchema);
            DatabaseControl.execute(control, "RESET ROLE");
            assertThrows(IllegalStateException.class, () ->
                    RequiredDatabaseExtensionControl.requireExact(
                            control, environment));
            DatabaseControl.execute(control, "REVOKE CREATE ON DATABASE "
                    + POSTGRES.getDatabaseName() + " FROM " + RUNTIME);
            DatabaseControl.execute(control, "ALTER SCHEMA " + extensionSchema
                    + " OWNER TO " + ADMIN);
            assertThrows(IllegalStateException.class, () ->
                    ServiceRoleDdlBoundaryControl.requireExact(control, environment));
            assertThrows(IllegalStateException.class, () ->
                    ServiceRoleDdlBoundaryControl.normalize(control, environment));
            DatabaseControl.execute(control, "DROP EXTENSION hstore");
            DatabaseControl.execute(control, "DROP SCHEMA " + extensionSchema);
            assertDoesNotThrow(() ->
                    RequiredDatabaseExtensionControl.requireExact(
                            control, environment));

            ServiceRoleDdlBoundaryControl.normalize(control, environment);
            assertDoesNotThrow(() ->
                    ServiceRoleDdlBoundaryControl.requireExact(control, environment));
        } finally {
            try (Connection control = admin()) {
                DatabaseControl.execute(control, "RESET ROLE");
                DatabaseControl.execute(control, "REVOKE CREATE ON DATABASE "
                        + POSTGRES.getDatabaseName() + " FROM " + RUNTIME);
                DatabaseControl.execute(control, "DROP EXTENSION IF EXISTS hstore CASCADE");
                DatabaseControl.execute(control, "DROP SCHEMA IF EXISTS "
                        + extensionSchema + " CASCADE");
                DatabaseControl.execute(control, "DROP SCHEMA IF EXISTS "
                        + runtimeSchema + " CASCADE");
                ServiceRoleDdlBoundaryControl.normalize(control, environment);
            }
        }
    }

    @Test
    void failedControlLeavesDatabaseConnectFenceClosed() throws Exception {
        ControlCredentials temporaryCredentials;
        try (Connection failedControl = admin()) {
            DatabaseControl.acquireExclusiveControlLock(failedControl, environment);
            temporaryCredentials =
                    ServiceConnectionControl.activateExclusiveControlFence(failedControl, environment);
            DatabaseControl.grantTemporary(failedControl, environment);
            assertEquals(1L, DatabaseControl.scalarLong(failedControl,
                    "SELECT CASE WHEN has_database_privilege('" + MIGRATION + "', '"
                            + POSTGRES.getDatabaseName()
                            + "', 'TEMPORARY') THEN 1 ELSE 0 END"));
            ServiceConnectionControl.failClosedServiceConnections(failedControl, environment);
        }
        SQLException denied = assertThrows(SQLException.class, MigrationControlPostgresTest::runtime);
        assertEquals("28P01", denied.getSQLState());
        assertThrows(SQLException.class, MigrationControlPostgresTest::migration);
        assertThrows(
                SQLException.class,
                () -> DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATION,
                        temporaryCredentials.migrationPassword()));
        assertThrows(
                SQLException.class,
                () -> DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), RUNTIME,
                        temporaryCredentials.runtimePassword()));
        try (Connection recoveryControl = admin()) {
            assertEquals(0L, DatabaseControl.scalarLong(recoveryControl,
                    "SELECT CASE WHEN has_database_privilege('" + MIGRATION + "', '"
                            + POSTGRES.getDatabaseName()
                            + "', 'TEMPORARY') THEN 1 ELSE 0 END"));
            ServiceConnectionControl.restoreServiceConnections(
                    recoveryControl, environment, temporaryCredentials);
        }
        assertDoesNotThrow(() -> {
            try (Connection connection = runtime()) {
                assertFalse(connection.isClosed());
            }
        });
    }


    @Test
    void metadataContractRejectsExtraObjectsOrColumnsAndKeepsControlOwnership()
            throws Exception {
        try (Connection admin = admin()) {
            ControlMetadata.ensure(admin, ADMIN);
            assertDoesNotThrow(() -> ControlMetadata.verify(admin, ADMIN));
            DatabaseControl.execute(admin,
                    "CREATE TABLE dwp_migration_control.unmanaged (value text)");
            assertThrows(
                    IllegalStateException.class,
                    () -> ControlMetadata.verify(admin, ADMIN));
            DatabaseControl.execute(admin,
                    "DROP TABLE dwp_migration_control.unmanaged");
            DatabaseControl.execute(admin,
                    "CREATE FUNCTION dwp_migration_control.unmanaged() "
                            + "RETURNS integer LANGUAGE sql AS 'SELECT 1'");
            assertThrows(
                    IllegalStateException.class,
                    () -> ControlMetadata.verify(admin, ADMIN));
            DatabaseControl.execute(admin,
                    "DROP FUNCTION dwp_migration_control.unmanaged()");
            assertDoesNotThrow(() -> ControlMetadata.verify(admin, ADMIN));
            DatabaseControl.execute(admin,
                    "ALTER TABLE dwp_migration_control.adoption_inventory "
                            + "ADD COLUMN unexpected text");
            assertThrows(
                    IllegalStateException.class,
                    () -> ControlMetadata.verify(admin, ADMIN));
        }
    }

    @Test
    void runtimeCanMutateDomainButNeverHistoryOrUnlistedRoutines() throws Exception {
        try (Connection admin = admin()) {
            DatabaseControl.execute(admin, "ALTER SCHEMA public OWNER TO " + MIGRATION);
            DatabaseControl.execute(admin, "GRANT USAGE, CREATE ON SCHEMA public TO "
                    + MIGRATION);
        }
        try (Connection migration = migration()) {
            DatabaseControl.execute(migration, "CREATE TABLE public.runtime_domain_fixture ("
                    + "fixture_id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
                    + "value text NOT NULL)");
            createDomainEventLedgerFixture(migration);
            DatabaseControl.execute(migration, "CREATE TABLE public.flyway_schema_history ("
                    + "installed_rank integer PRIMARY KEY, description text NOT NULL)");
            DatabaseControl.execute(migration, "CREATE FUNCTION public.runtime_escalation_fixture() "
                    + "RETURNS integer LANGUAGE sql SECURITY DEFINER "
                    + "SET search_path=pg_catalog AS 'SELECT 1'");
        }
        try (Connection admin = admin()) {
            DomainPrivilegeControl.normalize(admin, environment);
        }

        assertRuntimeDomainAllowedAndProtectedObjectsDenied("initial");

        try (Connection admin = admin()) {
            DatabaseControl.execute(admin,
                    "GRANT DELETE ON public.sys_domain_event_outbox TO " + RUNTIME);
            assertThrows(IllegalStateException.class,
                    () -> DomainEventLedgerAclControl.verify(admin, environment));
            DomainEventLedgerAclControl.normalize(admin, environment);

            DatabaseControl.execute(admin,
                    "GRANT SELECT (event_key) ON public.sys_domain_event_inbox TO "
                            + RUNTIME);
            assertThrows(IllegalStateException.class,
                    () -> DomainEventLedgerAclControl.verify(admin, environment));
            DomainEventLedgerAclControl.normalize(admin, environment);

            DatabaseControl.execute(admin,
                    "ALTER DEFAULT PRIVILEGES FOR ROLE " + MIGRATION
                            + " IN SCHEMA public GRANT DELETE ON TABLES TO " + RUNTIME);
            assertThrows(IllegalStateException.class,
                    () -> DomainEventLedgerAclControl.verify(admin, environment));
            DomainEventLedgerAclControl.normalize(admin, environment);
            assertDoesNotThrow(
                    () -> DomainEventLedgerAclControl.verify(admin, environment));
        }

        try (Connection migration = migration()) {
            DatabaseControl.execute(migration,
                    "CREATE TABLE public.future_domain_fixture (fixture_id bigint PRIMARY KEY)");
            DatabaseControl.execute(migration, "CREATE FUNCTION public.future_escalation_fixture() "
                    + "RETURNS integer LANGUAGE sql SECURITY DEFINER "
                    + "SET search_path=pg_catalog AS 'SELECT 1'");
        }
        try (Connection runtime = runtime()) {
            assertPrivilegeDenied(runtime,
                    "INSERT INTO future_domain_fixture VALUES (1)");
            assertPrivilegeDenied(runtime, "SELECT future_escalation_fixture()");
        }

        try (Connection admin = admin()) {
            DomainPrivilegeControl.normalize(admin, environment);
        }
        try (Connection runtime = runtime()) {
            DatabaseControl.execute(runtime,
                    "INSERT INTO future_domain_fixture VALUES (1)");
        }
        assertRuntimeDomainAllowedAndProtectedObjectsDenied("restart");
    }

    private void assertRuntimeDomainAllowedAndProtectedObjectsDenied(String value)
            throws Exception {
        try (Connection runtime = runtime()) {
            DatabaseControl.execute(runtime,
                    "INSERT INTO runtime_domain_fixture(value) VALUES ('" + value + "')");
            for (String table : List.of(
                    "sys_domain_event_outbox",
                    "sys_domain_event_inbox",
                    "sys_domain_event_offsets")) {
                DatabaseControl.execute(runtime,
                        "INSERT INTO public." + table
                                + " (event_key, event_value) VALUES ('" + value
                                + "', 'before')");
                DatabaseControl.scalar(runtime,
                        "SELECT event_value FROM public." + table
                                + " WHERE event_key='" + value + "'");
                DatabaseControl.execute(runtime,
                        "UPDATE public." + table
                                + " SET event_value='after' WHERE event_key='" + value + "'");
                assertPrivilegeDenied(runtime,
                        "DELETE FROM public." + table
                                + " WHERE event_key='" + value + "'");
            }
            DatabaseControl.execute(runtime,
                    "INSERT INTO public.sys_domain_event_replay_audit(event_key) VALUES ('"
                            + value + "')");
            for (String statement : new String[] {
                    "SELECT * FROM public.sys_domain_event_replay_audit",
                    "UPDATE public.sys_domain_event_replay_audit "
                            + "SET event_key=event_key WHERE false",
                    "DELETE FROM public.sys_domain_event_replay_audit WHERE false",
                    "SELECT * FROM public.sys_domain_event_dead_letters",
                    "SELECT * FROM flyway_schema_history",
                    "INSERT INTO flyway_schema_history VALUES (1, 'forged')",
                    "UPDATE flyway_schema_history SET description='forged'",
                    "DELETE FROM flyway_schema_history",
                    "TRUNCATE flyway_schema_history",
                    "ALTER TABLE runtime_domain_fixture ADD COLUMN forged text",
                    "SELECT runtime_escalation_fixture()"
            }) {
                assertPrivilegeDenied(runtime, statement);
            }
        }
    }

    private static void createDomainEventLedgerFixture(Connection migration)
            throws SQLException {
        for (String table : List.of(
                "sys_domain_event_outbox",
                "sys_domain_event_inbox",
                "sys_domain_event_offsets")) {
            DatabaseControl.execute(migration,
                    "CREATE TABLE public." + table + " ("
                            + "event_key text PRIMARY KEY, event_value text NOT NULL)");
        }
        DatabaseControl.execute(migration,
                "CREATE TABLE public.sys_domain_event_replay_audit ("
                        + "event_key text PRIMARY KEY)");
        DatabaseControl.execute(migration,
                "CREATE VIEW public.sys_domain_event_dead_letters AS "
                        + "SELECT event_key, event_value "
                        + "FROM public.sys_domain_event_outbox WHERE false");
    }

    private static void assertPrivilegeDenied(Connection connection, String statement) {
        SQLException failure = assertThrows(
                SQLException.class,
                () -> DatabaseControl.execute(connection, statement));
        assertEquals("42501", failure.getSQLState());
    }


    private static ControlEnvironment environmentForSchema(String schema) {
        ControlPlan plan = new ControlPlan(
                "auth",
                List.of(new StreamPlan(
                        "acl-test", schema, "history", "classpath:db/migration",
                        false, false)),
                null,
                List.of(),
                List.of(),
                List.of());
        return new ControlEnvironment(
                ControlEnvironment.Mode.STRICT_FRESH,
                plan,
                POSTGRES.getJdbcUrl(),
                POSTGRES.getDatabaseName(),
                ADMIN,
                ADMIN_PASSWORD,
                MIGRATION,
                MIGRATION_PASSWORD,
                RUNTIME,
                RUNTIME_PASSWORD,
                "dwp-migration-control-v2:" + "a".repeat(64),
                Map.of(),
                "",
                Map.of(),
                "");
    }


    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), ADMIN, ADMIN_PASSWORD);
    }

    private static Connection migration() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), MIGRATION, MIGRATION_PASSWORD);
    }

    private static Connection runtime() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), RUNTIME, RUNTIME_PASSWORD);
    }

    private static String controlPostgresImage() {
        String configured = System.getenv().getOrDefault(
                "DWP_CONTROL_POSTGRES_TEST_IMAGE", "postgres:16-alpine");
        if (!Set.of("postgres:16-alpine", "postgres:18.4-alpine")
                .contains(configured)) {
            throw new IllegalStateException(
                    "Control PostgreSQL test image is not an approved version");
        }
        return configured;
    }
}
