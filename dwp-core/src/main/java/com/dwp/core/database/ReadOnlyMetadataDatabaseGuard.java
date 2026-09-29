package com.dwp.core.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/** Fail-closed PostgreSQL metadata-reader boundary that grants no application-data authority. */
public final class ReadOnlyMetadataDatabaseGuard {

    private ReadOnlyMetadataDatabaseGuard() {
    }

    public static void verify(Source source) {
        Objects.requireNonNull(source, "source must not be null");
        source.requireCanonical();
        Properties properties = new Properties();
        properties.setProperty("user", source.username());
        properties.setProperty("password", source.password());
        properties.setProperty("ApplicationName", source.service() + "-metadata-guard");
        properties.setProperty("connectTimeout", "10");
        try (Connection connection = DriverManager.getConnection(source.jdbcUrl(), properties)) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL search_path = pg_catalog");
            }
            inspect(connection, source);
            connection.rollback();
        } catch (SQLException exception) {
            throw failure(source, "cannot verify metadata database authority", exception);
        }
    }

    private static void inspect(Connection connection, Source source) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("""
                     SELECT current_user,
                            session_user,
                            current_database(),
                            current_setting('transaction_read_only'),
                            current_setting('search_path'),
                            role.rolsuper,
                            role.rolcreatedb,
                            role.rolcreaterole,
                            role.rolreplication,
                            role.rolbypassrls,
                            role.rolinherit
                       FROM pg_catalog.pg_roles role
                      WHERE role.rolname = current_user
                     """)) {
            if (!row.next()
                    || !source.username().equals(row.getString(1))
                    || !source.username().equals(row.getString(2))
                    || !source.databaseName().equals(row.getString(3))
                    || !"on".equals(row.getString(4))
                    || !"pg_catalog".equals(row.getString(5))) {
                throw failure(source,
                        "metadata identity, catalog, read-only mode, or search_path differs");
            }
            for (int index = 6; index <= 10; index++) {
                if (row.getBoolean(index)) {
                    throw failure(source, "metadata principal has an elevated role attribute");
                }
            }
            if (row.getBoolean(11)) {
                throw failure(source, "metadata principal must remain NOINHERIT");
            }
        }

        if (count(connection, """
                SELECT count(*)
                  FROM pg_catalog.pg_database database_record
                 WHERE database_record.datallowconn
                   AND NOT database_record.datistemplate
                   AND database_record.datname <> current_database()
                   AND pg_catalog.has_database_privilege(
                           current_user, database_record.oid, 'CONNECT')
                """) != 0) {
            throw failure(source, "metadata principal can CONNECT to another database");
        }

        if (booleanValue(connection,
                "SELECT has_database_privilege(current_user, current_database(), 'CREATE')")
                || booleanValue(connection,
                "SELECT has_database_privilege(current_user, current_database(), 'TEMP')")) {
            throw failure(source, "metadata principal has database CREATE or TEMPORARY");
        }
        if (count(connection, """
                WITH RECURSIVE memberships(roleid) AS (
                    SELECT membership.roleid
                      FROM pg_catalog.pg_auth_members membership
                      JOIN pg_catalog.pg_roles member_role
                        ON member_role.oid = membership.member
                     WHERE member_role.rolname = current_user
                    UNION
                    SELECT membership.roleid
                      FROM pg_catalog.pg_auth_members membership
                      JOIN memberships prior ON prior.roleid = membership.member
                )
                SELECT count(*) FROM memberships
                """) != 0) {
            throw failure(source, "metadata principal has direct or transitive role membership");
        }
        if (count(connection, """
                SELECT count(*)
                  FROM pg_catalog.pg_auth_members membership
                  JOIN pg_catalog.pg_roles granted_role
                    ON granted_role.oid=membership.roleid
                 WHERE granted_role.rolname=current_user
                """) != 0) {
            throw failure(source, "metadata principal is granted to another role");
        }

        if (count(connection, """
                SELECT count(*)
                  FROM pg_catalog.pg_namespace namespace
                 WHERE pg_catalog.has_schema_privilege(
                           current_user, namespace.oid, 'CREATE')
                """) != 0) {
            throw failure(source, "metadata principal has schema CREATE authority");
        }
        if (count(connection, """
                WITH direct_relation_grants AS (
                    SELECT object.oid, object.relname, acl.grantee,
                           acl.privilege_type, acl.is_grantable
                      FROM pg_catalog.pg_class object
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=object.relnamespace
                      CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(
                          object.relacl,
                          pg_catalog.acldefault('r', object.relowner))) acl
                      JOIN pg_catalog.pg_roles role_record
                        ON role_record.rolname=current_user
                     WHERE namespace.nspname='pg_catalog'
                       AND acl.grantee IN (0, role_record.oid)
                       AND acl.privilege_type IN (
                           'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE',
                           'REFERENCES', 'TRIGGER')
                       AND NOT (object.relname='pg_settings'
                                AND acl.grantee=0
                                AND acl.privilege_type='UPDATE'
                                AND NOT acl.is_grantable)
                    UNION ALL
                    SELECT object.oid, object.relname, acl.grantee,
                           acl.privilege_type, acl.is_grantable
                      FROM pg_catalog.pg_attribute attribute
                      JOIN pg_catalog.pg_class object
                        ON object.oid=attribute.attrelid
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=object.relnamespace
                      CROSS JOIN LATERAL pg_catalog.aclexplode(attribute.attacl) acl
                      JOIN pg_catalog.pg_roles role_record
                        ON role_record.rolname=current_user
                     WHERE namespace.nspname='pg_catalog'
                       AND acl.grantee IN (0, role_record.oid)
                       AND acl.privilege_type IN ('INSERT', 'UPDATE', 'REFERENCES')
                )
                SELECT count(*) FROM direct_relation_grants
                """) != 0) {
            throw failure(source, "metadata principal can mutate system-catalog relations");
        }
        if (count(connection, """
                SELECT count(*)
                  FROM pg_catalog.pg_proc routine
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=routine.pronamespace
                 WHERE namespace.nspname='pg_catalog'
                   AND routine.oid >= 16384
                   AND pg_catalog.has_function_privilege(
                           current_user, routine.oid, 'EXECUTE')
                """) != 0) {
            throw failure(source, "metadata principal can execute non-system catalog routines");
        }

        List<String> schemas = dataBearingSchemas(connection);
        if (!schemas.isEmpty()) {
            if (count(connection, """
                    SELECT count(*)
                      FROM pg_catalog.pg_namespace namespace
                     WHERE namespace.nspname <> ALL (ARRAY['pg_catalog',
                                                           'information_schema'])
                       AND namespace.nspname NOT LIKE 'pg_toast%'
                       AND namespace.nspname NOT LIKE 'pg_temp_%'
                       AND has_schema_privilege(current_user, namespace.oid, 'CREATE')
                    """) != 0) {
                throw failure(source, "metadata principal has application-schema CREATE");
            }
            if (count(connection, """
                    SELECT count(*)
                      FROM pg_catalog.pg_class object
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid = object.relnamespace
                     WHERE namespace.nspname <> ALL (ARRAY['pg_catalog',
                                                           'information_schema'])
                       AND namespace.nspname NOT LIKE 'pg_toast%'
                       AND namespace.nspname NOT LIKE 'pg_temp_%'
                       AND object.relkind IN ('r', 'p', 'v', 'm', 'f')
                       AND (
                           has_table_privilege(current_user, object.oid, 'SELECT')
                           OR has_table_privilege(current_user, object.oid, 'INSERT')
                           OR has_table_privilege(current_user, object.oid, 'UPDATE')
                           OR has_table_privilege(current_user, object.oid, 'DELETE')
                           OR has_table_privilege(current_user, object.oid, 'TRUNCATE')
                           OR has_table_privilege(current_user, object.oid, 'REFERENCES')
                           OR has_table_privilege(current_user, object.oid, 'TRIGGER')
                           OR has_any_column_privilege(
                               current_user, object.oid, 'SELECT')
                           OR has_any_column_privilege(
                               current_user, object.oid, 'INSERT')
                           OR has_any_column_privilege(
                               current_user, object.oid, 'UPDATE')
                           OR has_any_column_privilege(
                               current_user, object.oid, 'REFERENCES'))
                    """) != 0) {
                throw failure(source,
                        "metadata principal has application relation or column privileges");
            }
            if (count(connection, """
                    SELECT count(*)
                      FROM pg_catalog.pg_class sequence
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid = sequence.relnamespace
                     WHERE namespace.nspname <> ALL (ARRAY['pg_catalog',
                                                           'information_schema'])
                       AND namespace.nspname NOT LIKE 'pg_toast%'
                       AND namespace.nspname NOT LIKE 'pg_temp_%'
                       AND sequence.relkind = 'S'
                       AND (
                           has_sequence_privilege(current_user, sequence.oid, 'USAGE')
                           OR has_sequence_privilege(current_user, sequence.oid, 'SELECT')
                           OR has_sequence_privilege(current_user, sequence.oid, 'UPDATE'))
                    """) != 0) {
                throw failure(source, "metadata principal has application sequence privileges");
            }
            if (count(connection, """
                    SELECT count(*)
                      FROM pg_catalog.pg_proc routine
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid = routine.pronamespace
                     WHERE namespace.nspname <> ALL (ARRAY['pg_catalog',
                                                           'information_schema'])
                       AND namespace.nspname NOT LIKE 'pg_toast%'
                       AND namespace.nspname NOT LIKE 'pg_temp_%'
                       AND pg_catalog.has_function_privilege(
                               current_user, routine.oid, 'EXECUTE')
                    """) != 0) {
                throw failure(source, "metadata principal can EXECUTE application routines");
            }
            ProtectedSchemaObjectInventory.Ownership ownership =
                    ProtectedSchemaObjectInventory.inspect(
                            connection, schemas, source.username());
            if (ownership.owned() != 0) {
                throw failure(source, "metadata principal owns protected database objects");
            }
        }
        ServiceRoleDdlBoundaryGuard.verifyPrincipalOwnsNothing(
                source.service(), connection, source.username(), "metadata principal");
        ServiceRoleDdlBoundaryGuard.verifyNoParameterAuthority(
                source.service(), connection, source.username(), "metadata principal");
        SystemCatalogAuthorityGuard.verify(
                source.service(), connection, source.username(), "metadata principal");
    }

    private static List<String> dataBearingSchemas(Connection connection) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT namespace.nspname
                  FROM pg_catalog.pg_namespace namespace
                 WHERE namespace.nspname <> ALL (ARRAY['pg_catalog',
                                                       'information_schema'])
                   AND namespace.nspname NOT LIKE 'pg_toast%'
                   AND namespace.nspname NOT LIKE 'pg_temp_%'
                 ORDER BY namespace.nspname
                """);
             ResultSet row = statement.executeQuery()) {
            while (row.next()) {
                result.add(row.getString(1));
            }
        }
        return List.copyOf(result);
    }

    private static boolean booleanValue(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            if (!row.next()) {
                throw new SQLException("boolean query returned no row");
            }
            return row.getBoolean(1);
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            if (!row.next()) {
                throw new SQLException("count query returned no row");
            }
            return row.getLong(1);
        }
    }

    private static IllegalStateException failure(Source source, String message) {
        return new IllegalStateException(
                source.service() + " metadata source " + source.key() + ": " + message);
    }

    private static IllegalStateException failure(
            Source source,
            String message,
            SQLException cause) {
        return new IllegalStateException(
                source.service() + " metadata source " + source.key() + ": " + message,
                cause);
    }

    public record Source(
            String service,
            String key,
            String databaseName,
            String jdbcUrl,
            String username,
            String password) {

        private void requireCanonical() {
            requireText("service", service);
            requireText("key", key);
            requireText("databaseName", databaseName);
            requireText("jdbcUrl", jdbcUrl);
            requireText("username", username);
            requireText("password", password);
            if (!jdbcUrl.startsWith("jdbc:postgresql://")) {
                throw failure(this, "jdbcUrl must use PostgreSQL");
            }
        }

        private void requireText(String name, String value) {
            if (value == null || value.isBlank()) {
                throw failure(this, name + " must not be blank");
            }
        }
    }
}
