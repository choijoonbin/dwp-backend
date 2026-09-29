package com.dwp.core.database;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

/** Exact database-wide DDL and ownership boundary for strict service login roles. */
public final class ServiceRoleDdlBoundaryGuard {

    private ServiceRoleDdlBoundaryGuard() {
    }

    public static void verifyStrict(
            String serviceName,
            DataSource dataSource,
            String migrationPrincipal,
            String runtimePrincipal,
            List<String> protectedSchemas) {
        verifyStrict(
                serviceName,
                dataSource,
                migrationPrincipal,
                runtimePrincipal,
                protectedSchemas,
                Map.of());
    }

    public static void verifyStrict(
            String serviceName,
            DataSource dataSource,
            String migrationPrincipal,
            String runtimePrincipal,
            List<String> protectedSchemas,
            Map<String, String> auxiliarySchemaOwners) {
        requireText("serviceName", serviceName);
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        requireText("migrationPrincipal", migrationPrincipal);
        requireText("runtimePrincipal", runtimePrincipal);
        Objects.requireNonNull(protectedSchemas, "protectedSchemas must not be null");
        if (protectedSchemas.isEmpty()
                || protectedSchemas.stream().anyMatch(schema -> schema == null || schema.isBlank())) {
            throw new IllegalArgumentException("protectedSchemas must contain nonblank values");
        }
        Objects.requireNonNull(
                auxiliarySchemaOwners, "auxiliarySchemaOwners must not be null");
        if (!protectedSchemas.containsAll(auxiliarySchemaOwners.keySet())) {
            throw new IllegalArgumentException(
                    "auxiliarySchemaOwners must stay inside protectedSchemas");
        }
        auxiliarySchemaOwners.forEach((schema, owner) -> {
            requireText("auxiliary schema", schema);
            requireText("auxiliary schema owner", owner);
            if (owner.equals(migrationPrincipal) || owner.equals(runtimePrincipal)) {
                throw new IllegalArgumentException(
                        "auxiliary schema owner must differ from service principals");
            }
        });
        try (Connection connection = dataSource.getConnection()) {
            verifySchemaOwnership(serviceName, connection, migrationPrincipal,
                    runtimePrincipal, protectedSchemas, auxiliarySchemaOwners);
            verifySchemaCreate(serviceName, connection, migrationPrincipal,
                    runtimePrincipal, protectedSchemas, auxiliarySchemaOwners);
            verifySchemaUsage(serviceName, connection, migrationPrincipal,
                    runtimePrincipal, protectedSchemas, auxiliarySchemaOwners);
            verifyPrincipalOwnsNothing(
                    serviceName, connection, runtimePrincipal, "runtime principal");
            verifyServiceRolesOwnNoExtensions(
                    serviceName, connection, migrationPrincipal, runtimePrincipal);
            verifyNoParameterAuthority(
                    serviceName, connection, migrationPrincipal, "migration principal");
            verifyNoParameterAuthority(
                    serviceName, connection, runtimePrincipal, "runtime principal");
            SystemCatalogAuthorityGuard.verify(
                    serviceName, connection, migrationPrincipal, "migration principal");
            SystemCatalogAuthorityGuard.verify(
                    serviceName, connection, runtimePrincipal, "runtime principal");
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify " + serviceName + " database-wide service-role DDL boundary",
                    exception);
        }
    }

    private static void verifySchemaCreate(
            String serviceName,
            Connection connection,
            String migrationPrincipal,
            String runtimePrincipal,
            List<String> protectedSchemas,
            Map<String, String> auxiliarySchemaOwners) throws SQLException {
        List<String> migrationSchemas = protectedSchemas.stream()
                .filter(schema -> !auxiliarySchemaOwners.containsKey(schema))
                .toList();
        Array schemas = connection.createArrayOf(
                "text", migrationSchemas.toArray(String[]::new));
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FILTER (
                           WHERE pg_catalog.has_schema_privilege(
                               runtime_role.oid, namespace.oid, 'CREATE')),
                       COUNT(*) FILTER (
                           WHERE NOT namespace.nspname = ANY (?::text[])
                             AND pg_catalog.has_schema_privilege(
                                 migration_role.oid, namespace.oid, 'CREATE')),
                       COUNT(*) FILTER (
                           WHERE namespace.nspname = ANY (?::text[])
                             AND NOT pg_catalog.has_schema_privilege(
                                 migration_role.oid, namespace.oid, 'CREATE'))
                  FROM pg_catalog.pg_namespace namespace
                  CROSS JOIN pg_catalog.pg_roles runtime_role
                  CROSS JOIN pg_catalog.pg_roles migration_role
                 WHERE runtime_role.rolname=? AND migration_role.rolname=?
                """)) {
            statement.setArray(1, schemas);
            statement.setArray(2, schemas);
            statement.setString(3, runtimePrincipal);
            statement.setString(4, migrationPrincipal);
            requireZeroTriple(serviceName, statement,
                    "service-role schema CREATE authority is not exact");
        } finally {
            schemas.free();
        }
    }

    private static void verifySchemaOwnership(
            String serviceName,
            Connection connection,
            String migrationPrincipal,
            String runtimePrincipal,
            List<String> protectedSchemas,
            Map<String, String> auxiliarySchemaOwners) throws SQLException {
        Set<String> migrationSchemas = new LinkedHashSet<>(protectedSchemas);
        migrationSchemas.removeAll(auxiliarySchemaOwners.keySet());
        Array allowedMigrationSchemas = connection.createArrayOf(
                "text", migrationSchemas.toArray(String[]::new));
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                  FROM pg_catalog.pg_namespace namespace
                  JOIN pg_catalog.pg_roles owner ON owner.oid=namespace.nspowner
                 WHERE (owner.rolname=? OR owner.rolname=?)
                   AND (owner.rolname=?
                        OR namespace.nspname<>ALL (?::text[]))
                """)) {
            statement.setString(1, runtimePrincipal);
            statement.setString(2, migrationPrincipal);
            statement.setString(3, runtimePrincipal);
            statement.setArray(4, allowedMigrationSchemas);
            requireZero(serviceName, statement,
                    "service-role schema ownership is not exact");
        } finally {
            allowedMigrationSchemas.free();
        }

        Map<String, String> expected = new LinkedHashMap<>();
        for (String schema : protectedSchemas) {
            expected.put(
                    schema,
                    auxiliarySchemaOwners.getOrDefault(schema, migrationPrincipal));
        }
        Map<String, String> actual = new LinkedHashMap<>();
        Array protectedSchemaArray = connection.createArrayOf(
                "text", protectedSchemas.toArray(String[]::new));
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT namespace.nspname, owner.rolname
                  FROM pg_catalog.pg_namespace namespace
                  JOIN pg_catalog.pg_roles owner ON owner.oid=namespace.nspowner
                 WHERE namespace.nspname=ANY (?::text[])
                 ORDER BY namespace.nspname
                """)) {
            statement.setArray(1, protectedSchemaArray);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    actual.put(result.getString(1), result.getString(2));
                }
            }
        } finally {
            protectedSchemaArray.free();
        }
        if (!expected.equals(actual)) {
            throw failure(serviceName,
                    "service-role schema ownership is not exact; expected="
                            + expected + "; actual=" + actual);
        }
    }

    private static void verifySchemaUsage(
            String serviceName,
            Connection connection,
            String migrationPrincipal,
            String runtimePrincipal,
            List<String> protectedSchemas,
            Map<String, String> auxiliarySchemaOwners) throws SQLException {
        List<String> serviceSchemas = protectedSchemas.stream()
                .filter(schema -> !auxiliarySchemaOwners.containsKey(schema))
                .toList();
        Array protectedSchemaArray = connection.createArrayOf(
                "text", protectedSchemas.toArray(String[]::new));
        Array service = connection.createArrayOf(
                "text", serviceSchemas.toArray(String[]::new));
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FILTER (
                           WHERE (namespace.nspname=ANY (?::text[]))
                               <> pg_catalog.has_schema_privilege(
                                      migration_role.oid,namespace.oid,'USAGE')),
                       COUNT(*) FILTER (
                           WHERE (namespace.nspname=ANY (?::text[]))
                               <> pg_catalog.has_schema_privilege(
                                      runtime_role.oid,namespace.oid,'USAGE'))
                  FROM pg_catalog.pg_namespace namespace
                  CROSS JOIN pg_catalog.pg_roles migration_role
                  CROSS JOIN pg_catalog.pg_roles runtime_role
                 WHERE namespace.nspname=ANY (?::text[])
                   AND migration_role.rolname=?
                   AND runtime_role.rolname=?
                """)) {
            statement.setArray(1, service);
            statement.setArray(2, service);
            statement.setArray(3, protectedSchemaArray);
            statement.setString(4, migrationPrincipal);
            statement.setString(5, runtimePrincipal);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()
                        || result.getLong(1) != 0L
                        || result.getLong(2) != 0L) {
                    throw failure(serviceName,
                            "service-role schema USAGE authority is not exact");
                }
            }
        } finally {
            service.free();
            protectedSchemaArray.free();
        }
    }

    static void verifyPrincipalOwnsNothing(
            String serviceName,
            Connection connection,
            String principal,
            String purpose)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH runtime_role AS (
                    SELECT oid FROM pg_catalog.pg_roles WHERE rolname=?
                ), owned AS (
                    SELECT object.oid FROM pg_catalog.pg_database object, runtime_role
                     WHERE object.datdba=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_class object, runtime_role
                     WHERE object.relowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_proc object, runtime_role
                     WHERE object.proowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_type object, runtime_role
                     WHERE object.typowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_collation object, runtime_role
                     WHERE object.collowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_conversion object, runtime_role
                     WHERE object.conowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_operator object, runtime_role
                     WHERE object.oprowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_opclass object, runtime_role
                     WHERE object.opcowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_opfamily object, runtime_role
                     WHERE object.opfowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_statistic_ext object, runtime_role
                     WHERE object.stxowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_ts_config object, runtime_role
                     WHERE object.cfgowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_ts_dict object, runtime_role
                     WHERE object.dictowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_extension object, runtime_role
                     WHERE object.extowner=runtime_role.oid
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
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_language object, runtime_role
                     WHERE object.lanowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_tablespace object, runtime_role
                     WHERE object.spcowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_largeobject_metadata object,
                         runtime_role WHERE object.lomowner=runtime_role.oid
                    UNION ALL SELECT object.oid FROM pg_catalog.pg_default_acl object, runtime_role
                     WHERE object.defaclrole=runtime_role.oid
                    UNION ALL SELECT object.umid FROM pg_catalog.pg_user_mappings object,
                         runtime_role
                     WHERE object.umuser=runtime_role.oid
                )
                SELECT COUNT(*) FROM owned
                """)) {
            statement.setString(1, principal);
            requireZero(serviceName, statement,
                    purpose + " owns database authority");
        }
    }

    static void verifyNoParameterAuthority(
            String serviceName,
            Connection connection,
            String principal,
            String purpose) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT parameter_acl.parname, acl.privilege_type,
                       COALESCE(grantee.rolname, 'PUBLIC')
                  FROM pg_catalog.pg_parameter_acl parameter_acl
                  CROSS JOIN LATERAL pg_catalog.aclexplode(parameter_acl.paracl) acl
                  JOIN pg_catalog.pg_roles principal_role ON principal_role.rolname=?
                  LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid=acl.grantee
                 WHERE acl.grantee IN (0, principal_role.oid)
                 ORDER BY parameter_acl.parname, acl.privilege_type, acl.grantee
                 LIMIT 1
                """)) {
            statement.setString(1, principal);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw failure(serviceName,
                            purpose + " has explicit PostgreSQL parameter authority: "
                                    + result.getString(1) + ":" + result.getString(2)
                                    + " via " + result.getString(3));
                }
            }
        }
    }

    private static void verifyServiceRolesOwnNoExtensions(
            String serviceName,
            Connection connection,
            String migrationPrincipal,
            String runtimePrincipal) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                  FROM pg_catalog.pg_extension extension_object
                  JOIN pg_catalog.pg_roles owner ON owner.oid=extension_object.extowner
                 WHERE owner.rolname IN (?, ?)
                """)) {
            statement.setString(1, migrationPrincipal);
            statement.setString(2, runtimePrincipal);
            requireZero(serviceName, statement,
                    "service login roles must not own extensions");
        }
    }

    private static void requireZeroTriple(
            String serviceName, PreparedStatement statement, String detail) throws SQLException {
        try (ResultSet result = statement.executeQuery()) {
            if (!result.next()
                    || result.getLong(1) != 0L
                    || result.getLong(2) != 0L
                    || result.getLong(3) != 0L) {
                throw failure(serviceName, detail);
            }
        }
    }

    private static void requireZero(
            String serviceName, PreparedStatement statement, String detail) throws SQLException {
        try (ResultSet result = statement.executeQuery()) {
            if (!result.next() || result.getLong(1) != 0L) {
                throw failure(serviceName, detail);
            }
        }
    }

    private static IllegalStateException failure(String serviceName, String detail) {
        return new IllegalStateException(serviceName + " database boundary violation: " + detail);
    }

    private static void requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
