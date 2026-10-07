package com.dwp.core.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

/** Verifies the sealed Control receipt tables and their exact privilege profile. */
final class MigrationAdoptionControlMetadataGuard {
    private MigrationAdoptionControlMetadataGuard() {
    }

    static ReceiptRow readReceipt(
            Connection connection, MigrationAdoptionEvidenceTypes.ContractView contract)
            throws SQLException {
        if (!relationExists(connection, MigrationAdoptionEvidenceTypes.CONTROL_SCHEMA,
                MigrationAdoptionEvidenceTypes.RECEIPT_TABLE)) {
            throw failure(
                    contract, "strict legacy startup requires a Control receipt");
        }
        String sql = "SELECT contract_version, database_name, history_schema, history_table, "
                + "legacy_max_installed_rank, sealed_history_max_installed_rank, "
                + "sealed_history_row_count, sealed_history_sha256, inventory_object_count, "
                + "inventory_sha256, migration_principal, control_reference, sealed_at_utc, "
                + "receipt_sha256 FROM " + qualified(
                        MigrationAdoptionEvidenceTypes.CONTROL_SCHEMA,
                        MigrationAdoptionEvidenceTypes.RECEIPT_TABLE)
                + " WHERE stream_key = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, contract.streamKey());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw failure(
                            contract, "strict legacy startup requires a Control receipt");
                }
                ReceiptRow receipt = new ReceiptRow(
                        contract.streamKey(), result.getInt(1), result.getString(2),
                        result.getString(3), result.getString(4), result.getInt(5),
                        result.getInt(6), result.getInt(7), result.getString(8),
                        result.getInt(9), result.getString(10), result.getString(11),
                        result.getString(12), result.getString(13), result.getString(14));
                if (result.next()) {
                    throw failure(
                            contract, "duplicate Control receipts found");
                }
                return receipt;
            }
        }
    }

    static boolean receiptExists(Connection connection, String streamKey) throws SQLException {
        if (!relationExists(connection, MigrationAdoptionEvidenceTypes.CONTROL_SCHEMA,
                MigrationAdoptionEvidenceTypes.RECEIPT_TABLE)) {
            return false;
        }
        String sql = "SELECT EXISTS (SELECT 1 FROM "
                + qualified(
                        MigrationAdoptionEvidenceTypes.CONTROL_SCHEMA,
                        MigrationAdoptionEvidenceTypes.RECEIPT_TABLE)
                + " WHERE stream_key = ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, streamKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    static void verifyPrivileges(
            MigrationAdoptionEvidenceTypes.ContractView contract,
            DataSource migrationDataSource,
            DataSource runtimeDataSource,
            String migrationPrincipal) {
        ObjectIds objectIds = controlObjectIds(contract, migrationDataSource);
        try (Connection connection = migrationDataSource.getConnection()) {
            verifyPublicPrivileges(contract, connection, objectIds);
        } catch (SQLException exception) {
            throw failure(
                    contract, "cannot verify PUBLIC Control metadata privileges", exception);
        }
        verifyPrincipal(contract, migrationDataSource, migrationPrincipal, true, objectIds);
        verifyPrincipal(contract, runtimeDataSource, null, false, objectIds);
    }

    static void verifyPublicPrivileges(
            MigrationAdoptionEvidenceTypes.ContractView contract,
            Connection connection,
            ObjectIds objectIds) throws SQLException {
        String sql = """
                SELECT NOT EXISTS (
                           SELECT 1
                             FROM pg_catalog.pg_namespace namespace,
                                  LATERAL aclexplode(COALESCE(
                                      namespace.nspacl,
                                      acldefault('n', namespace.nspowner))) acl
                            WHERE namespace.oid = ?::oid
                              AND acl.grantee = 0
                              AND acl.privilege_type IN ('USAGE', 'CREATE'))
                       AND NOT EXISTS (
                           SELECT 1
                             FROM pg_catalog.pg_class object,
                                  LATERAL aclexplode(COALESCE(
                                      object.relacl,
                                      acldefault('r', object.relowner))) acl
                            WHERE object.oid IN (?::oid, ?::oid)
                              AND acl.grantee = 0
                              AND acl.privilege_type IN (
                                  'SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE',
                                  'REFERENCES', 'TRIGGER'))
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, objectIds.schemaOid());
            statement.setLong(2, objectIds.receiptOid());
            statement.setLong(3, objectIds.inventoryOid());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw failure(
                            contract, "PUBLIC retains Control metadata privileges");
                }
            }
        }
    }

    static ObjectIds controlObjectIds(
            MigrationAdoptionEvidenceTypes.ContractView contract, Connection connection)
            throws SQLException {
        String sql = """
                SELECT namespace.oid::bigint,
                       receipt.oid::bigint,
                       inventory.oid::bigint
                  FROM pg_catalog.pg_namespace namespace
                  JOIN pg_catalog.pg_class receipt
                    ON receipt.relnamespace = namespace.oid AND receipt.relname = ?
                  JOIN pg_catalog.pg_class inventory
                    ON inventory.relnamespace = namespace.oid AND inventory.relname = ?
                 WHERE namespace.nspname = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, MigrationAdoptionEvidenceTypes.RECEIPT_TABLE);
            statement.setString(2, MigrationAdoptionEvidenceTypes.INVENTORY_TABLE);
            statement.setString(3, MigrationAdoptionEvidenceTypes.CONTROL_SCHEMA);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw failure(
                            contract, "Control metadata objects are missing");
                }
                ObjectIds ids = new ObjectIds(
                        result.getLong(1), result.getLong(2), result.getLong(3));
                if (result.next()) {
                    throw failure(
                            contract, "duplicate Control metadata objects found");
                }
                return ids;
            }
        }
    }

    private static ObjectIds controlObjectIds(
            MigrationAdoptionEvidenceTypes.ContractView contract,
            DataSource migrationDataSource) {
        try (Connection connection = migrationDataSource.getConnection()) {
            return controlObjectIds(contract, connection);
        } catch (SQLException exception) {
            throw failure(
                    contract, "cannot resolve Control metadata object identities", exception);
        }
    }

    private static void verifyPrincipal(
            MigrationAdoptionEvidenceTypes.ContractView contract,
            DataSource dataSource,
            String expectedPrincipal,
            boolean migration,
            ObjectIds objectIds) {
        String sql = "SELECT current_user, "
                + "has_schema_privilege(current_user, ?::oid, 'USAGE'), "
                + "has_schema_privilege(current_user, ?::oid, 'CREATE'), "
                + "has_table_privilege(current_user, ?::oid, 'SELECT'), "
                + "has_table_privilege(current_user, ?::oid, "
                + "'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'), "
                + "has_table_privilege(current_user, ?::oid, 'SELECT'), "
                + "has_table_privilege(current_user, ?::oid, "
                + "'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, objectIds.schemaOid());
            statement.setLong(2, objectIds.schemaOid());
            statement.setLong(3, objectIds.receiptOid());
            statement.setLong(4, objectIds.receiptOid());
            statement.setLong(5, objectIds.inventoryOid());
            statement.setLong(6, objectIds.inventoryOid());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw failure(
                            contract, "Control privilege query returned no row");
                }
                boolean valid = migration
                        ? expectedPrincipal.equals(result.getString(1))
                                && result.getBoolean(2) && !result.getBoolean(3)
                                && result.getBoolean(4) && !result.getBoolean(5)
                                && result.getBoolean(6) && !result.getBoolean(7)
                        : !result.getBoolean(2) && !result.getBoolean(3)
                                && !result.getBoolean(4) && !result.getBoolean(5)
                                && !result.getBoolean(6) && !result.getBoolean(7);
                if (!valid) {
                    throw failure(
                            contract, (migration ? "migration" : "runtime")
                                    + " Control metadata privilege profile is invalid");
                }
            }
        } catch (SQLException exception) {
            throw failure(
                    contract, "cannot verify Control metadata privileges", exception);
        }
    }

    private static boolean relationExists(Connection connection, String schema, String table)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT to_regclass(format('%I.%I', ?, ?)) IS NOT NULL")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static String qualified(String schema, String table) {
        if (!schema.matches("[a-z_][a-z0-9_]{0,62}")
                || !table.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Control metadata name is not canonical");
        }
        return '"' + schema + "\".\"" + table + '"';
    }

    private static IllegalStateException failure(
            MigrationAdoptionEvidenceTypes.ContractView contract, String message) {
        return new IllegalStateException(
                "Migration adoption rejected for " + contract.streamKey() + ": " + message);
    }

    private static IllegalStateException failure(
            MigrationAdoptionEvidenceTypes.ContractView contract,
            String message,
            Exception cause) {
        return new IllegalStateException(
                "Migration adoption rejected for " + contract.streamKey() + ": " + message,
                cause);
    }

    record ObjectIds(long schemaOid, long receiptOid, long inventoryOid) {
    }

    record ReceiptRow(
            String streamKey,
            int contractVersion,
            String databaseName,
            String historySchema,
            String historyTable,
            int legacyMaxInstalledRank,
            int sealedHistoryMaxInstalledRank,
            int sealedHistoryRowCount,
            String sealedHistorySha256,
            int inventoryObjectCount,
            String inventorySha256,
            String migrationPrincipal,
            String controlReference,
            String sealedAtUtc,
            String receiptSha256) {
    }
}
