package com.dwp.core.database;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** Fail-closed topology policy for objects whose semantics extend beyond protected schemas. */
final class ProtectedSchemaTopologyPolicy {

    private ProtectedSchemaTopologyPolicy() {
    }

    static void verify(Connection connection, List<String> schemas) throws SQLException {
        rejectForeignTables(connection, schemas);
        rejectCrossBoundaryInheritance(connection, schemas);
    }

    private static void rejectForeignTables(Connection connection, List<String> schemas)
            throws SQLException {
        Array schemaArray = connection.createArrayOf("text", schemas.toArray());
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT format('%I.%I', namespace.nspname, object.relname)
                  FROM pg_catalog.pg_class object
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=object.relnamespace
                 WHERE namespace.nspname = ANY (?::text[])
                   AND namespace.nspname <> 'dwp_migration_control'
                   AND object.relkind='f'
                 ORDER BY object.oid
                 LIMIT 1
                """)) {
            statement.setArray(1, schemaArray);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new SQLException(
                            "protected schemas must not contain foreign tables: "
                                    + result.getString(1));
                }
            }
        } finally {
            schemaArray.free();
        }
    }

    private static void rejectCrossBoundaryInheritance(
            Connection connection, List<String> schemas) throws SQLException {
        Array protectedSchemas = connection.createArrayOf("text", schemas.toArray());
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT format('%I.%I -> %I.%I',
                           child_namespace.nspname, child.relname,
                           parent_namespace.nspname, parent.relname)
                  FROM pg_catalog.pg_inherits inheritance
                  JOIN pg_catalog.pg_class child ON child.oid=inheritance.inhrelid
                  JOIN pg_catalog.pg_namespace child_namespace
                    ON child_namespace.oid=child.relnamespace
                  JOIN pg_catalog.pg_class parent ON parent.oid=inheritance.inhparent
                  JOIN pg_catalog.pg_namespace parent_namespace
                    ON parent_namespace.oid=parent.relnamespace
                 WHERE (child_namespace.nspname = ANY (?::text[]))
                       <> (parent_namespace.nspname = ANY (?::text[]))
                 ORDER BY inheritance.inhparent, inheritance.inhrelid
                 LIMIT 1
                """)) {
            statement.setArray(1, protectedSchemas);
            statement.setArray(2, protectedSchemas);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new SQLException(
                            "protected-schema inheritance and partition edges must remain "
                                    + "inside the protected schema set: " + result.getString(1));
                }
            }
        } finally {
            protectedSchemas.free();
        }
    }
}
