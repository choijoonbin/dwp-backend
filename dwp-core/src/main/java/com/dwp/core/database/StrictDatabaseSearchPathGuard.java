package com.dwp.core.database;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

/** Prevents a strict database principal from resolving unqualified names through a shadow schema. */
public final class StrictDatabaseSearchPathGuard {

    private StrictDatabaseSearchPathGuard() {
    }

    public static void verify(
            String serviceName,
            String purpose,
            DataSource dataSource,
            List<String> searchPathSchemas,
            List<String> allowedOwnedSchemas) {
        requireText("serviceName", serviceName);
        requireText("purpose", purpose);
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        List<String> pathSchemas = canonicalSchemas("searchPathSchemas", searchPathSchemas);
        List<String> ownedSchemas = canonicalSchemas("allowedOwnedSchemas", allowedOwnedSchemas);
        if (!ownedSchemas.containsAll(pathSchemas)) {
            throw new IllegalArgumentException(
                    "allowedOwnedSchemas must include every searchPathSchemas entry");
        }
        List<String> expectedPath = new java.util.ArrayList<>();
        expectedPath.add("pg_catalog");
        expectedPath.addAll(pathSchemas);
        try (Connection connection = dataSource.getConnection()) {
            verifyEffectivePath(serviceName, purpose, connection, expectedPath);
            verifyConfigurationSources(serviceName, purpose, connection, expectedPath);
            verifyNoUnexpectedOwnedSchema(serviceName, purpose, connection, ownedSchemas);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify " + serviceName + " " + purpose + " search_path boundary",
                    exception);
        }
    }

    private static List<String> canonicalSchemas(String name, List<String> values) {
        List<String> schemas = List.copyOf(Objects.requireNonNull(
                values, name + " must not be null"));
        if (schemas.isEmpty() || schemas.stream().anyMatch(
                schema -> schema == null || !schema.matches("[a-z_][a-z0-9_]{0,62}"))) {
            throw new IllegalArgumentException(
                    name + " must contain canonical identifiers");
        }
        return schemas;
    }

    private static void verifyEffectivePath(
            String serviceName,
            String purpose,
            Connection connection,
            List<String> expectedPath) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT current_setting('search_path'), current_schemas(false),
                       current_setting('session_replication_role')
                """); ResultSet result = statement.executeQuery()) {
            result.next();
            String configured = result.getString(1);
            String[] effective = (String[]) result.getArray(2).getArray();
            String expectedSetting = String.join(", ", expectedPath);
            if (!parsePath(configured).equals(expectedPath)
                    || !Arrays.asList(effective).equals(expectedPath)) {
                throw failure(serviceName, purpose,
                        "search_path must be exact; configured=" + configured
                                + "; effective=" + Arrays.toString(effective)
                                + "; expected=" + expectedSetting);
            }
            if (!"origin".equals(result.getString(3))) {
                throw failure(serviceName, purpose,
                        "session_replication_role must be origin");
            }
        }
    }

    private static void verifyConfigurationSources(
            String serviceName,
            String purpose,
            Connection connection,
            List<String> expectedPath) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH settings AS (
                    SELECT unnest(COALESCE(role.rolconfig, ARRAY[]::text[])) AS setting
                      FROM pg_catalog.pg_roles role
                     WHERE role.rolname=current_user
                    UNION ALL
                    SELECT unnest(database_role.setconfig)
                      FROM pg_catalog.pg_db_role_setting database_role
                     WHERE database_role.setdatabase IN (
                               0, (SELECT database.oid FROM pg_catalog.pg_database database
                                    WHERE database.datname=current_database()))
                       AND database_role.setrole IN (
                               0, (SELECT role.oid FROM pg_catalog.pg_roles role
                                    WHERE role.rolname=current_user))
                )
                SELECT setting
                  FROM settings
                 WHERE lower(split_part(setting, '=', 1)) IN (
                           'search_path', 'session_replication_role')
                 ORDER BY setting
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String setting = result.getString(1);
                    String name = setting.substring(0, setting.indexOf('=')).toLowerCase();
                    String value = setting.substring(setting.indexOf('=') + 1);
                    if ("search_path".equals(name) && !parsePath(value).equals(expectedPath)) {
                        throw failure(serviceName, purpose,
                                "role/database search_path setting conflicts with the exact boundary: "
                                        + setting);
                    }
                    if ("session_replication_role".equals(name)
                            && !"origin".equalsIgnoreCase(value.trim())) {
                        throw failure(serviceName, purpose,
                                "role/database session_replication_role setting permits trigger bypass: "
                                        + setting);
                    }
                }
            }
        }
    }

    private static List<String> parsePath(String setting) {
        return Arrays.stream(setting.split(",", -1)).map(String::trim).toList();
    }

    private static void verifyNoUnexpectedOwnedSchema(
            String serviceName,
            String purpose,
            Connection connection,
            List<String> allowedSchemas) throws SQLException {
        Array allowed = connection.createArrayOf("text", allowedSchemas.toArray());
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT namespace.nspname
                  FROM pg_catalog.pg_namespace namespace
                  JOIN pg_catalog.pg_roles owner ON owner.oid=namespace.nspowner
                 WHERE owner.rolname=current_user
                   AND namespace.nspname<>ALL (?::text[])
                   AND namespace.nspname NOT LIKE 'pg\\_%' ESCAPE '\\'
                   AND namespace.nspname<>'information_schema'
                 ORDER BY namespace.nspname
                """)) {
            statement.setArray(1, allowed);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw failure(serviceName, purpose,
                            "principal owns an unapproved schema: " + result.getString(1));
                }
            }
        } finally {
            allowed.free();
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static IllegalStateException failure(
            String serviceName, String purpose, String message) {
        return new IllegalStateException(serviceName + " " + purpose + " " + message);
    }
}
