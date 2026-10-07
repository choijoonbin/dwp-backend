package com.dwp.migration.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL 16/18 proof for the non-superuser CREATEROLE grantor lifecycle. */
@Testcontainers(disabledWithoutDocker = true)
class ManagedDatabaseRoleGrantorCompatibilityPostgresTest {
    private static final String ADMIN = "managed_role_cluster_admin";
    private static final String ADMIN_PASSWORD = "managed_role_cluster_admin_password";
    private static final String BOOTSTRAP = "managed_role_nonsuper_bootstrap";
    private static final String BOOTSTRAP_PASSWORD = "managed_role_nonsuper_password";
    private static final String MIGRATION = "managed_role_nonsuper_migration";
    private static final String MIGRATION_PASSWORD = "managed_role_migration_password";
    private static final String RUNTIME = "managed_role_nonsuper_runtime";
    private static final String RUNTIME_PASSWORD = "managed_role_runtime_password";
    private static final String OWNER = "dwp_approval_retention_owner";
    private static final String EXECUTOR = "dwp_approval_retention_executor";
    private static final String AUDIT_RELAY = "dwp_approval_audit_relay";

    @Test
    void postgres16SupportsExactAdminOnlyGrantorLifecycle() throws Exception {
        assertExactLifecycle("postgres:16-alpine", "16");
    }

    @Test
    void postgres18SupportsExactAdminOnlyGrantorLifecycle() throws Exception {
        assertExactLifecycle("postgres:18.4-alpine", "18");
    }

    private static void assertExactLifecycle(String image, String expectedMajor)
            throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(image)
                .withDatabaseName("managed_role_compatibility")
                .withUsername(ADMIN)
                .withPassword(ADMIN_PASSWORD)) {
            postgres.start();
            try (Connection admin = DriverManager.getConnection(
                    postgres.getJdbcUrl(), ADMIN, ADMIN_PASSWORD)) {
                assertTrue(DatabaseControl.scalar(admin, "SHOW server_version")
                        .startsWith(expectedMajor + "."));
                DatabaseControl.execute(admin, "CREATE ROLE " + BOOTSTRAP
                        + " LOGIN PASSWORD '" + BOOTSTRAP_PASSWORD + "'"
                        + " CREATEROLE NOSUPERUSER NOCREATEDB NOINHERIT"
                        + " NOREPLICATION NOBYPASSRLS");
                DatabaseControl.execute(admin, "CREATE ROLE " + MIGRATION
                        + " LOGIN PASSWORD '" + MIGRATION_PASSWORD + "'"
                        + " NOCREATEROLE NOSUPERUSER NOCREATEDB NOINHERIT"
                        + " NOREPLICATION NOBYPASSRLS");
                DatabaseControl.execute(admin, "CREATE ROLE " + RUNTIME
                        + " LOGIN PASSWORD '" + RUNTIME_PASSWORD + "'"
                        + " NOCREATEROLE NOSUPERUSER NOCREATEDB NOINHERIT"
                        + " NOREPLICATION NOBYPASSRLS");
                DatabaseControl.execute(admin, "ALTER DATABASE "
                        + ControlValues.quoteIdentifier(postgres.getDatabaseName())
                        + " OWNER TO " + BOOTSTRAP);
                revokePublicDatabaseAuthority(admin);
                DatabaseControl.execute(admin, "GRANT CONNECT ON DATABASE "
                        + ControlValues.quoteIdentifier(postgres.getDatabaseName())
                        + " TO " + MIGRATION + "," + RUNTIME);
                DatabaseControl.execute(admin, "ALTER SCHEMA public OWNER TO " + MIGRATION);
                DatabaseControl.execute(admin, "REVOKE ALL ON SCHEMA public FROM PUBLIC");
                DatabaseControl.execute(admin,
                        "GRANT USAGE,CREATE ON SCHEMA public TO " + MIGRATION);
                DatabaseControl.execute(admin,
                        "GRANT USAGE ON SCHEMA public TO " + RUNTIME);
            }

            ControlEnvironment environment = new ControlEnvironment(
                    ControlEnvironment.Mode.STRICT_FRESH,
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
            try (Connection bootstrap = DriverManager.getConnection(
                    postgres.getJdbcUrl(), BOOTSTRAP, BOOTSTRAP_PASSWORD)) {
                assertEquals("f:t", DatabaseControl.scalar(bootstrap, """
                        SELECT concat_ws(':',rolsuper,rolcreaterole)
                          FROM pg_catalog.pg_roles WHERE rolname=current_user
                        """));

                ManagedDatabaseRoleControl.provision(bootstrap, environment);
                assertEquals("3:1:t:t", DatabaseControl.scalar(bootstrap, """
                        SELECT concat_ws(':',
                                   COUNT(*),
                                   COUNT(DISTINCT membership.grantor),
                                   bool_and(grantor.rolsuper),
                                   bool_and(membership.grantor<>member.oid))
                          FROM pg_catalog.pg_auth_members membership
                          JOIN pg_catalog.pg_roles granted
                            ON granted.oid=membership.roleid
                          JOIN pg_catalog.pg_roles member
                            ON member.oid=membership.member
                          JOIN pg_catalog.pg_roles grantor
                            ON grantor.oid=membership.grantor
                         WHERE granted.rolname IN (
                                   'dwp_approval_retention_owner',
                                   'dwp_approval_retention_executor',
                                   'dwp_approval_audit_relay')
                           AND member.rolname='managed_role_nonsuper_bootstrap'
                           AND membership.admin_option
                           AND NOT membership.inherit_option
                           AND NOT membership.set_option
                        """));

                try (Connection migration = DriverManager.getConnection(
                        postgres.getJdbcUrl(), MIGRATION, MIGRATION_PASSWORD)) {
                    DatabaseControl.execute(migration,
                            "GRANT CREATE ON SCHEMA public TO " + OWNER);
                    DatabaseControl.grantDatabaseCreate(bootstrap, environment);
                    ManagedDatabaseRoleControl.grantMigrationAuthority(
                            bootstrap, environment);
                    try {
                        assertTrue(hasRole(bootstrap, MIGRATION, OWNER, "USAGE"));
                        assertTrue(hasRole(bootstrap, MIGRATION, OWNER, "SET"));
                        assertFalse(hasRole(bootstrap, MIGRATION, EXECUTOR, "MEMBER"));
                        assertFalse(hasRole(
                                bootstrap, MIGRATION, AUDIT_RELAY, "MEMBER"));
                        assertFalse(hasSchemaPrivilege(
                                bootstrap, OWNER, "public", "USAGE"));
                        assertTrue(hasSchemaPrivilege(
                                bootstrap, OWNER, "public", "CREATE"));
                        assertEquals(1L, DatabaseControl.scalarLong(bootstrap, """
                                SELECT COUNT(*)
                                  FROM pg_catalog.pg_auth_members membership
                                  JOIN pg_catalog.pg_roles granted
                                    ON granted.oid=membership.roleid
                                  JOIN pg_catalog.pg_roles member
                                    ON member.oid=membership.member
                                  JOIN pg_catalog.pg_roles grantor
                                    ON grantor.oid=membership.grantor
                                 WHERE granted.rolname='dwp_approval_retention_owner'
                                   AND member.rolname='managed_role_nonsuper_migration'
                                   AND grantor.rolname='managed_role_nonsuper_bootstrap'
                                   AND NOT membership.admin_option
                                   AND membership.inherit_option
                                   AND membership.set_option
                                """));
                        DatabaseControl.execute(migration,
                                "CREATE SCHEMA apr_retention_internal AUTHORIZATION " + OWNER);
                        DatabaseControl.execute(migration,
                                "CREATE TABLE public.managed_role_window_probe(id bigint)");
                        DatabaseControl.execute(migration,
                                "ALTER TABLE public.managed_role_window_probe OWNER TO " + OWNER);
                        assertEquals(OWNER, DatabaseControl.scalar(bootstrap, """
                                SELECT owner.rolname
                                  FROM pg_catalog.pg_class relation
                                  JOIN pg_catalog.pg_namespace namespace
                                    ON namespace.oid=relation.relnamespace
                                  JOIN pg_catalog.pg_roles owner
                                    ON owner.oid=relation.relowner
                                 WHERE namespace.nspname='public'
                                   AND relation.relname='managed_role_window_probe'
                                """));
                        DatabaseControl.execute(migration,
                                "ALTER TABLE public.managed_role_window_probe OWNER TO "
                                        + MIGRATION);
                        DatabaseControl.execute(migration,
                                "DROP TABLE public.managed_role_window_probe");
                        DatabaseControl.execute(migration,
                                "ALTER SCHEMA apr_retention_internal OWNER TO " + MIGRATION);
                        DatabaseControl.execute(migration,
                                "DROP SCHEMA apr_retention_internal");
                    } finally {
                        ManagedDatabaseRoleControl.revokeMigrationAuthority(
                                bootstrap, environment);
                        DatabaseControl.execute(migration,
                                "REVOKE CREATE ON SCHEMA public FROM " + OWNER);
                        DatabaseControl.revokeDatabaseCreate(bootstrap, environment);
                    }
                    ManagedDatabaseRoleControl.normalizeFinalSchemaUsage(
                            migration, environment);
                }
                assertFalse(hasRole(bootstrap, MIGRATION, OWNER, "MEMBER"));
                assertTrue(hasSchemaPrivilege(bootstrap, OWNER, "public", "USAGE"));
                assertTrue(hasSchemaPrivilege(bootstrap, EXECUTOR, "public", "USAGE"));
                assertTrue(hasSchemaPrivilege(
                        bootstrap, AUDIT_RELAY, "public", "USAGE"));
                assertFalse(hasSchemaPrivilege(bootstrap, OWNER, "public", "CREATE"));
                assertFalse(hasSchemaPrivilege(bootstrap, EXECUTOR, "public", "CREATE"));
                assertFalse(hasSchemaPrivilege(
                        bootstrap, AUDIT_RELAY, "public", "CREATE"));
                DatabaseControl.requireDatabaseCreateDenied(bootstrap, environment);
                assertEquals(3L, DatabaseControl.scalarLong(bootstrap, """
                        SELECT COUNT(*)
                          FROM pg_catalog.pg_auth_members membership
                          JOIN pg_catalog.pg_roles granted
                            ON granted.oid=membership.roleid
                          JOIN pg_catalog.pg_roles member
                            ON member.oid=membership.member
                         WHERE granted.rolname IN (
                                   'dwp_approval_retention_owner',
                                   'dwp_approval_retention_executor',
                                   'dwp_approval_audit_relay')
                           AND member.rolname='managed_role_nonsuper_bootstrap'
                        """));
                ManagedDatabaseRoleControl.requirePreflight(bootstrap, environment);
            }
        }
    }

    private static void revokePublicDatabaseAuthority(Connection admin) throws Exception {
        ArrayList<String> statements = new ArrayList<>();
        try (var query = admin.prepareStatement("""
                SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', datname)
                  FROM pg_catalog.pg_database
                """); ResultSet result = query.executeQuery()) {
            while (result.next()) {
                statements.add(result.getString(1));
            }
        }
        for (String statement : statements) {
            DatabaseControl.execute(admin, statement);
        }
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
}
