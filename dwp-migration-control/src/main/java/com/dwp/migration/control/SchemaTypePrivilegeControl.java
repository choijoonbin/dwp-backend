package com.dwp.migration.control;

import static com.dwp.migration.control.ControlValues.quoteIdentifier;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Normalizes privileges for grantable, non-array data types in one schema. */
final class SchemaTypePrivilegeControl {
    private SchemaTypePrivilegeControl() {
    }

    static void normalize(
            Connection connection,
            String schema,
            String runtimePrincipal,
            boolean grantRuntimeUsage) throws SQLException {
        String runtime = quoteIdentifier(runtimePrincipal);
        for (String type : grantableTypes(connection, schema)) {
            execute(connection,
                    "REVOKE ALL PRIVILEGES ON TYPE " + type
                            + " FROM PUBLIC, " + runtime);
            if (grantRuntimeUsage) {
                execute(connection,
                        "GRANT USAGE ON TYPE " + type + " TO " + runtime);
            }
        }
    }

    private static List<String> grantableTypes(Connection connection, String schema)
            throws SQLException {
        List<String> types = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT format('%I.%I', namespace.nspname, data_type.typname)
                  FROM pg_catalog.pg_type data_type
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=data_type.typnamespace
                  LEFT JOIN pg_catalog.pg_class relation
                    ON relation.oid=data_type.typrelid
                 WHERE namespace.nspname=?
                   AND data_type.typtype<>'p'
                   AND (data_type.typrelid=0 OR relation.relkind='c')
                   AND NOT EXISTS (
                       SELECT 1
                         FROM pg_catalog.pg_type element_type
                        WHERE element_type.typarray=data_type.oid)
                 ORDER BY data_type.oid
                """)) {
            statement.setString(1, schema);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    types.add(result.getString(1));
                }
            }
        }
        return List.copyOf(types);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
