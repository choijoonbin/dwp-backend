package com.dwp.migration.control;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Exact per-database role defaults used by every strict Control principal. */
final class RoleSearchPathFence {
    private static final String SERVICE_PATH = "search_path=pg_catalog, public";
    private static final String PUBLISHER_PATH = "search_path=pg_catalog, public";
    private static final String METADATA_PATH = "search_path=pg_catalog";

    private RoleSearchPathFence() {
    }

    static void normalize(Connection connection, ControlEnvironment environment)
            throws SQLException {
        List<String> servicePrincipals = new ArrayList<>(List.of(
                environment.runtimePrincipal(), environment.migrationPrincipal()));
        ControlSql.execute(connection, "ALTER DATABASE "
                + ControlValues.quoteIdentifier(environment.database()) + " RESET ALL");
        for (String principal : servicePrincipals) {
            ControlSql.execute(connection, "ALTER ROLE "
                    + ControlValues.quoteIdentifier(principal) + " RESET ALL");
            ControlSql.execute(connection, "ALTER ROLE "
                    + ControlValues.quoteIdentifier(principal) + " IN DATABASE "
                    + ControlValues.quoteIdentifier(environment.database())
                    + " SET search_path TO pg_catalog, public");
        }
        for (String principal : environment.plan().fencedReadOnlyPrincipals()) {
            ControlSql.execute(connection, "ALTER ROLE "
                    + ControlValues.quoteIdentifier(principal) + " RESET ALL");
            ControlSql.execute(connection, "ALTER ROLE "
                    + ControlValues.quoteIdentifier(principal) + " IN DATABASE "
                    + ControlValues.quoteIdentifier(environment.database())
                    + " SET search_path TO pg_catalog");
        }
        if (environment.hasProjectionPublisher()) {
            String principal = environment.projectionPublisherPrincipal();
            ControlSql.execute(connection, "ALTER ROLE "
                    + ControlValues.quoteIdentifier(principal) + " RESET ALL");
            ControlSql.execute(connection, "ALTER ROLE "
                    + ControlValues.quoteIdentifier(principal) + " IN DATABASE "
                    + ControlValues.quoteIdentifier(environment.database())
                    + " SET search_path TO pg_catalog, public");
        }
        requireExact(connection, environment);
    }

    static void requireExact(Connection connection, ControlEnvironment environment)
            throws SQLException {
        List<String> principals = new ArrayList<>();
        principals.add(environment.runtimePrincipal());
        principals.add(environment.migrationPrincipal());
        principals.addAll(environment.plan().fencedReadOnlyPrincipals());
        if (environment.hasProjectionPublisher()) {
            principals.add(environment.projectionPublisherPrincipal());
        }

        List<String> actual = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(database.datname, '*'),
                       COALESCE(role.rolname, '*'),
                       setting.value
                  FROM pg_catalog.pg_db_role_setting configured
                  LEFT JOIN pg_catalog.pg_database database
                    ON database.oid=configured.setdatabase
                  LEFT JOIN pg_catalog.pg_roles role
                    ON role.oid=configured.setrole
                 CROSS JOIN LATERAL unnest(configured.setconfig) setting(value)
                 WHERE configured.setrole IN (
                           SELECT oid FROM pg_catalog.pg_roles
                            WHERE rolname = ANY (?))
                    OR (configured.setdatabase=(
                            SELECT oid FROM pg_catalog.pg_database
                             WHERE datname=current_database())
                        AND configured.setrole=0)
                 ORDER BY 1, 2, 3
                """)) {
            statement.setArray(1, connection.createArrayOf(
                    "text", principals.toArray(String[]::new)));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    actual.add(row(
                            result.getString(1),
                            result.getString(2),
                            result.getString(3)));
                }
            }
        }

        List<String> expected = new ArrayList<>();
        expected.add(row(
                environment.database(), environment.runtimePrincipal(), SERVICE_PATH));
        expected.add(row(
                environment.database(), environment.migrationPrincipal(), SERVICE_PATH));
        if (environment.hasProjectionPublisher()) {
            expected.add(row(
                    environment.database(),
                    environment.projectionPublisherPrincipal(),
                    PUBLISHER_PATH));
        }
        for (String principal : environment.plan().fencedReadOnlyPrincipals()) {
            expected.add(row(environment.database(), principal, METADATA_PATH));
        }
        expected.sort(Comparator.naturalOrder());
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                    "Migration Control role/database search_path settings are not exact; "
                            + "expected=" + expected + "; actual=" + actual);
        }
    }

    private static String row(String database, String principal, String setting) {
        return database + ":" + principal + ":" + setting;
    }
}
