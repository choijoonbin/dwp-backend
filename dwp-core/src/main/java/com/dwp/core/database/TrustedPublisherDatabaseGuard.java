package com.dwp.core.database;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

/** Catalog verifier for a credentialed projection-feed publisher. */
final class TrustedPublisherDatabaseGuard {
    private TrustedPublisherDatabaseGuard() {
    }

    static void verify(
            String serviceName,
            DataSource migrationDataSource,
            String expectedDatabase,
            String expectedSearchPath,
            List<String> protectedSchemas,
            TrustedPublisherPolicy policy) {
        if (policy.principal().isEmpty()) {
            return;
        }
        try (Connection connection = migrationDataSource.getConnection()) {
            String role = policy.principal();
            requireStrictRole(serviceName, connection, role);
            requireExactConnectivity(serviceName, connection, role, expectedDatabase);
            requireExactDatabaseSetting(
                    serviceName, connection, role, expectedDatabase, expectedSearchPath);
            requireNoMemberships(serviceName, connection, role);
            requireNoDatabaseDdl(serviceName, connection, role, expectedDatabase);
            requireProtectedSchemaPosture(
                    serviceName, connection, role, protectedSchemas);
            AuxiliaryRoleOwnershipGuard.verify(
                    serviceName, connection, Set.of(role), Map.of(role, Set.of()));
            SystemCatalogAuthorityGuard.verify(
                    serviceName, connection, role, "trusted publisher " + role);
            AuxiliaryRoleAclGuard.verify(
                    serviceName,
                    connection,
                    protectedSchemas,
                    Set.of(role),
                    policy.allowedAclPrivileges(),
                    policy.requiredAclPrivileges());
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify " + serviceName + " trusted publisher boundary",
                    exception);
        }
    }

    private static void requireStrictRole(
            String serviceName, Connection connection, String role) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolinherit,
                       rolreplication, rolbypassrls, rolconnlimit, rolvaliduntil, rolconfig
                  FROM pg_catalog.pg_roles
                 WHERE rolname=?
                """)) {
            statement.setString(1, role);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw failure(serviceName, "trusted publisher role is missing");
                }
                boolean strict = result.getBoolean(1)
                        && !result.getBoolean(2)
                        && !result.getBoolean(3)
                        && !result.getBoolean(4)
                        && !result.getBoolean(5)
                        && !result.getBoolean(6)
                        && !result.getBoolean(7)
                        && result.getInt(8) == -1
                        && result.getObject(9) == null
                        && result.getArray(10) == null;
                if (!strict) {
                    throw failure(serviceName,
                            "trusted publisher role attributes are not strict: " + role);
                }
            }
        }
    }

    private static void requireExactConnectivity(
            String serviceName,
            Connection connection,
            String role,
            String expectedDatabase) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT database.datname
                  FROM pg_catalog.pg_database database
                 WHERE database.datallowconn
                   AND pg_catalog.has_database_privilege(?, database.oid, 'CONNECT')
                 ORDER BY database.datname
                """)) {
            statement.setString(1, role);
            List<String> actual = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    actual.add(result.getString(1));
                }
            }
            if (!actual.equals(List.of(expectedDatabase))) {
                throw failure(serviceName,
                        "trusted publisher CONNECT surface must be exactly the configured catalog; actual="
                                + actual + "; expected=" + List.of(expectedDatabase));
            }
        }
    }

    private static void requireExactDatabaseSetting(
            String serviceName,
            Connection connection,
            String role,
            String expectedDatabase,
            String expectedSearchPath) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT database.datname, setting
                  FROM pg_catalog.pg_db_role_setting role_setting
                  JOIN pg_catalog.pg_roles role ON role.oid=role_setting.setrole
                  LEFT JOIN pg_catalog.pg_database database
                    ON database.oid=role_setting.setdatabase
                  CROSS JOIN LATERAL unnest(role_setting.setconfig) setting
                 WHERE role.rolname=?
                 ORDER BY database.datname NULLS FIRST, setting
                """)) {
            statement.setString(1, role);
            List<String> actual = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    actual.add(result.getString(1) + ":" + result.getString(2));
                }
            }
            List<String> expected = List.of(
                    expectedDatabase + ":search_path=pg_catalog, " + expectedSearchPath);
            if (!actual.equals(expected)) {
                throw failure(serviceName,
                        "trusted publisher database settings are not exact; actual="
                                + actual + "; expected=" + expected);
            }
        }
    }

    private static void requireNoMemberships(
            String serviceName, Connection connection, String role) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT granted.rolname, member.rolname
                  FROM pg_catalog.pg_auth_members membership
                  JOIN pg_catalog.pg_roles granted ON granted.oid=membership.roleid
                  JOIN pg_catalog.pg_roles member ON member.oid=membership.member
                 WHERE granted.rolname=? OR member.rolname=?
                 LIMIT 1
                """)) {
            statement.setString(1, role);
            statement.setString(2, role);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw failure(serviceName,
                            "trusted publisher role memberships must be empty");
                }
            }
        }
    }

    private static void requireNoDatabaseDdl(
            String serviceName,
            Connection connection,
            String role,
            String expectedDatabase) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_catalog.has_database_privilege(?, ?, 'CREATE'),
                       pg_catalog.has_database_privilege(?, ?, 'TEMPORARY')
                """)) {
            statement.setString(1, role);
            statement.setString(2, expectedDatabase);
            statement.setString(3, role);
            statement.setString(4, expectedDatabase);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getBoolean(1) || result.getBoolean(2)) {
                    throw failure(serviceName,
                            "trusted publisher must not hold database CREATE or TEMPORARY");
                }
            }
        }
    }

    private static void requireProtectedSchemaPosture(
            String serviceName,
            Connection connection,
            String role,
            List<String> protectedSchemas) throws SQLException {
        Array schemas = connection.createArrayOf(
                "text", protectedSchemas.toArray(String[]::new));
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT namespace.nspname,
                       pg_catalog.has_schema_privilege(?, namespace.oid, 'USAGE'),
                       pg_catalog.has_schema_privilege(?, namespace.oid, 'CREATE')
                  FROM pg_catalog.pg_namespace namespace
                 WHERE namespace.nspname=ANY (?::text[])
                 ORDER BY namespace.nspname
                """)) {
            statement.setString(1, role);
            statement.setString(2, role);
            statement.setArray(3, schemas);
            int rows = 0;
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows++;
                    if (!result.getBoolean(2) || result.getBoolean(3)) {
                        throw failure(serviceName,
                                "trusted publisher protected-schema posture is not USAGE-only: "
                                        + result.getString(1));
                    }
                }
            }
            if (rows != protectedSchemas.size()) {
                throw failure(serviceName,
                        "trusted publisher protected-schema inventory is incomplete");
            }
        } finally {
            schemas.free();
        }
    }

    private static IllegalStateException failure(String serviceName, String detail) {
        return new IllegalStateException(
                serviceName + " database boundary violation: " + detail);
    }
}
