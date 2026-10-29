package com.dwp.migration.control;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class FlywayControlPlatformV342PostgresTest {
    private static final String MIGRATION = "control_platform_migration";
    private static final String MIGRATION_PASSWORD = "control_platform_migration_password";
    private static final String RUNTIME = "control_platform_runtime";
    private static final String RUNTIME_PASSWORD = "control_platform_runtime_password";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void migratesTheRealPlatformPredecessorThroughV342WithoutTransactionalSelfWait()
            throws Exception {
        Path migrations = Path.of(System.getProperty(
                "dwp.test.platform.migration-directory")).toAbsolutePath().normalize();
        StreamPlan stream = new StreamPlan(
                "platform-main", "public", "flyway_schema_history",
                "filesystem:" + migrations, false, false);
        ControlPlan plan = ControlPlan.forService("platform");
        ControlEnvironment environment = new ControlEnvironment(
                ControlEnvironment.Mode.STRICT_FRESH,
                plan,
                POSTGRES.getJdbcUrl(),
                POSTGRES.getDatabaseName(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                MIGRATION,
                MIGRATION_PASSWORD,
                RUNTIME,
                RUNTIME_PASSWORD,
                "dwp-migration-control-v2:" + "a".repeat(64),
                Map.of(), "", Map.of(), "");

        provisionUnprivilegedPrincipalsAndBootstrapExtension(environment);
        try (Connection bootstrap = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            RequiredDatabaseExtensionControl.requireExact(bootstrap, environment);
        }

        MigrationVersion predecessor = FlywayControl.predecessorVersion(
                environment, stream, "342");
        assertTrue(predecessor.compareTo(MigrationVersion.fromVersion("342")) < 0);
        Flyway beforeV342 = FlywayControl.load(environment, stream, predecessor);
        assertSessionAdvisoryLock(beforeV342);
        FlywayControl.migrateAndValidate(beforeV342, false);

        Flyway throughV342 = FlywayControl.load(
                environment, stream, MigrationVersion.fromVersion("342"));
        assertSessionAdvisoryLock(throughV342);
        FlywayControl.migrateAndValidate(throughV342);

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            try (var history = statement.executeQuery(
                    "SELECT version FROM flyway_schema_history "
                            + "WHERE success ORDER BY installed_rank DESC LIMIT 1")) {
                assertTrue(history.next());
                assertEquals("342", history.getString(1));
            }
            try (var extension = statement.executeQuery(
                    "SELECT count(*) FROM pg_extension WHERE extname='pg_trgm'")) {
                extension.next();
                assertEquals(1, extension.getInt(1));
            }
            try (var index = statement.executeQuery(
                    "SELECT count(*) FROM pg_indexes "
                            + "WHERE indexname='idx_activity_search_text_trgm'")) {
                index.next();
                assertEquals(1, index.getInt(1));
            }
        }
        try (Connection bootstrap = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            RequiredDatabaseExtensionControl.requireExact(bootstrap, environment);
        }
    }

    @Test
    void keepsExistingRequiredExtensionInventoriesServiceScoped() {
        assertEquals(List.of(
                        new RequiredDatabaseExtension("btree_gist", "public", "1.7"),
                        new RequiredDatabaseExtension("pgcrypto", "public", "1.3"),
                        new RequiredDatabaseExtension("pg_trgm", "public", "1.6")),
                ControlPlan.forService("platform").requiredDatabaseExtensions());
        assertEquals(List.of(
                        new RequiredDatabaseExtension("btree_gist", "public", "1.7"),
                        new RequiredDatabaseExtension("pgcrypto", "public", "1.3")),
                ControlPlan.forService("people").requiredDatabaseExtensions());
        assertEquals(List.of(
                        new RequiredDatabaseExtension("btree_gist", "public", "1.7")),
                ControlPlan.forService("payroll").requiredDatabaseExtensions());
        assertEquals(ControlPlan.forService("payroll").requiredDatabaseExtensions(),
                ControlPlan.forService("time").requiredDatabaseExtensions());
    }

    private void provisionUnprivilegedPrincipalsAndBootstrapExtension(
            ControlEnvironment environment) throws Exception {
        try (Connection bootstrap = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = bootstrap.createStatement()) {
            statement.execute("CREATE ROLE " + MIGRATION + " LOGIN PASSWORD '"
                    + MIGRATION_PASSWORD + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");
            statement.execute("CREATE ROLE " + RUNTIME + " LOGIN PASSWORD '"
                    + RUNTIME_PASSWORD + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");
            statement.execute("REVOKE CREATE ON DATABASE " + POSTGRES.getDatabaseName()
                    + " FROM PUBLIC");
            statement.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + MIGRATION);
            statement.execute("CREATE EXTENSION btree_gist WITH SCHEMA public VERSION '1.7'");
            statement.execute("CREATE EXTENSION pgcrypto WITH SCHEMA public VERSION '1.3'");
            statement.execute("CREATE EXTENSION pg_trgm WITH SCHEMA public VERSION '1.6'");
            try (var role = statement.executeQuery(
                    "SELECT rolsuper, rolcreatedb FROM pg_roles WHERE rolname='"
                            + MIGRATION + "'")) {
                assertTrue(role.next());
                assertFalse(role.getBoolean(1));
                assertFalse(role.getBoolean(2));
            }
            try (var create = statement.executeQuery(
                    "SELECT has_database_privilege('" + MIGRATION + "', '"
                            + environment.database() + "', 'CREATE')")) {
                assertTrue(create.next());
                assertFalse(create.getBoolean(1));
            }
        }
    }

    private void assertSessionAdvisoryLock(Flyway flyway) {
        assertFalse(flyway.getConfiguration()
                .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
                .isTransactionalLock());
    }
}
