package com.dwp.migration.control;

import static com.dwp.migration.control.ControlValues.quoteIdentifier;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ProtectedObjectOwnershipControl {
    private ProtectedObjectOwnershipControl() {
    }

    static void transfer(Connection connection, ControlEnvironment environment)
            throws SQLException {
        Set<String> schemas = new LinkedHashSet<>();
        environment.plan().streams().forEach(stream -> schemas.add(stream.schema()));
        Set<String> preservedOwners = environment.plan().managedObjectOwnerNames();
        for (String schema : schemas) {
            transferSchema(
                    connection,
                    schema,
                    environment.migrationPrincipal(),
                    preservedOwners);
        }
    }

    private static void transferSchema(
            Connection connection,
            String schema,
            String migration,
            Set<String> preservedOwners) throws SQLException {
        String preservedOwnerList = String.join(",", preservedOwners);
        List<String> unsupported = strings(connection, """
                SELECT format('%s:%I.%I', 'TEXT_SEARCH_PARSER', n.nspname, object.prsname)
                  FROM pg_catalog.pg_ts_parser object
                  JOIN pg_catalog.pg_namespace n ON n.oid=object.prsnamespace
                 WHERE n.nspname=?
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_catalog.pg_depend dependency
                        WHERE dependency.classid='pg_ts_parser'::regclass
                          AND dependency.objid=object.oid AND dependency.deptype='e')
                UNION ALL
                SELECT format('%s:%I.%I', 'TEXT_SEARCH_TEMPLATE', n.nspname, object.tmplname)
                  FROM pg_catalog.pg_ts_template object
                  JOIN pg_catalog.pg_namespace n ON n.oid=object.tmplnamespace
                 WHERE n.nspname=?
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_catalog.pg_depend dependency
                        WHERE dependency.classid='pg_ts_template'::regclass
                          AND dependency.objid=object.oid AND dependency.deptype='e')
                 ORDER BY 1
                """, schema, schema);
        if (!unsupported.isEmpty()) {
            throw new IllegalStateException(
                    "Migration Control cannot automatically adopt unsupported owner classes: "
                            + String.join(", ", unsupported));
        }
        List<String> commands = strings(connection, """
                SELECT CASE object.relkind
                         WHEN 'r' THEN format('ALTER TABLE %I.%I OWNER TO %I', n.nspname, object.relname, ?)
                         WHEN 'p' THEN format('ALTER TABLE %I.%I OWNER TO %I', n.nspname, object.relname, ?)
                         WHEN 'f' THEN format('ALTER FOREIGN TABLE %I.%I OWNER TO %I', n.nspname, object.relname, ?)
                         WHEN 'S' THEN format('ALTER SEQUENCE %I.%I OWNER TO %I', n.nspname, object.relname, ?)
                         WHEN 'v' THEN format('ALTER VIEW %I.%I OWNER TO %I', n.nspname, object.relname, ?)
                         WHEN 'm' THEN format('ALTER MATERIALIZED VIEW %I.%I OWNER TO %I', n.nspname, object.relname, ?)
                         WHEN 'c' THEN format('ALTER TYPE %I.%I OWNER TO %I', n.nspname, object.relname, ?)
                       END
                  FROM pg_catalog.pg_class object
                  JOIN pg_catalog.pg_namespace n ON n.oid = object.relnamespace
                 WHERE n.nspname = ?
                   AND pg_get_userbyid(object.relowner)
                       <> ALL (string_to_array(?, ','))
                   AND object.relkind IN ('r', 'p', 'f', 'S', 'v', 'm', 'c')
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_catalog.pg_depend dependency
                        WHERE dependency.classid = 'pg_class'::regclass
                          AND dependency.objid = object.oid
                          AND dependency.deptype = 'e')
                 ORDER BY CASE object.relkind WHEN 'r' THEN 1 WHEN 'p' THEN 1
                              WHEN 'f' THEN 1 WHEN 'S' THEN 2 WHEN 'v' THEN 3
                              WHEN 'm' THEN 4 ELSE 5 END, object.oid
                """, migration, migration, migration, migration, migration, migration,
                migration, schema, preservedOwnerList);
        executeAll(connection, commands);
        commands = strings(connection, """
                SELECT CASE routine.prokind
                         WHEN 'p' THEN format('ALTER PROCEDURE %I.%I(%s) OWNER TO %I',
                              n.nspname, routine.proname,
                              pg_get_function_identity_arguments(routine.oid), ?)
                         WHEN 'a' THEN format('ALTER AGGREGATE %I.%I(%s) OWNER TO %I',
                              n.nspname, routine.proname,
                              pg_get_function_identity_arguments(routine.oid), ?)
                         ELSE format('ALTER FUNCTION %I.%I(%s) OWNER TO %I',
                              n.nspname, routine.proname,
                              pg_get_function_identity_arguments(routine.oid), ?)
                       END
                  FROM pg_catalog.pg_proc routine
                  JOIN pg_catalog.pg_namespace n ON n.oid = routine.pronamespace
                 WHERE n.nspname = ?
                   AND pg_get_userbyid(routine.proowner)
                       <> ALL (string_to_array(?, ','))
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_catalog.pg_depend dependency
                        WHERE dependency.classid = 'pg_proc'::regclass
                          AND dependency.objid = routine.oid
                          AND dependency.deptype = 'e')
                 ORDER BY routine.oid
                """, migration, migration, migration, schema, preservedOwnerList);
        executeAll(connection, commands);
        commands = strings(connection, """
                SELECT CASE data_type.typtype
                         WHEN 'd' THEN format('ALTER DOMAIN %I.%I OWNER TO %I',
                              n.nspname, data_type.typname, ?)
                         ELSE format('ALTER TYPE %I.%I OWNER TO %I',
                              n.nspname, data_type.typname, ?)
                       END
                  FROM pg_catalog.pg_type data_type
                  JOIN pg_catalog.pg_namespace n ON n.oid = data_type.typnamespace
                 WHERE n.nspname = ?
                   AND pg_get_userbyid(data_type.typowner)
                       <> ALL (string_to_array(?, ','))
                   AND data_type.typtype <> 'p'
                   AND data_type.typrelid = 0
                   AND data_type.typelem = 0
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_catalog.pg_depend dependency
                        WHERE dependency.classid = 'pg_type'::regclass
                          AND dependency.objid = data_type.oid
                          AND dependency.deptype = 'e')
                 ORDER BY data_type.oid
                """, migration, migration, schema, preservedOwnerList);
        executeAll(connection, commands);
        commands = strings(connection, """
                SELECT format('ALTER COLLATION %I.%I OWNER TO %I',
                              n.nspname, object.collname, ?)
                  FROM pg_catalog.pg_collation object
                  JOIN pg_catalog.pg_namespace n ON n.oid=object.collnamespace
                 WHERE n.nspname=?
                   AND pg_get_userbyid(object.collowner)
                       <> ALL (string_to_array(?, ','))
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_catalog.pg_depend dependency
                        WHERE dependency.classid='pg_collation'::regclass
                          AND dependency.objid=object.oid AND dependency.deptype='e')
                 ORDER BY object.oid
                """, migration, schema, preservedOwnerList);
        executeAll(connection, commands);
        commands = strings(connection, """
                SELECT format('ALTER CONVERSION %I.%I OWNER TO %I',
                              n.nspname, object.conname, ?)
                  FROM pg_catalog.pg_conversion object
                  JOIN pg_catalog.pg_namespace n ON n.oid=object.connamespace
                 WHERE n.nspname=?
                   AND pg_get_userbyid(object.conowner)
                       <> ALL (string_to_array(?, ','))
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_catalog.pg_depend dependency
                        WHERE dependency.classid='pg_conversion'::regclass
                          AND dependency.objid=object.oid AND dependency.deptype='e')
                 ORDER BY object.oid
                """, migration, schema, preservedOwnerList);
        executeAll(connection, commands);
        commands = strings(connection, """
                SELECT format('ALTER OPERATOR %I.%I (%s, %s) OWNER TO %I',
                              n.nspname, object.oprname,
                              CASE WHEN object.oprleft=0 THEN 'NONE'
                                   ELSE object.oprleft::regtype::text END,
                              CASE WHEN object.oprright=0 THEN 'NONE'
                                   ELSE object.oprright::regtype::text END, ?)
                  FROM pg_catalog.pg_operator object
                  JOIN pg_catalog.pg_namespace n ON n.oid=object.oprnamespace
                 WHERE n.nspname=?
                   AND pg_get_userbyid(object.oprowner)
                       <> ALL (string_to_array(?, ','))
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_catalog.pg_depend dependency
                        WHERE dependency.classid='pg_operator'::regclass
                          AND dependency.objid=object.oid AND dependency.deptype='e')
                 ORDER BY object.oid
                """, migration, schema, preservedOwnerList);
        executeAll(connection, commands);
        commands = strings(connection, """
                SELECT format('ALTER OPERATOR CLASS %I.%I USING %I OWNER TO %I',
                              n.nspname, object.opcname, method.amname, ?)
                  FROM pg_catalog.pg_opclass object
                  JOIN pg_catalog.pg_namespace n ON n.oid=object.opcnamespace
                  JOIN pg_catalog.pg_am method ON method.oid=object.opcmethod
                 WHERE n.nspname=?
                   AND pg_get_userbyid(object.opcowner)
                       <> ALL (string_to_array(?, ','))
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_catalog.pg_depend dependency
                        WHERE dependency.classid='pg_opclass'::regclass
                          AND dependency.objid=object.oid AND dependency.deptype='e')
                 ORDER BY object.oid
                """, migration, schema, preservedOwnerList);
        executeAll(connection, commands);
        commands = strings(connection, """
                SELECT format('ALTER OPERATOR FAMILY %I.%I USING %I OWNER TO %I',
                              n.nspname, object.opfname, method.amname, ?)
                  FROM pg_catalog.pg_opfamily object
                  JOIN pg_catalog.pg_namespace n ON n.oid=object.opfnamespace
                  JOIN pg_catalog.pg_am method ON method.oid=object.opfmethod
                 WHERE n.nspname=?
                   AND pg_get_userbyid(object.opfowner)
                       <> ALL (string_to_array(?, ','))
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_catalog.pg_depend dependency
                        WHERE dependency.classid='pg_opfamily'::regclass
                          AND dependency.objid=object.oid AND dependency.deptype='e')
                 ORDER BY object.oid
                """, migration, schema, preservedOwnerList);
        executeAll(connection, commands);
        commands = strings(connection, """
                SELECT format('ALTER STATISTICS %I.%I OWNER TO %I',
                              n.nspname, object.stxname, ?)
                  FROM pg_catalog.pg_statistic_ext object
                  JOIN pg_catalog.pg_namespace n ON n.oid=object.stxnamespace
                 WHERE n.nspname=?
                   AND pg_get_userbyid(object.stxowner)
                       <> ALL (string_to_array(?, ','))
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_catalog.pg_depend dependency
                        WHERE dependency.classid='pg_statistic_ext'::regclass
                          AND dependency.objid=object.oid AND dependency.deptype='e')
                 ORDER BY object.oid
                """, migration, schema, preservedOwnerList);
        executeAll(connection, commands);
        commands = strings(connection, """
                SELECT format('ALTER TEXT SEARCH CONFIGURATION %I.%I OWNER TO %I',
                              n.nspname, object.cfgname, ?)
                  FROM pg_catalog.pg_ts_config object
                  JOIN pg_catalog.pg_namespace n ON n.oid=object.cfgnamespace
                 WHERE n.nspname=?
                   AND pg_get_userbyid(object.cfgowner)
                       <> ALL (string_to_array(?, ','))
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_catalog.pg_depend dependency
                        WHERE dependency.classid='pg_ts_config'::regclass
                          AND dependency.objid=object.oid AND dependency.deptype='e')
                 ORDER BY object.oid
                """, migration, schema, preservedOwnerList);
        executeAll(connection, commands);
        commands = strings(connection, """
                SELECT format('ALTER TEXT SEARCH DICTIONARY %I.%I OWNER TO %I',
                              n.nspname, object.dictname, ?)
                  FROM pg_catalog.pg_ts_dict object
                  JOIN pg_catalog.pg_namespace n ON n.oid=object.dictnamespace
                 WHERE n.nspname=?
                   AND pg_get_userbyid(object.dictowner)
                       <> ALL (string_to_array(?, ','))
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_catalog.pg_depend dependency
                        WHERE dependency.classid='pg_ts_dict'::regclass
                          AND dependency.objid=object.oid AND dependency.deptype='e')
                 ORDER BY object.oid
                """, migration, schema, preservedOwnerList);
        executeAll(connection, commands);
        if (!preservedOwners.contains(schemaOwner(connection, schema))) {
            execute(connection, "ALTER SCHEMA " + quoteIdentifier(schema)
                    + " OWNER TO " + quoteIdentifier(migration));
        }
    }

    private static List<String> strings(
            Connection connection, String sql, String... values) throws SQLException {
        List<String> results = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setString(index + 1, values[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    results.add(result.getString(1));
                }
            }
        }
        return results;
    }

    private static void executeAll(Connection connection, List<String> commands)
            throws SQLException {
        for (String command : commands) {
            execute(connection, command);
        }
    }

    private static String schemaOwner(Connection connection, String schema)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner.rolname
                  FROM pg_catalog.pg_namespace namespace
                  JOIN pg_catalog.pg_roles owner ON owner.oid=namespace.nspowner
                 WHERE namespace.nspname=?
                """)) {
            statement.setString(1, schema);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException(
                            "Migration Control protected schema disappeared: " + schema);
                }
                return result.getString(1);
            }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
