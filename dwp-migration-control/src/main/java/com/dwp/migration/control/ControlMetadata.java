package com.dwp.migration.control;

import static com.dwp.migration.control.ControlValues.quoteIdentifier;
import static com.dwp.migration.control.ControlValues.quoteLiteral;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import com.dwp.core.database.MigrationAdoptionGuard;

/** Exact, Control-owned schema contract for adoption receipts. */
final class ControlMetadata {
    private static final String SCHEMA = MigrationAdoptionGuard.CONTROL_SCHEMA;
    private static final String RECEIPT = MigrationAdoptionGuard.RECEIPT_TABLE;
    private static final String INVENTORY = MigrationAdoptionGuard.INVENTORY_TABLE;

    private ControlMetadata() {
    }

    static void ensure(Connection connection, String bootstrapPrincipal) throws SQLException {
        String schema = quoteIdentifier(SCHEMA);
        DatabaseControl.execute(connection, "CREATE SCHEMA IF NOT EXISTS " + schema
                + " AUTHORIZATION " + quoteIdentifier(bootstrapPrincipal));
        DatabaseControl.execute(connection, "CREATE TABLE IF NOT EXISTS " + schema + "."
                + quoteIdentifier(RECEIPT) + " ("
                + "stream_key text PRIMARY KEY, contract_version integer NOT NULL, "
                + "database_name text NOT NULL, history_schema text NOT NULL, "
                + "history_table text NOT NULL, legacy_max_installed_rank integer NOT NULL, "
                + "sealed_history_max_installed_rank integer NOT NULL, "
                + "sealed_history_row_count integer NOT NULL, "
                + "sealed_history_sha256 text NOT NULL, inventory_object_count integer NOT NULL, "
                + "inventory_sha256 text NOT NULL, migration_principal text NOT NULL, "
                + "control_reference text NOT NULL, sealed_at_utc text NOT NULL, "
                + "receipt_sha256 text NOT NULL)");
        DatabaseControl.execute(connection, "CREATE TABLE IF NOT EXISTS " + schema + "."
                + quoteIdentifier(INVENTORY) + " ("
                + "stream_key text NOT NULL, object_class text NOT NULL, object_oid oid NOT NULL, "
                + "object_schema text NOT NULL, object_identity text NOT NULL, "
                + "adopted_owner text NOT NULL, "
                + "PRIMARY KEY (stream_key, object_class, object_oid))");
        verify(connection, bootstrapPrincipal);
    }

    static void verify(Connection connection, String bootstrapPrincipal) throws SQLException {
        if (DatabaseControl.scalarLong(connection, schemaOwnerMismatch(bootstrapPrincipal)) != 0L
                || DatabaseControl.scalarLong(
                        connection, relationOwnerMismatch(bootstrapPrincipal)) != 0L
                || DatabaseControl.scalarLong(connection, columnMismatch()) != 0L
                || DatabaseControl.scalarLong(connection, primaryKeyMismatch()) != 0L
                || DatabaseControl.scalarLong(connection, unexpectedConstraintCount()) != 0L
                || !exactObjectSet(connection, bootstrapPrincipal)) {
            throw new IllegalStateException("Control metadata shape or owner is invalid");
        }
    }

    private static boolean exactObjectSet(
            Connection connection, String bootstrapPrincipal) throws SQLException {
        MigrationAdoptionGuard.Contract contract = new MigrationAdoptionGuard.Contract(
                "control-metadata", SCHEMA, RECEIPT, List.of(SCHEMA));
        Set<String> actual = MigrationAdoptionGuard.liveInventory(
                        connection, contract, bootstrapPrincipal)
                .stream()
                .map(entry -> entry.objectClass() + ":" + entry.schema() + ":"
                        + entry.identity() + ":" + entry.owner())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> expected = Set.of(
                "SCHEMA:" + SCHEMA + ":" + SCHEMA
                        + "|extension-member-count=0|extension-member-sha256="
                        + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855:"
                        + bootstrapPrincipal,
                "RELATION:" + SCHEMA + ":r:" + SCHEMA + "." + RECEIPT
                        + ":" + bootstrapPrincipal,
                "RELATION:" + SCHEMA + ":i:" + SCHEMA + "." + RECEIPT
                        + "_pkey:" + bootstrapPrincipal,
                "RELATION:" + SCHEMA + ":r:" + SCHEMA + "." + INVENTORY
                        + ":" + bootstrapPrincipal,
                "RELATION:" + SCHEMA + ":i:" + SCHEMA + "." + INVENTORY
                        + "_pkey:" + bootstrapPrincipal);
        return actual.equals(expected);
    }

    private static String schemaOwnerMismatch(String bootstrapPrincipal) {
        return "SELECT COUNT(*) FROM pg_catalog.pg_namespace "
                + "WHERE nspname=" + quoteLiteral(SCHEMA)
                + " AND pg_get_userbyid(nspowner)<>" + quoteLiteral(bootstrapPrincipal);
    }

    private static String relationOwnerMismatch(String bootstrapPrincipal) {
        return "SELECT COUNT(*) FROM pg_catalog.pg_class object "
                + "JOIN pg_catalog.pg_namespace namespace ON namespace.oid=object.relnamespace "
                + "WHERE namespace.nspname=" + quoteLiteral(SCHEMA)
                + " AND object.relname IN (" + quoteLiteral(RECEIPT) + ","
                + quoteLiteral(INVENTORY) + ") AND (object.relkind<>'r' OR "
                + "pg_get_userbyid(object.relowner)<>" + quoteLiteral(bootstrapPrincipal) + ")";
    }

    private static String columnMismatch() {
        return """
                WITH expected(table_name, ordinal_position, column_name, data_type, udt_name,
                              is_nullable) AS (
                    VALUES
                      ('adoption_receipt',1,'stream_key','text','text','NO'),
                      ('adoption_receipt',2,'contract_version','integer','int4','NO'),
                      ('adoption_receipt',3,'database_name','text','text','NO'),
                      ('adoption_receipt',4,'history_schema','text','text','NO'),
                      ('adoption_receipt',5,'history_table','text','text','NO'),
                      ('adoption_receipt',6,'legacy_max_installed_rank','integer','int4','NO'),
                      ('adoption_receipt',7,'sealed_history_max_installed_rank','integer','int4','NO'),
                      ('adoption_receipt',8,'sealed_history_row_count','integer','int4','NO'),
                      ('adoption_receipt',9,'sealed_history_sha256','text','text','NO'),
                      ('adoption_receipt',10,'inventory_object_count','integer','int4','NO'),
                      ('adoption_receipt',11,'inventory_sha256','text','text','NO'),
                      ('adoption_receipt',12,'migration_principal','text','text','NO'),
                      ('adoption_receipt',13,'control_reference','text','text','NO'),
                      ('adoption_receipt',14,'sealed_at_utc','text','text','NO'),
                      ('adoption_receipt',15,'receipt_sha256','text','text','NO'),
                      ('adoption_inventory',1,'stream_key','text','text','NO'),
                      ('adoption_inventory',2,'object_class','text','text','NO'),
                      ('adoption_inventory',3,'object_oid','oid','oid','NO'),
                      ('adoption_inventory',4,'object_schema','text','text','NO'),
                      ('adoption_inventory',5,'object_identity','text','text','NO'),
                      ('adoption_inventory',6,'adopted_owner','text','text','NO')
                ), actual AS (
                    SELECT table_name, ordinal_position, column_name, data_type, udt_name,
                           is_nullable
                      FROM information_schema.columns
                     WHERE table_schema=%s
                       AND table_name IN (%s,%s)
                )
                SELECT COUNT(*) FROM (
                    (SELECT * FROM expected EXCEPT SELECT * FROM actual)
                    UNION ALL
                    (SELECT * FROM actual EXCEPT SELECT * FROM expected)
                ) mismatch
                """.formatted(
                        quoteLiteral(SCHEMA), quoteLiteral(RECEIPT), quoteLiteral(INVENTORY));
    }

    private static String primaryKeyMismatch() {
        return """
                WITH expected(table_name, ordinal_position, column_name) AS (
                    VALUES
                      ('adoption_receipt',1,'stream_key'),
                      ('adoption_inventory',1,'stream_key'),
                      ('adoption_inventory',2,'object_class'),
                      ('adoption_inventory',3,'object_oid')
                ), actual AS (
                    SELECT relation.relname, key.ordinality::integer, attribute.attname
                      FROM pg_catalog.pg_constraint constraint_row
                      JOIN pg_catalog.pg_class relation ON relation.oid=constraint_row.conrelid
                      JOIN pg_catalog.pg_namespace namespace ON namespace.oid=relation.relnamespace
                      CROSS JOIN LATERAL unnest(constraint_row.conkey)
                           WITH ORDINALITY AS key(attnum, ordinality)
                      JOIN pg_catalog.pg_attribute attribute
                        ON attribute.attrelid=relation.oid AND attribute.attnum=key.attnum
                     WHERE namespace.nspname=%s AND constraint_row.contype='p'
                       AND relation.relname IN (%s,%s)
                )
                SELECT COUNT(*) FROM (
                    (SELECT * FROM expected EXCEPT SELECT * FROM actual)
                    UNION ALL
                    (SELECT * FROM actual EXCEPT SELECT * FROM expected)
                ) mismatch
                """.formatted(
                        quoteLiteral(SCHEMA), quoteLiteral(RECEIPT), quoteLiteral(INVENTORY));
    }

    private static String unexpectedConstraintCount() {
        return "SELECT COUNT(*) FROM pg_catalog.pg_constraint constraint_row "
                + "JOIN pg_catalog.pg_class relation ON relation.oid=constraint_row.conrelid "
                + "JOIN pg_catalog.pg_namespace namespace ON namespace.oid=relation.relnamespace "
                + "WHERE namespace.nspname=" + quoteLiteral(SCHEMA)
                + " AND relation.relname IN (" + quoteLiteral(RECEIPT) + ","
                + quoteLiteral(INVENTORY)
                + ") AND constraint_row.contype NOT IN ('p','n')";
    }
}
