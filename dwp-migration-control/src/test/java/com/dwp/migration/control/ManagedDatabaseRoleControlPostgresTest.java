package com.dwp.migration.control;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ManagedDatabaseRoleControlPostgresTest {
    private static final String BOOTSTRAP = "managed_role_bootstrap";
    private static final String BOOTSTRAP_PASSWORD = "managed_role_bootstrap_password";
    private static final String MIGRATION = "managed_role_migration";
    private static final String MIGRATION_PASSWORD = "managed_role_migration_password";
    private static final String RUNTIME = "managed_role_runtime";
    private static final String RUNTIME_PASSWORD = "managed_role_runtime_password";
    private static final String OWNER = "dwp_approval_retention_owner";
    private static final String EXECUTOR = "dwp_approval_retention_executor";
    private static final String AUDIT_RELAY = "dwp_approval_audit_relay";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            controlPostgresImage())
            .withDatabaseName("managed_role_control")
            .withUsername(BOOTSTRAP)
            .withPassword(BOOTSTRAP_PASSWORD);

    private static ControlEnvironment environment;

    @BeforeAll
    static void provisionServiceLogins() throws Exception {
        environment = new ControlEnvironment(
                ControlEnvironment.Mode.STRICT_FRESH,
                ControlPlan.forService("approval"),
                POSTGRES.getJdbcUrl(),
                POSTGRES.getDatabaseName(),
                BOOTSTRAP,
                BOOTSTRAP_PASSWORD,
                MIGRATION,
                MIGRATION_PASSWORD,
                RUNTIME,
                RUNTIME_PASSWORD,
                "dwp-migration-control-v2:" + "a".repeat(64),
                Map.of(),
                "",
                Map.of(),
                "");
        try (Connection bootstrap = bootstrap()) {
            try (var statement = bootstrap.prepareStatement("""
                    SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', datname)
                      FROM pg_catalog.pg_database
                    """); ResultSet result = statement.executeQuery()) {
                java.util.ArrayList<String> revocations = new java.util.ArrayList<>();
                while (result.next()) {
                    revocations.add(result.getString(1));
                }
                for (String revocation : revocations) {
                    DatabaseControl.execute(bootstrap, revocation);
                }
            }
            DatabaseControl.execute(bootstrap, "CREATE ROLE " + MIGRATION
                    + " LOGIN PASSWORD '" + MIGRATION_PASSWORD + "'"
                    + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT"
                    + " NOREPLICATION NOBYPASSRLS");
            DatabaseControl.execute(bootstrap, "CREATE ROLE " + RUNTIME
                    + " LOGIN PASSWORD '" + RUNTIME_PASSWORD + "'"
                    + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT"
                    + " NOREPLICATION NOBYPASSRLS");
            DatabaseControl.execute(bootstrap, "REVOKE CONNECT,TEMPORARY ON DATABASE "
                    + POSTGRES.getDatabaseName() + " FROM PUBLIC");
            DatabaseControl.execute(bootstrap, "GRANT CONNECT ON DATABASE "
                    + POSTGRES.getDatabaseName() + " TO " + MIGRATION + "," + RUNTIME);
            DatabaseControl.execute(bootstrap, "ALTER SCHEMA public OWNER TO " + MIGRATION);
            DatabaseControl.execute(bootstrap, "REVOKE ALL ON SCHEMA public FROM PUBLIC");
            DatabaseControl.execute(bootstrap,
                    "GRANT USAGE,CREATE ON SCHEMA public TO " + MIGRATION);
            DatabaseControl.execute(bootstrap,
                    "GRANT USAGE ON SCHEMA public TO " + RUNTIME);
        }
    }

    @Test
    void bootstrapCreatesStrictRolesAndScopesTemporaryFlywayAuthority() throws Exception {
        try (Connection bootstrap = bootstrap()) {
            assertDoesNotThrow(() -> ManagedDatabaseRoleControl.requirePreflight(
                    bootstrap, environment));
            ManagedDatabaseRoleControl.provision(bootstrap, environment);
            try (Connection migration = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), MIGRATION, MIGRATION_PASSWORD)) {
                DatabaseControl.execute(migration,
                        "GRANT CREATE ON SCHEMA public TO " + OWNER);
                try {
                    ManagedDatabaseRoleControl.grantMigrationAuthority(
                            bootstrap, environment);
                    assertTrue(hasRole(bootstrap, MIGRATION, OWNER, "USAGE"));
                    assertTrue(hasRole(bootstrap, MIGRATION, OWNER, "SET"));
                    assertFalse(hasRole(bootstrap, MIGRATION, EXECUTOR, "MEMBER"));
                    assertFalse(hasRole(bootstrap, MIGRATION, AUDIT_RELAY, "MEMBER"));
                    DatabaseControl.execute(migration,
                            "CREATE TABLE public.managed_role_authority_probe(id bigint)");
                    DatabaseControl.execute(migration,
                            "ALTER TABLE public.managed_role_authority_probe OWNER TO " + OWNER);
                    DatabaseControl.execute(migration,
                            "ALTER TABLE public.managed_role_authority_probe OWNER TO " + MIGRATION);
                    DatabaseControl.execute(migration,
                            "DROP TABLE public.managed_role_authority_probe");
                } finally {
                    try {
                        ManagedDatabaseRoleControl.revokeMigrationAuthority(
                                bootstrap, environment);
                    } finally {
                        DatabaseControl.execute(migration,
                                "REVOKE CREATE ON SCHEMA public FROM " + OWNER);
                    }
                }
            }
            assertFalse(hasRole(bootstrap, MIGRATION, OWNER, "MEMBER"));
            assertFalse(hasSchemaPrivilege(bootstrap, OWNER, "public", "USAGE"));
            assertFalse(hasSchemaPrivilege(bootstrap, OWNER, "public", "CREATE"));
            assertStrictNoLogin(bootstrap, OWNER);
            assertStrictNoLogin(bootstrap, EXECUTOR);
            assertStrictNoLogin(bootstrap, AUDIT_RELAY);
        }
    }

    @Test
    void partialOrUnexpectedMembershipStateFailsClosed() throws Exception {
        try (Connection bootstrap = bootstrap()) {
            ManagedDatabaseRoleControl.provision(bootstrap, environment);
            ManagedDatabaseRoleControl.grantMigrationAuthority(bootstrap, environment);
            ManagedDatabaseRoleControl.revokeMigrationAuthority(bootstrap, environment);
            DatabaseControl.execute(bootstrap,
                    "GRANT " + EXECUTOR + " TO " + RUNTIME
                            + " WITH ADMIN FALSE, INHERIT FALSE, SET TRUE");
            try {
                IllegalStateException failure = assertThrows(
                        IllegalStateException.class,
                        () -> ManagedDatabaseRoleControl.requirePreflight(
                                bootstrap, environment));
                assertTrue(failure.getMessage().contains("memberships are not exact"));
            } finally {
                DatabaseControl.execute(bootstrap,
                        "REVOKE " + EXECUTOR + " FROM " + RUNTIME + " CASCADE");
            }
        }
    }

    @Test
    void protectedPrivateSchemaAclRejectsPublicAndExecutorCreate() throws Exception {
        try (Connection bootstrap = bootstrap()) {
            ManagedDatabaseRoleControl.provision(bootstrap, environment);
            DatabaseControl.execute(bootstrap,
                    "CREATE SCHEMA apr_retention_internal AUTHORIZATION " + OWNER);
            try {
                DatabaseControl.execute(bootstrap,
                        "REVOKE ALL ON SCHEMA apr_retention_internal FROM PUBLIC");
                DatabaseControl.execute(bootstrap,
                        "GRANT USAGE ON SCHEMA apr_retention_internal TO " + EXECUTOR);
                assertDoesNotThrow(() -> ProtectedSchemaAclControl.verify(
                        bootstrap, environment));

                DatabaseControl.execute(bootstrap,
                        "GRANT CREATE ON SCHEMA apr_retention_internal TO " + EXECUTOR);
                IllegalStateException executorCreate = assertThrows(
                        IllegalStateException.class,
                        () -> ProtectedSchemaAclControl.verify(bootstrap, environment));
                assertTrue(executorCreate.getMessage().contains("ACL"));

                DatabaseControl.execute(bootstrap,
                        "REVOKE CREATE ON SCHEMA apr_retention_internal FROM " + EXECUTOR);
                DatabaseControl.execute(bootstrap,
                        "GRANT USAGE ON SCHEMA apr_retention_internal TO PUBLIC");
                IllegalStateException publicGrant = assertThrows(
                        IllegalStateException.class,
                        () -> ProtectedSchemaAclControl.verify(bootstrap, environment));
                assertTrue(publicGrant.getMessage().contains("unapproved grantee"));
            } finally {
                DatabaseControl.execute(bootstrap,
                        "DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
            }
        }
    }

    @Test
    void finalAuxiliaryPublicUsageIsPresentWithoutCreateAuthority() throws Exception {
        try (Connection bootstrap = bootstrap()) {
            ManagedDatabaseRoleControl.provision(bootstrap, environment);
            try {
                try (Connection migration = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATION, MIGRATION_PASSWORD)) {
                    ManagedDatabaseRoleControl.normalizeFinalSchemaUsage(
                            migration, environment);
                }
                assertTrue(hasSchemaPrivilege(bootstrap, OWNER, "public", "USAGE"));
                assertTrue(hasSchemaPrivilege(bootstrap, EXECUTOR, "public", "USAGE"));
                assertTrue(hasSchemaPrivilege(
                        bootstrap, AUDIT_RELAY, "public", "USAGE"));
                assertFalse(hasSchemaPrivilege(bootstrap, OWNER, "public", "CREATE"));
                assertFalse(hasSchemaPrivilege(bootstrap, EXECUTOR, "public", "CREATE"));
                assertFalse(hasSchemaPrivilege(
                        bootstrap, AUDIT_RELAY, "public", "CREATE"));
                assertDoesNotThrow(() -> ManagedDatabaseRoleControl.requireFinalSchemaUsage(
                        bootstrap, environment));
                IllegalStateException missingMigrationOwnedRoutine = assertThrows(
                        IllegalStateException.class,
                        () -> ProtectedSchemaAclControl.verifyFinal(bootstrap, environment));
                assertTrue(missingMigrationOwnedRoutine.getMessage().contains(
                        "system_sla_witness_canonical_json"));
            } finally {
                DatabaseControl.execute(bootstrap,
                        "REVOKE ALL ON SCHEMA public FROM " + OWNER + "," + EXECUTOR
                                + "," + AUDIT_RELAY);
            }
        }
    }

    @Test
    void appliedV24PostCheckFailureRetryKeepsEveryCapabilityClosed() throws Exception {
        DatabaseCreateMigration capability =
                DatabaseCreateMigration.APPROVAL_RETENTION_FOUNDATION;
        StreamPlan stream = environment.plan().streams().getFirst();
        try (Connection bootstrap = bootstrap()) {
            ManagedDatabaseRoleControl.provision(bootstrap, environment);
            DatabaseControl.execute(bootstrap, "REVOKE CONNECT ON DATABASE "
                    + POSTGRES.getDatabaseName() + " FROM " + RUNTIME);
            DatabaseControl.execute(bootstrap, "CREATE TABLE public.flyway_schema_history ("
                    + "installed_rank integer PRIMARY KEY, version varchar(50), "
                    + "description varchar(200), script varchar(1000), checksum integer, "
                    + "installed_by varchar(100), success boolean)");
            try {
                try (var insert = bootstrap.prepareStatement("""
                        INSERT INTO public.flyway_schema_history(
                            installed_rank,version,description,script,checksum,
                            installed_by,success)
                        VALUES (1,?,?,?,?,?,true)
                        """)) {
                    insert.setString(1, capability.version());
                    insert.setString(2, capability.description());
                    insert.setString(3, capability.fileName());
                    insert.setInt(4, capability.checksum());
                    insert.setString(5, MIGRATION);
                    insert.executeUpdate();
                }
                DatabaseControl.execute(bootstrap,
                        "CREATE SCHEMA apr_retention_internal AUTHORIZATION " + OWNER);
                DatabaseControl.execute(bootstrap,
                        "REVOKE ALL ON SCHEMA apr_retention_internal FROM PUBLIC");
                DatabaseControl.execute(bootstrap,
                        "GRANT USAGE ON SCHEMA apr_retention_internal TO " + EXECUTOR);
                DatabaseControl.execute(bootstrap,
                        "GRANT CREATE ON SCHEMA apr_retention_internal TO " + EXECUTOR);

                IllegalStateException failedPostCheck = assertThrows(
                        IllegalStateException.class,
                        () -> DatabaseCreateMigrationControl.requireAppliedState(
                                bootstrap, stream, environment, capability));
                assertTrue(failedPostCheck.getMessage().contains("ACL"));
                assertCapabilityClosed(bootstrap, false);

                DatabaseControl.execute(bootstrap,
                        "REVOKE CREATE ON SCHEMA apr_retention_internal FROM " + EXECUTOR);
                assertDoesNotThrow(() -> DatabaseCreateMigrationControl.requireAppliedState(
                        bootstrap, stream, environment, capability));
                assertDoesNotThrow(() -> DatabaseCreateMigrationControl.requireAppliedState(
                        bootstrap, stream, environment, capability));
                assertCapabilityClosed(bootstrap, false);

                try (Connection migration = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATION, MIGRATION_PASSWORD)) {
                    ManagedDatabaseRoleControl.normalizeFinalSchemaUsage(
                            migration, environment);
                }
                assertDoesNotThrow(() -> DatabaseCreateMigrationControl.requireAppliedState(
                        bootstrap, stream, environment, capability));
                assertCapabilityClosed(bootstrap, true);
            } finally {
                DatabaseControl.execute(bootstrap,
                        "REVOKE ALL ON SCHEMA public FROM " + OWNER + "," + EXECUTOR
                                + "," + AUDIT_RELAY);
                DatabaseControl.execute(bootstrap,
                        "DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
                DatabaseControl.execute(bootstrap,
                        "DROP TABLE IF EXISTS public.flyway_schema_history");
                DatabaseControl.execute(bootstrap, "GRANT CONNECT ON DATABASE "
                        + POSTGRES.getDatabaseName() + " TO " + RUNTIME);
            }
        }
    }

    private static void assertCapabilityClosed(
            Connection connection, boolean steadyOwnerUsage) throws Exception {
        assertEquals("f:f:" + (steadyOwnerUsage ? "t" : "f") + ":f",
                DatabaseControl.scalar(connection, """
                SELECT concat_ws(':',
                           pg_catalog.has_database_privilege(
                               'managed_role_migration',current_database(),'CREATE'),
                           pg_catalog.pg_has_role(
                               'managed_role_migration','dwp_approval_retention_owner','MEMBER'),
                           pg_catalog.has_schema_privilege(
                               'dwp_approval_retention_owner','public','USAGE'),
                           pg_catalog.has_schema_privilege(
                               'dwp_approval_retention_owner','public','CREATE'))
                """));
    }

    private static boolean hasRole(
            Connection connection, String member, String role, String privilege)
            throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT pg_catalog.pg_has_role(CAST(? AS name),CAST(? AS name),?)")) {
            statement.setString(1, member);
            statement.setString(2, role);
            statement.setString(3, privilege);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static void assertStrictNoLogin(Connection connection, String role)
            throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT concat_ws(':',rolcanlogin,rolsuper,rolcreatedb,rolcreaterole,
                       rolinherit,rolreplication,rolbypassrls)
                  FROM pg_catalog.pg_roles WHERE rolname=?
                """)) {
            statement.setString(1, role);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("f:f:f:f:f:f:f", result.getString(1));
            }
        }
    }

    private static boolean hasSchemaPrivilege(
            Connection connection, String role, String schema, String privilege)
            throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT pg_catalog.has_schema_privilege("
                        + "CAST(? AS name),CAST(? AS name),?)")) {
            statement.setString(1, role);
            statement.setString(2, schema);
            statement.setString(3, privilege);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static Connection bootstrap() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), BOOTSTRAP, BOOTSTRAP_PASSWORD);
    }

    private static String controlPostgresImage() {
        String image = System.getenv().getOrDefault(
                "DWP_CONTROL_POSTGRES_TEST_IMAGE", "postgres:18.4-alpine");
        if (!java.util.Set.of("postgres:16-alpine", "postgres:18.4-alpine")
                .contains(image)) {
            throw new IllegalArgumentException("Unapproved Control test PostgreSQL image");
        }
        return image;
    }
}
