package com.dwp.migration.control;

import static com.dwp.migration.control.ControlValues.quoteIdentifier;
import static com.dwp.migration.control.ControlValues.quoteLiteral;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Removes every service-side path that could disable trigger enforcement. */
final class SessionReplicationRoleControl {
    private static final String PARAMETER = "session_replication_role";

    private SessionReplicationRoleControl() {
    }

    static void normalize(Connection connection, ControlEnvironment environment)
            throws SQLException {
        List<String> principals = existingPrincipals(connection, environment);
        String principalList = principals.stream()
                .map(ControlValues::quoteIdentifier)
                .collect(java.util.stream.Collectors.joining(", "));
        ControlSql.execute(connection,
                "REVOKE ALL PRIVILEGES ON PARAMETER " + PARAMETER
                        + " FROM PUBLIC, " + principalList);
        ControlSql.execute(connection, "ALTER DATABASE "
                + quoteIdentifier(environment.database())
                + " RESET " + PARAMETER);
        for (String principal : principals) {
            ControlSql.execute(connection, "ALTER ROLE "
                    + quoteIdentifier(principal) + " RESET " + PARAMETER);
            ControlSql.execute(connection, "ALTER ROLE "
                    + quoteIdentifier(principal) + " IN DATABASE "
                    + quoteIdentifier(environment.database())
                    + " RESET " + PARAMETER);
        }
        requireExact(connection, environment);
    }

    static void requireExact(Connection connection, ControlEnvironment environment)
            throws SQLException {
        if (!"origin".equals(ControlSql.scalar(
                connection, "SELECT current_setting('" + PARAMETER + "')"))) {
            throw new IllegalStateException(
                    "Migration Control session_replication_role must be origin");
        }
        List<String> principals = principals(environment);
        Set<String> relevant = new LinkedHashSet<>(principals);
        relevant.add("PUBLIC");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT parameter_acl.parname || ':' || acl.privilege_type || ':'
                       || COALESCE(grantee.rolname, 'PUBLIC')
                       || CASE WHEN acl.is_grantable THEN ':GRANTABLE' ELSE '' END
                  FROM pg_catalog.pg_parameter_acl parameter_acl
                 CROSS JOIN LATERAL pg_catalog.aclexplode(parameter_acl.paracl) acl
                  LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid=acl.grantee
                 WHERE parameter_acl.parname=?
                   AND (acl.grantee=0 OR grantee.rolname=ANY (?::text[]))
                 ORDER BY 1
                """)) {
            statement.setString(1, PARAMETER);
            statement.setArray(2, connection.createArrayOf(
                    "text", principals.toArray(String[]::new)));
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new IllegalStateException(
                            "Migration Control parameter authority is not zero: "
                                    + result.getString(1));
                }
            }
        }
        requireNoConfiguredOverride(connection, environment, relevant);
    }

    private static void requireNoConfiguredOverride(
            Connection connection,
            ControlEnvironment environment,
            Set<String> principals) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(database.datname, '*') || ':'
                       || COALESCE(role.rolname, '*') || ':' || setting.value
                  FROM pg_catalog.pg_db_role_setting configured
                  LEFT JOIN pg_catalog.pg_database database
                    ON database.oid=configured.setdatabase
                  LEFT JOIN pg_catalog.pg_roles role ON role.oid=configured.setrole
                 CROSS JOIN LATERAL unnest(configured.setconfig) setting(value)
                 WHERE lower(split_part(setting.value, '=', 1))=?
                   AND (role.rolname=ANY (?::text[])
                        OR (configured.setdatabase=(
                                SELECT oid FROM pg_catalog.pg_database
                                 WHERE datname=?)
                            AND configured.setrole=0))
                 ORDER BY 1
                """)) {
            statement.setString(1, PARAMETER);
            statement.setArray(2, connection.createArrayOf(
                    "text", principals.stream()
                            .filter(principal -> !"PUBLIC".equals(principal))
                            .toArray(String[]::new)));
            statement.setString(3, environment.database());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new IllegalStateException(
                            "Migration Control session_replication_role setting is not empty: "
                                    + result.getString(1));
                }
            }
        }
    }

    private static List<String> principals(ControlEnvironment environment) {
        Set<String> principals = new LinkedHashSet<>();
        principals.add(environment.runtimePrincipal());
        principals.add(environment.migrationPrincipal());
        principals.addAll(environment.plan().fencedReadOnlyPrincipals());
        principals.addAll(environment.plan().managedRoleNames());
        if (environment.hasProjectionPublisher()) {
            principals.add(environment.projectionPublisherPrincipal());
        }
        if ("notification".equals(environment.plan().service())) {
            principals.addAll(List.of(
                    "dwp_notification_api",
                    "dwp_notification_worker",
                    "dwp_notification_audit_relay"));
        }
        return List.copyOf(new ArrayList<>(principals));
    }

    private static List<String> existingPrincipals(
            Connection connection, ControlEnvironment environment) throws SQLException {
        List<String> candidates = principals(environment);
        List<String> existing = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT rolname
                  FROM pg_catalog.pg_roles
                 WHERE rolname=ANY (?::text[])
                 ORDER BY rolname
                """)) {
            statement.setArray(1, connection.createArrayOf(
                    "text", candidates.toArray(String[]::new)));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    existing.add(result.getString(1));
                }
            }
        }
        return List.copyOf(existing);
    }
}
