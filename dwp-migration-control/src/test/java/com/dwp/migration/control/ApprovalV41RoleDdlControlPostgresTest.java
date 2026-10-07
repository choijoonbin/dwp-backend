package com.dwp.migration.control;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL proof for the exact approval V41 role-DDL lifecycle. */
@Testcontainers(disabledWithoutDocker = true)
class ApprovalV41RoleDdlControlPostgresTest {
    private static final String BOOTSTRAP = "approval_v41_bootstrap";
    private static final String BOOTSTRAP_PASSWORD = "approval_v41_bootstrap_password";
    private static final String MIGRATION = "approval_v41_migration";
    private static final String MIGRATION_PASSWORD = "approval_v41_migration_password";
    private static final String RUNTIME = "approval_v41_runtime";
    private static final String RUNTIME_PASSWORD = "approval_v41_runtime_password";
    private static final String OWNER = "dwp_approval_retention_owner";
    private static final String EXECUTOR = "dwp_approval_retention_executor";
    private static final String AUDIT_RELAY = "dwp_approval_audit_relay";

    @Test
    void v41UsesOneExactSuperuserWindowAndLeavesExactRelayAcl() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(postgresImage())
                .withDatabaseName("approval_v41_control")
                .withUsername(BOOTSTRAP)
                .withPassword(BOOTSTRAP_PASSWORD)) {
            postgres.start();
            ControlEnvironment environment = environment(postgres);
            try (Connection bootstrap = DriverManager.getConnection(
                    postgres.getJdbcUrl(), BOOTSTRAP, BOOTSTRAP_PASSWORD)) {
                provisionPreV41State(bootstrap, postgres);

                assertDoesNotThrow(() -> ManagedDatabaseRoleControl.requirePreflight(
                        bootstrap, environment));
                assertFalse(roleExists(bootstrap, AUDIT_RELAY));
                ManagedDatabaseRoleControl.provision(bootstrap, environment);
                assertTrue(roleExists(bootstrap, AUDIT_RELAY));

                DatabaseCreateMigration capability =
                        DatabaseCreateMigration.APPROVAL_NATIVE_OPERATIONS_RETENTION;
                TemporaryRoleDdlAuthorityControl.activate(
                        bootstrap, environment, capability);
                try (Connection migration = DriverManager.getConnection(
                        postgres.getJdbcUrl(), MIGRATION, MIGRATION_PASSWORD)) {
                    try {
                        DatabaseControl.execute(migration, "ALTER ROLE " + AUDIT_RELAY
                                + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE"
                                + " NOREPLICATION NOBYPASSRLS");
                        DatabaseControl.execute(migration,
                                "GRANT " + AUDIT_RELAY + " TO " + MIGRATION);
                        TemporaryRoleDdlAuthorityControl.requireMigrationGrant(
                                bootstrap, environment, capability);
                    } finally {
                        TemporaryRoleDdlAuthorityControl.deactivateRoleAttributes(
                                bootstrap, environment, capability);
                        TemporaryRoleDdlAuthorityControl.revokeMigrationMembership(
                                bootstrap, environment, capability);
                    }
                    SQLException denied = assertThrows(
                            SQLException.class,
                            () -> DatabaseControl.execute(
                                    migration, "ALTER ROLE " + AUDIT_RELAY + " NOLOGIN"));
                    assertEquals("42501", denied.getSQLState());
                    createAuditOutbox(migration);
                }
                TemporaryRoleDdlAuthorityControl.requireClosed(
                        bootstrap, environment, capability);
                ManagedDatabaseRoleControl.requireSteadyPostMigration(
                        bootstrap, environment);

                IllegalStateException injectedFailure = assertThrows(
                        IllegalStateException.class,
                        () -> {
                            TemporaryRoleDdlAuthorityControl.activate(
                                    bootstrap, environment, capability);
                            try {
                                throw new IllegalStateException("injected V41 failure");
                            } finally {
                                TemporaryRoleDdlAuthorityControl.deactivateRoleAttributes(
                                        bootstrap, environment, capability);
                                TemporaryRoleDdlAuthorityControl.revokeMigrationMembership(
                                        bootstrap, environment, capability);
                            }
                        });
                assertEquals("injected V41 failure", injectedFailure.getMessage());
                TemporaryRoleDdlAuthorityControl.requireClosed(
                        bootstrap, environment, capability);
                TemporaryRoleDdlAuthorityControl.requireClosed(
                        bootstrap, environment, capability);
                createRelayRoutineAndAcl(bootstrap);
                assertDoesNotThrow(() -> ProtectedSchemaAclControl.verify(
                        bootstrap, environment));

                DatabaseControl.execute(bootstrap,
                        "GRANT INSERT ON public.sys_audit_outbox TO " + AUDIT_RELAY);
                try {
                    IllegalStateException excessive = assertThrows(
                            IllegalStateException.class,
                            () -> ProtectedSchemaAclControl.verify(
                                    bootstrap, environment));
                    assertTrue(excessive.getMessage().contains("auxiliary-role ACL"));
                } finally {
                    DatabaseControl.execute(bootstrap,
                            "REVOKE INSERT ON public.sys_audit_outbox FROM "
                                    + AUDIT_RELAY);
                }
            }
        }
    }

    private static void provisionPreV41State(
            Connection bootstrap, PostgreSQLContainer<?> postgres) throws Exception {
        revokePublicDatabaseAuthority(bootstrap);
        DatabaseControl.execute(bootstrap, "CREATE ROLE " + MIGRATION
                + " LOGIN PASSWORD '" + MIGRATION_PASSWORD + "'"
                + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT"
                + " NOREPLICATION NOBYPASSRLS");
        DatabaseControl.execute(bootstrap, "CREATE ROLE " + RUNTIME
                + " LOGIN PASSWORD '" + RUNTIME_PASSWORD + "'"
                + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT"
                + " NOREPLICATION NOBYPASSRLS");
        DatabaseControl.execute(bootstrap, "CREATE ROLE " + OWNER
                + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT"
                + " NOREPLICATION NOBYPASSRLS CONNECTION LIMIT -1");
        DatabaseControl.execute(bootstrap, "CREATE ROLE " + EXECUTOR
                + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT"
                + " NOREPLICATION NOBYPASSRLS CONNECTION LIMIT -1");
        DatabaseControl.execute(bootstrap, "GRANT CONNECT ON DATABASE "
                + ControlValues.quoteIdentifier(postgres.getDatabaseName())
                + " TO " + MIGRATION + "," + RUNTIME);
        DatabaseControl.execute(bootstrap, "ALTER SCHEMA public OWNER TO " + MIGRATION);
        DatabaseControl.execute(bootstrap, "REVOKE ALL ON SCHEMA public FROM PUBLIC");
        DatabaseControl.execute(bootstrap,
                "GRANT USAGE,CREATE ON SCHEMA public TO " + MIGRATION);
        DatabaseControl.execute(bootstrap,
                "GRANT USAGE ON SCHEMA public TO " + RUNTIME);
        DatabaseControl.execute(bootstrap,
                "CREATE SCHEMA apr_retention_internal AUTHORIZATION " + OWNER);
        DatabaseControl.execute(bootstrap, "CREATE TABLE public.flyway_schema_history ("
                + "installed_rank integer PRIMARY KEY, version varchar(50), success boolean)");
        DatabaseControl.execute(bootstrap,
                "INSERT INTO public.flyway_schema_history VALUES (1,'24',true)");
    }

    private static void createAuditOutbox(Connection migration) throws SQLException {
        DatabaseControl.execute(migration, """
                CREATE TABLE public.sys_audit_outbox(
                    status text,
                    attempt_count integer,
                    available_at timestamptz,
                    locked_by text,
                    locked_until timestamptz,
                    last_error text,
                    published_at timestamptz,
                    updated_at timestamptz)
                """);
    }

    private static void createRelayRoutineAndAcl(Connection bootstrap) throws SQLException {
        DatabaseControl.execute(bootstrap, """
                CREATE FUNCTION apr_retention_internal.audit_cleanup_eligible(p_row jsonb)
                RETURNS boolean LANGUAGE sql IMMUTABLE AS 'SELECT true'
                """);
        DatabaseControl.execute(bootstrap, "ALTER FUNCTION "
                + "apr_retention_internal.audit_cleanup_eligible(jsonb) OWNER TO " + OWNER);
        DatabaseControl.execute(bootstrap, "REVOKE EXECUTE ON FUNCTION "
                + "apr_retention_internal.audit_cleanup_eligible(jsonb) FROM PUBLIC");
        DatabaseControl.execute(bootstrap,
                "GRANT USAGE ON SCHEMA public,apr_retention_internal TO " + AUDIT_RELAY);
        DatabaseControl.execute(bootstrap,
                "GRANT SELECT,DELETE ON public.sys_audit_outbox TO " + AUDIT_RELAY);
        DatabaseControl.execute(bootstrap, "GRANT UPDATE(status,attempt_count,available_at,"
                + "locked_by,locked_until,last_error,published_at,updated_at)"
                + " ON public.sys_audit_outbox TO " + AUDIT_RELAY);
        DatabaseControl.execute(bootstrap, "GRANT EXECUTE ON FUNCTION "
                + "apr_retention_internal.audit_cleanup_eligible(jsonb) TO " + AUDIT_RELAY);
    }

    private static boolean roleExists(Connection connection, String role) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM pg_catalog.pg_roles WHERE rolname=?)")) {
            statement.setString(1, role);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static void revokePublicDatabaseAuthority(Connection bootstrap) throws Exception {
        ArrayList<String> revocations = new ArrayList<>();
        try (var statement = bootstrap.prepareStatement("""
                SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', datname)
                  FROM pg_catalog.pg_database
                """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                revocations.add(result.getString(1));
            }
        }
        for (String revocation : revocations) {
            DatabaseControl.execute(bootstrap, revocation);
        }
    }

    private static ControlEnvironment environment(PostgreSQLContainer<?> postgres) {
        return new ControlEnvironment(
                ControlEnvironment.Mode.ADOPT_OR_UPGRADE,
                ControlPlan.forService("approval"),
                postgres.getJdbcUrl(),
                postgres.getDatabaseName(),
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
    }

    private static String postgresImage() {
        String image = System.getenv().getOrDefault(
                "DWP_CONTROL_POSTGRES_TEST_IMAGE", "postgres:18.4-alpine");
        if (!java.util.Set.of("postgres:16-alpine", "postgres:18.4-alpine")
                .contains(image)) {
            throw new IllegalArgumentException("Unapproved Control test PostgreSQL image");
        }
        return image;
    }
}
