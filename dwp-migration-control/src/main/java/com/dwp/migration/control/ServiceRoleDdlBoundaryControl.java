package com.dwp.migration.control;

import static com.dwp.migration.control.ControlValues.quoteIdentifier;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Closes database-wide schema and object-owner authority for service login roles. */
final class ServiceRoleDdlBoundaryControl {
    private ServiceRoleDdlBoundaryControl() {
    }

    static void normalize(Connection connection, ControlEnvironment environment)
            throws SQLException {
        List<String> protectedSchemas = protectedSchemas(environment);
        requireOwnershipShape(connection, environment, protectedSchemas);
        for (String statement : schemaRevocations(connection, environment)) {
            execute(connection, statement);
        }
        if (environment.hasProjectionPublisher()) {
            for (String statement : schemaRevocations(
                    connection, environment.projectionPublisherPrincipal())) {
                execute(connection, statement);
            }
        }
        for (String schema : migrationOwnedSchemas(environment)) {
            execute(connection, "GRANT USAGE, CREATE ON SCHEMA "
                    + quoteIdentifier(schema) + " TO "
                    + quoteIdentifier(environment.migrationPrincipal()));
            execute(connection, "GRANT USAGE ON SCHEMA "
                    + quoteIdentifier(schema) + " TO "
                    + quoteIdentifier(environment.runtimePrincipal()));
            if (environment.hasProjectionPublisher()) {
                execute(connection, "GRANT USAGE ON SCHEMA "
                        + quoteIdentifier(schema) + " TO "
                        + quoteIdentifier(
                                environment.projectionPublisherPrincipal()));
            }
        }
        requireExact(connection, environment);
    }

    static void requireExact(Connection connection, ControlEnvironment environment)
            throws SQLException {
        List<String> protectedSchemas = protectedSchemas(environment);
        requireOwnershipShape(connection, environment, protectedSchemas);
        List<String> migrationSchemas = migrationOwnedSchemas(environment);
        Array migrationSchemaArray = connection.createArrayOf(
                "text", migrationSchemas.toArray(String[]::new));
        Array protectedSchemaArray = connection.createArrayOf(
                "text", protectedSchemas.toArray(String[]::new));
        try (PreparedStatement statement = connection.prepareStatement("""
                        WITH policy AS (
                            SELECT ?::text[] AS migration_schemas,
                                   ?::text[] AS protected_schemas
                        )
                        SELECT COUNT(*) FILTER (
                                   WHERE pg_catalog.has_schema_privilege(
                                       runtime_role.oid, namespace.oid, 'CREATE')),
                               COUNT(*) FILTER (
                                   WHERE NOT namespace.nspname = ANY (policy.migration_schemas)
                                     AND pg_catalog.has_schema_privilege(
                                         migration_role.oid, namespace.oid, 'CREATE')),
                               COUNT(*) FILTER (
                                   WHERE namespace.nspname = ANY (policy.migration_schemas)
                                     AND NOT pg_catalog.has_schema_privilege(
                                         migration_role.oid, namespace.oid, 'CREATE')),
                               COUNT(*) FILTER (
                                   WHERE namespace.nspname = ANY (policy.protected_schemas)
                                     AND (namespace.nspname = ANY (policy.migration_schemas))
                                         <> pg_catalog.has_schema_privilege(
                                             runtime_role.oid, namespace.oid, 'USAGE')),
                               COUNT(*) FILTER (
                                   WHERE namespace.nspname = ANY (policy.protected_schemas)
                                     AND (namespace.nspname = ANY (policy.migration_schemas))
                                         <> pg_catalog.has_schema_privilege(
                                             migration_role.oid, namespace.oid, 'USAGE'))
                          FROM pg_catalog.pg_namespace namespace
                          CROSS JOIN pg_catalog.pg_roles runtime_role
                          CROSS JOIN pg_catalog.pg_roles migration_role
                          CROSS JOIN policy
                         WHERE runtime_role.rolname=? AND migration_role.rolname=?
                        """)) {
            statement.setArray(1, migrationSchemaArray);
            statement.setArray(2, protectedSchemaArray);
            statement.setString(3, environment.runtimePrincipal());
            statement.setString(4, environment.migrationPrincipal());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()
                        || result.getLong(1) != 0L
                        || result.getLong(2) != 0L
                        || result.getLong(3) != 0L
                        || result.getLong(4) != 0L
                        || result.getLong(5) != 0L) {
                    throw new IllegalStateException(
                            "Migration Control service schema CREATE authority is not exact");
                }
            }
        } finally {
            protectedSchemaArray.free();
            migrationSchemaArray.free();
        }
        requirePrincipalOwnsNothing(connection, environment.runtimePrincipal());
        if (environment.hasProjectionPublisher()) {
            requirePrincipalOwnsNothing(
                    connection, environment.projectionPublisherPrincipal());
            requirePublisherSchemaAuthority(connection, environment);
        }
        requireZeroServiceOwnedExtensions(connection, environment);
        SessionReplicationRoleControl.requireExact(connection, environment);
    }

    private static void requireOwnershipShape(
            Connection connection,
            ControlEnvironment environment,
            List<String> protectedSchemas) throws SQLException {
        if (DatabaseControl.scalarLong(connection, """
                SELECT COUNT(*)
                  FROM pg_catalog.pg_namespace namespace
                  JOIN pg_catalog.pg_roles runtime_role
                    ON runtime_role.oid=namespace.nspowner
                 WHERE runtime_role.rolname=%s
                """.formatted(ControlValues.quoteLiteral(
                        environment.runtimePrincipal()))) != 0L) {
            throw new IllegalStateException(
                    "Migration Control runtime principal owns a schema");
        }
        Array migrationSchemas = connection.createArrayOf(
                "text", migrationOwnedSchemas(environment).toArray(String[]::new));
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                  FROM pg_catalog.pg_namespace namespace
                  JOIN pg_catalog.pg_roles owner ON owner.oid=namespace.nspowner
                 WHERE owner.rolname=?
                   AND namespace.nspname<>ALL (?::text[])
                """)) {
            statement.setString(1, environment.migrationPrincipal());
            statement.setArray(2, migrationSchemas);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getLong(1) != 0L) {
                    throw new IllegalStateException(
                            "Migration Control migration principal owns an unexpected schema");
                }
            }
        } finally {
            migrationSchemas.free();
        }
        Map<String, String> expected = expectedSchemaOwners(environment);
        Map<String, String> actual = new LinkedHashMap<>();
        Array schemas = connection.createArrayOf(
                "text", protectedSchemas.toArray(String[]::new));
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT namespace.nspname, owner.rolname
                  FROM pg_catalog.pg_namespace namespace
                  JOIN pg_catalog.pg_roles owner ON owner.oid=namespace.nspowner
                 WHERE namespace.nspname=ANY (?::text[])
                 ORDER BY namespace.nspname
                """)) {
            statement.setArray(1, schemas);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    actual.put(result.getString(1), result.getString(2));
                }
            }
        } finally {
            schemas.free();
        }
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                    "Migration Control service schema ownership is not exact; expected="
                            + expected + "; actual=" + actual);
        }
    }

    private static List<String> schemaRevocations(
            Connection connection, ControlEnvironment environment) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT format(
                           'REVOKE ALL PRIVILEGES ON SCHEMA %I FROM %I, %I',
                           namespace.nspname, ?, ?)
                  FROM pg_catalog.pg_namespace namespace
                 ORDER BY namespace.oid
                """)) {
            statement.setString(1, environment.runtimePrincipal());
            statement.setString(2, environment.migrationPrincipal());
            try (ResultSet result = statement.executeQuery()) {
                java.util.ArrayList<String> statements = new java.util.ArrayList<>();
                while (result.next()) {
                    statements.add(result.getString(1));
                }
                return List.copyOf(statements);
            }
        }
    }

    private static List<String> schemaRevocations(
            Connection connection, String principal) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT format(
                           'REVOKE ALL PRIVILEGES ON SCHEMA %I FROM %I',
                           namespace.nspname, ?)
                  FROM pg_catalog.pg_namespace namespace
                 ORDER BY namespace.oid
                """)) {
            statement.setString(1, principal);
            try (ResultSet result = statement.executeQuery()) {
                java.util.ArrayList<String> statements = new java.util.ArrayList<>();
                while (result.next()) statements.add(result.getString(1));
                return List.copyOf(statements);
            }
        }
    }

    private static void requirePublisherSchemaAuthority(
            Connection connection, ControlEnvironment environment) throws SQLException {
        Array protectedSchemas = connection.createArrayOf(
                "text", protectedSchemas(environment).toArray(String[]::new));
        Array migrationSchemas = connection.createArrayOf(
                "text", migrationOwnedSchemas(environment).toArray(String[]::new));
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FILTER (
                           WHERE pg_catalog.has_schema_privilege(
                               publisher.oid, namespace.oid, 'CREATE')),
                       COUNT(*) FILTER (
                           WHERE namespace.nspname=ANY (?::text[])
                             AND (namespace.nspname=ANY (?::text[]))
                                 <> pg_catalog.has_schema_privilege(
                                     publisher.oid, namespace.oid, 'USAGE'))
                  FROM pg_catalog.pg_namespace namespace
                  CROSS JOIN pg_catalog.pg_roles publisher
                 WHERE publisher.rolname=?
                """)) {
            statement.setArray(1, protectedSchemas);
            statement.setArray(2, migrationSchemas);
            statement.setString(3, environment.projectionPublisherPrincipal());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()
                        || result.getLong(1) != 0L
                        || result.getLong(2) != 0L) {
                    throw new IllegalStateException(
                            "Migration Control projection publisher schema authority is not exact");
                }
            }
        } finally {
            migrationSchemas.free();
            protectedSchemas.free();
        }
    }

    static void requirePrincipalOwnsNothing(
            Connection connection, String runtimePrincipal) throws SQLException {
        String sql = """
                WITH runtime_role AS (
                    SELECT oid FROM pg_catalog.pg_roles WHERE rolname=?
                ), owned AS (
                    SELECT database_object.oid FROM pg_catalog.pg_database database_object,
                         runtime_role WHERE database_object.datdba=runtime_role.oid
                    UNION ALL SELECT relation.oid FROM pg_catalog.pg_class relation,
                         runtime_role WHERE relation.relowner=runtime_role.oid
                    UNION ALL SELECT routine.oid FROM pg_catalog.pg_proc routine,
                         runtime_role WHERE routine.proowner=runtime_role.oid
                    UNION ALL SELECT data_type.oid FROM pg_catalog.pg_type data_type,
                         runtime_role WHERE data_type.typowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_collation object,
                         runtime_role WHERE object.collowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_conversion object,
                         runtime_role WHERE object.conowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_operator object,
                         runtime_role WHERE object.oprowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_opclass object,
                         runtime_role WHERE object.opcowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_opfamily object,
                         runtime_role WHERE object.opfowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_statistic_ext object,
                         runtime_role WHERE object.stxowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_ts_config object,
                         runtime_role WHERE object.cfgowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_ts_dict object,
                         runtime_role WHERE object.dictowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_extension object,
                         runtime_role WHERE object.extowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_foreign_data_wrapper object,
                         runtime_role WHERE object.fdwowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_foreign_server object,
                         runtime_role WHERE object.srvowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_event_trigger object,
                         runtime_role WHERE object.evtowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_publication object,
                         runtime_role WHERE object.pubowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_subscription object,
                         runtime_role WHERE object.subowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_language object,
                         runtime_role WHERE object.lanowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_tablespace object,
                         runtime_role WHERE object.spcowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_largeobject_metadata object,
                         runtime_role WHERE object.lomowner=runtime_role.oid
                    UNION ALL SELECT defaults.oid FROM pg_catalog.pg_default_acl defaults,
                         runtime_role WHERE defaults.defaclrole=runtime_role.oid
                    UNION ALL SELECT mapping.umid FROM pg_catalog.pg_user_mappings mapping,
                         runtime_role WHERE mapping.umuser=runtime_role.oid
                )
                SELECT COUNT(*) FROM owned
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runtimePrincipal);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getLong(1) != 0L) {
                    throw new IllegalStateException(
                            "Migration Control runtime principal owns database authority");
                }
            }
        }
    }

    private static void requireZeroServiceOwnedExtensions(
            Connection connection, ControlEnvironment environment) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                  FROM pg_catalog.pg_extension extension_object
                  JOIN pg_catalog.pg_roles owner ON owner.oid=extension_object.extowner
                 WHERE owner.rolname IN (?, ?)
                """)) {
            statement.setString(1, environment.runtimePrincipal());
            statement.setString(2, environment.migrationPrincipal());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getLong(1) != 0L) {
                    throw new IllegalStateException(
                            "Migration Control extensions must remain Control-owned");
                }
            }
        }
    }

    private static List<String> protectedSchemas(ControlEnvironment environment) {
        Set<String> schemas = new LinkedHashSet<>();
        environment.plan().streams().forEach(
                stream -> schemas.addAll(environment.plan().protectedSchemas(stream)));
        return List.copyOf(schemas);
    }

    private static List<String> migrationOwnedSchemas(ControlEnvironment environment) {
        return environment.plan().streams().stream()
                .map(StreamPlan::schema)
                .distinct()
                .toList();
    }

    private static Map<String, String> expectedSchemaOwners(
            ControlEnvironment environment) {
        Map<String, String> expected = new LinkedHashMap<>();
        for (StreamPlan stream : environment.plan().streams()) {
            expected.put(stream.schema(), environment.migrationPrincipal());
        }
        for (ManagedDatabaseRole role : environment.plan().managedDatabaseRoles()) {
            for (String schema : role.allowedOwnershipSchemas()) {
                expected.putIfAbsent(schema, role.name());
            }
        }
        return Map.copyOf(expected);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
