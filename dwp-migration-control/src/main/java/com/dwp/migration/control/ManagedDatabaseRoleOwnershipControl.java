package com.dwp.migration.control;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/** Proves that managed non-login roles own only their exact declared schemas. */
final class ManagedDatabaseRoleOwnershipControl {
    private ManagedDatabaseRoleOwnershipControl() {
    }

    static void requireScope(
            Connection connection,
            ControlEnvironment environment,
            List<ManagedDatabaseRole> roles,
            boolean introduced) throws SQLException {
        for (ManagedDatabaseRole role : roles) {
            if (!introduced || role.allowedOwnershipSchemas().isEmpty()) {
                ServiceRoleDdlBoundaryControl.requirePrincipalOwnsNothing(
                        connection, role.name());
                continue;
            }
            requireNoOwnershipOutsideAllowlist(connection, role);
            Set<String> primarySchemas = environment.plan().streams().stream()
                    .map(StreamPlan::schema)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            for (String schema : role.allowedOwnershipSchemas()) {
                if (!primarySchemas.contains(schema)) {
                    requireSchemaOwner(connection, schema, role.name());
                }
            }
        }
    }

    private static void requireNoOwnershipOutsideAllowlist(
            Connection connection, ManagedDatabaseRole role) throws SQLException {
        Array schemas = connection.createArrayOf(
                "text", role.allowedOwnershipSchemas().toArray(String[]::new));
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH target AS (
                    SELECT oid FROM pg_catalog.pg_roles WHERE rolname=?
                ), allowed_toast AS (
                    SELECT relation.reltoastrelid AS oid
                      FROM pg_catalog.pg_class relation
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=relation.relnamespace
                      CROSS JOIN target
                     WHERE relation.relowner=target.oid
                       AND namespace.nspname=ANY (?::text[])
                       AND relation.reltoastrelid<>0
                    UNION
                    SELECT toast_index.indexrelid
                      FROM pg_catalog.pg_class relation
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=relation.relnamespace
                      JOIN pg_catalog.pg_index toast_index
                        ON toast_index.indrelid=relation.reltoastrelid
                      CROSS JOIN target
                     WHERE relation.relowner=target.oid
                       AND namespace.nspname=ANY (?::text[])
                ), violations AS (
                    SELECT 'DATABASE:' || object.datname AS identity
                      FROM pg_catalog.pg_database object,target
                     WHERE object.datdba=target.oid
                    UNION ALL
                    SELECT 'SCHEMA:' || namespace.nspname
                      FROM pg_catalog.pg_namespace namespace,target
                     WHERE namespace.nspowner=target.oid
                       AND namespace.nspname<>ALL (?::text[])
                    UNION ALL
                    SELECT 'RELATION:' || namespace.nspname || '.' || object.relname
                      FROM pg_catalog.pg_class object
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=object.relnamespace,target
                     WHERE object.relowner=target.oid
                       AND namespace.nspname<>ALL (?::text[])
                       AND object.oid NOT IN (SELECT oid FROM allowed_toast)
                    UNION ALL
                    SELECT 'ROUTINE:' || namespace.nspname || '.' || object.proname
                      FROM pg_catalog.pg_proc object
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=object.pronamespace,target
                     WHERE object.proowner=target.oid
                       AND namespace.nspname<>ALL (?::text[])
                    UNION ALL
                    SELECT 'TYPE:' || namespace.nspname || '.' || object.typname
                      FROM pg_catalog.pg_type object
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=object.typnamespace,target
                     WHERE object.typowner=target.oid
                       AND namespace.nspname<>ALL (?::text[])
                    UNION ALL
                    SELECT 'COLLATION:' || namespace.nspname || '.' || object.collname
                      FROM pg_catalog.pg_collation object
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=object.collnamespace,target
                     WHERE object.collowner=target.oid
                       AND namespace.nspname<>ALL (?::text[])
                    UNION ALL
                    SELECT 'CONVERSION:' || namespace.nspname || '.' || object.conname
                      FROM pg_catalog.pg_conversion object
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=object.connamespace,target
                     WHERE object.conowner=target.oid
                       AND namespace.nspname<>ALL (?::text[])
                    UNION ALL
                    SELECT 'OPERATOR:' || namespace.nspname || '.' || object.oprname
                      FROM pg_catalog.pg_operator object
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=object.oprnamespace,target
                     WHERE object.oprowner=target.oid
                       AND namespace.nspname<>ALL (?::text[])
                    UNION ALL
                    SELECT 'OPERATOR_CLASS:' || namespace.nspname || '.' || object.opcname
                      FROM pg_catalog.pg_opclass object
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=object.opcnamespace,target
                     WHERE object.opcowner=target.oid
                       AND namespace.nspname<>ALL (?::text[])
                    UNION ALL
                    SELECT 'OPERATOR_FAMILY:' || namespace.nspname || '.' || object.opfname
                      FROM pg_catalog.pg_opfamily object
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=object.opfnamespace,target
                     WHERE object.opfowner=target.oid
                       AND namespace.nspname<>ALL (?::text[])
                    UNION ALL
                    SELECT 'STATISTICS:' || namespace.nspname || '.' || object.stxname
                      FROM pg_catalog.pg_statistic_ext object
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=object.stxnamespace,target
                     WHERE object.stxowner=target.oid
                       AND namespace.nspname<>ALL (?::text[])
                    UNION ALL
                    SELECT 'TEXT_SEARCH_CONFIGURATION:' || namespace.nspname || '.'
                           || object.cfgname
                      FROM pg_catalog.pg_ts_config object
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=object.cfgnamespace,target
                     WHERE object.cfgowner=target.oid
                       AND namespace.nspname<>ALL (?::text[])
                    UNION ALL
                    SELECT 'TEXT_SEARCH_DICTIONARY:' || namespace.nspname || '.'
                           || object.dictname
                      FROM pg_catalog.pg_ts_dict object
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=object.dictnamespace,target
                     WHERE object.dictowner=target.oid
                       AND namespace.nspname<>ALL (?::text[])
                    UNION ALL
                    SELECT 'DEFAULT_ACL:' || object.oid::text
                      FROM pg_catalog.pg_default_acl object
                      LEFT JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=object.defaclnamespace
                      CROSS JOIN target
                     WHERE object.defaclrole=target.oid
                       AND (namespace.nspname IS NULL
                            OR namespace.nspname<>ALL (?::text[]))
                    UNION ALL
                    SELECT 'EXTENSION:' || object.extname
                      FROM pg_catalog.pg_extension object,target
                     WHERE object.extowner=target.oid
                    UNION ALL
                    SELECT 'FDW:' || object.fdwname
                      FROM pg_catalog.pg_foreign_data_wrapper object,target
                     WHERE object.fdwowner=target.oid
                    UNION ALL
                    SELECT 'SERVER:' || object.srvname
                      FROM pg_catalog.pg_foreign_server object,target
                     WHERE object.srvowner=target.oid
                    UNION ALL
                    SELECT 'EVENT_TRIGGER:' || object.evtname
                      FROM pg_catalog.pg_event_trigger object,target
                     WHERE object.evtowner=target.oid
                    UNION ALL
                    SELECT 'PUBLICATION:' || object.pubname
                      FROM pg_catalog.pg_publication object,target
                     WHERE object.pubowner=target.oid
                    UNION ALL
                    SELECT 'SUBSCRIPTION:' || object.subname
                     FROM pg_catalog.pg_subscription object,target
                     WHERE object.subowner=target.oid
                    UNION ALL
                    SELECT 'LANGUAGE:' || object.lanname
                      FROM pg_catalog.pg_language object,target
                     WHERE object.lanowner=target.oid
                    UNION ALL
                    SELECT 'TABLESPACE:' || object.spcname
                      FROM pg_catalog.pg_tablespace object,target
                     WHERE object.spcowner=target.oid
                    UNION ALL
                    SELECT 'LARGE_OBJECT:' || object.oid::text
                      FROM pg_catalog.pg_largeobject_metadata object,target
                     WHERE object.lomowner=target.oid
                    UNION ALL
                    SELECT 'USER_MAPPING:' || object.umid::text
                      FROM pg_catalog.pg_user_mappings object,target
                     WHERE object.umuser=target.oid
                )
                SELECT identity FROM violations ORDER BY identity LIMIT 1
                """)) {
            statement.setString(1, role.name());
            for (int parameter = 2; parameter <= 16; parameter++) {
                statement.setArray(parameter, schemas);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new IllegalStateException(
                            "Migration Control managed role owns out-of-scope authority: "
                                    + role.name() + ":" + result.getString(1));
                }
            }
        } finally {
            schemas.free();
        }
    }

    private static void requireSchemaOwner(
            Connection connection, String schema, String owner) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner.rolname
                  FROM pg_catalog.pg_namespace namespace
                  JOIN pg_catalog.pg_roles owner ON owner.oid=namespace.nspowner
                 WHERE namespace.nspname=?
                """)) {
            statement.setString(1, schema);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !owner.equals(result.getString(1))) {
                    throw new IllegalStateException(
                            "Migration Control managed schema owner is not exact: " + schema);
                }
            }
        }
    }
}
