package com.dwp.migration.control;

import static com.dwp.migration.control.ControlValues.quoteIdentifier;
import static com.dwp.migration.control.ControlValues.quoteLiteral;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.dwp.core.database.MigrationAdoptionGuard;

final class DatabaseControl {
    private DatabaseControl() {
    }

    static void verifyIdentity(Connection connection, ControlEnvironment environment)
            throws SQLException {
        requireScalar(connection, "SELECT current_database()", environment.database());
        requireScalar(connection, "SELECT current_user", environment.bootstrapPrincipal());
        requireScalar(
                connection,
                "SELECT pg_catalog.pg_get_userbyid(datdba) FROM pg_catalog.pg_database "
                        + "WHERE datname = current_database()",
                environment.bootstrapPrincipal());
        String fencedPrincipals = allServicePrincipals(environment).stream()
                .map(ControlValues::quoteLiteral)
                .collect(java.util.stream.Collectors.joining(", "));
        long foreignSessions = scalarLong(connection, """
                SELECT COUNT(*)
                  FROM pg_catalog.pg_stat_activity
                 WHERE datname = current_database()
                   AND pid <> pg_backend_pid()
                   AND usename IN (%s)
                """.formatted(
                        fencedPrincipals));
        if (foreignSessions != 0L) {
            throw new IllegalStateException(
                    "Migration Control requires all service principals offline");
        }
    }

    static void acquireExclusiveControlLock(
            Connection connection, ControlEnvironment environment) throws SQLException {
        String key = "dwp-migration-control:" + environment.database() + ":"
                + environment.plan().service();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_catalog.pg_try_advisory_lock("
                        + "pg_catalog.hashtextextended(?, 0))")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new IllegalStateException(
                            "Another Migration Control owns the database/service fence");
                }
            }
        }
        requireBootstrapIdentity(connection, environment);
        DatabaseConnectionFence.requireNoForeignSessions(connection, environment);
    }

    private static List<String> allServicePrincipals(ControlEnvironment environment) {
        List<String> principals = new ArrayList<>();
        principals.add(environment.runtimePrincipal());
        principals.add(environment.migrationPrincipal());
        principals.addAll(environment.plan().fencedReadOnlyPrincipals());
        if (environment.hasProjectionPublisher()) {
            principals.add(environment.projectionPublisherPrincipal());
        }
        return List.copyOf(principals);
    }

    static void requireBootstrapIdentity(
            Connection connection, ControlEnvironment environment) throws SQLException {
        requireScalar(connection, "SELECT current_database()", environment.database());
        requireScalar(connection, "SELECT current_user", environment.bootstrapPrincipal());
        requireScalar(connection, "SELECT session_user", environment.bootstrapPrincipal());
        requireScalar(
                connection,
                "SELECT pg_catalog.pg_get_userbyid(datdba) FROM pg_catalog.pg_database "
                        + "WHERE datname = current_database()",
                environment.bootstrapPrincipal());
    }

    static void requireStrictServiceLoginRoles(
            Connection connection, ControlEnvironment environment) throws SQLException {
        requireStrictLoginRole(
                connection, environment.migrationPrincipal(), false);
        requireStrictLoginRole(
                connection, environment.runtimePrincipal(), false);
        if (environment.hasProjectionPublisher()) {
            requireStrictLoginRole(
                    connection, environment.projectionPublisherPrincipal(), true);
        }
    }

    private static void requireStrictLoginRole(
            Connection connection, String principal, boolean requireEmptyRoleConfig)
            throws SQLException {
        String configProjection = requireEmptyRoleConfig
                ? ", rolconfig IS NULL"
                : "";
        String attributes = scalar(connection, ("""
                SELECT concat_ws(':', rolcanlogin, rolsuper, rolcreatedb,
                       rolcreaterole, rolinherit, rolreplication, rolbypassrls,
                       rolconnlimit, rolvaliduntil IS NULL%s)
                  FROM pg_catalog.pg_roles
                 WHERE rolname=%s
                """).formatted(configProjection, quoteLiteral(principal)));
        String expected = requireEmptyRoleConfig
                ? "t:f:f:f:f:f:f:-1:t:t"
                : "t:f:f:f:f:f:f:-1:t";
        if (!expected.equals(attributes)) {
            throw new IllegalStateException(
                    "Migration Control service login attributes are not strict: "
                            + principal);
        }
    }

    static void revokeTemporary(Connection connection, ControlEnvironment environment)
            throws SQLException {
        execute(connection, "REVOKE TEMPORARY ON DATABASE "
                + quoteIdentifier(environment.database()) + " FROM "
                + quoteIdentifier(environment.migrationPrincipal()));
    }

    static void grantTemporary(Connection connection, ControlEnvironment environment)
            throws SQLException {
        if (environment.plan().temporaryMigrationVersions().isEmpty()) {
            throw new IllegalStateException(
                    "Temporary migration privilege is not approved for this service");
        }
        execute(connection, "GRANT TEMPORARY ON DATABASE "
                + quoteIdentifier(environment.database()) + " TO "
                + quoteIdentifier(environment.migrationPrincipal()));
    }

    static void requireTemporaryDenied(
            Connection connection, ControlEnvironment environment) throws SQLException {
        long allowed = scalarLong(connection, "SELECT CASE WHEN has_database_privilege("
                + quoteLiteral(environment.migrationPrincipal()) + ", "
                + quoteLiteral(environment.database()) + ", 'TEMPORARY') THEN 1 ELSE 0 END");
        if (allowed != 0L) {
            throw new IllegalStateException(
                    "Migration Control failed to revoke temporary privilege");
        }
    }

    static void grantDatabaseCreate(
            Connection connection, ControlEnvironment environment) throws SQLException {
        if (environment.plan().databaseCreateMigrations().isEmpty()) {
            throw new IllegalStateException(
                    "Database CREATE migration authority is not approved for this service");
        }
        execute(connection, "GRANT CREATE ON DATABASE "
                + quoteIdentifier(environment.database()) + " TO "
                + quoteIdentifier(environment.migrationPrincipal()));
    }

    static void revokeDatabaseCreate(
            Connection connection, ControlEnvironment environment) throws SQLException {
        execute(connection, "REVOKE CREATE ON DATABASE "
                + quoteIdentifier(environment.database()) + " FROM "
                + quoteIdentifier(environment.migrationPrincipal()));
    }

    static void requireDatabaseCreateDenied(
            Connection connection, ControlEnvironment environment) throws SQLException {
        long allowed = scalarLong(connection, "SELECT CASE WHEN has_database_privilege("
                + quoteLiteral(environment.migrationPrincipal()) + ", "
                + quoteLiteral(environment.database()) + ", 'CREATE') THEN 1 ELSE 0 END");
        if (allowed != 0L) {
            throw new IllegalStateException(
                    "Migration Control failed to revoke database CREATE privilege");
        }
    }

    static void requireFreshSchemaClean(
            Connection connection, StreamPlan stream, String migration) throws SQLException {
        List<MigrationAdoptionGuard.InventoryEntry> inventory =
                MigrationAdoptionGuard.liveInventory(
                        connection, ControlContracts.adoption(stream), migration);
        if (inventory.size() != 1
                || !"SCHEMA".equals(inventory.getFirst().objectClass())
                || !stream.schema().equals(inventory.getFirst().schema())) {
            throw new IllegalStateException(
                    "Fresh Control refuses a non-empty unmanaged schema");
        }
    }

    static boolean historyExists(Connection connection, StreamPlan stream)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1
                      FROM pg_catalog.pg_class relation
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=relation.relnamespace
                     WHERE namespace.nspname=?
                       AND relation.relname=?
                       AND relation.relkind IN ('r','p'))
                """)) {
            statement.setString(1, stream.schema());
            statement.setString(2, stream.historyTable());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    static int historyMax(Connection connection, StreamPlan stream) throws SQLException {
        if (!historyExists(connection, stream)) {
            return 0;
        }
        return Math.toIntExact(scalarLong(connection,
                "SELECT COALESCE(MAX(installed_rank), 0) FROM "
                        + quoteIdentifier(stream.schema()) + "."
                        + quoteIdentifier(stream.historyTable())));
    }

    static boolean versionApplied(
            Connection connection, StreamPlan stream, String version) throws SQLException {
        if (!historyExists(connection, stream)) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM " + quoteIdentifier(stream.schema()) + "."
                        + quoteIdentifier(stream.historyTable())
                        + " WHERE version = ? AND success)")) {
            statement.setString(1, version);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    static long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new IllegalStateException("Migration Control scalar returned no row");
            }
            return result.getLong(1);
        }
    }

    static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new IllegalStateException("Migration Control scalar returned no row");
            }
            return result.getString(1);
        }
    }

    static void requireScalar(
            Connection connection, String sql, String expected) throws SQLException {
        String actual = scalar(connection, sql);
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Migration Control database identity mismatch");
        }
    }

}
