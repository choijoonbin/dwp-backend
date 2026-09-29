package com.dwp.core.database;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.dwp.core.database.MigrationAdoptionEvidenceTypes.ContractView;
import com.dwp.core.database.MigrationAdoptionEvidenceTypes.HistoryDigestValue;
import com.dwp.core.database.MigrationAdoptionEvidenceTypes.InventoryDigestValue;
import com.dwp.core.database.MigrationAdoptionEvidenceTypes.InventoryEntryValue;
import com.dwp.core.database.MigrationAdoptionEvidenceTypes.InventoryEntryView;
import com.dwp.core.database.MigrationAdoptionEvidenceTypes.ReceiptView;

/** Canonical byte and catalog evidence shared by the app guard and Control runner. */
final class MigrationAdoptionEvidence {

    private MigrationAdoptionEvidence() {
    }

    static HistoryDigestValue digestHistory(
            Connection connection,
            ContractView contract,
            int sealedMaxInstalledRank,
            int legacyMaxInstalledRank,
            String migrationPrincipal) throws SQLException {
        String sql = "SELECT installed_rank, version, description, type, script, checksum, "
                + "installed_by, FLOOR(EXTRACT(EPOCH FROM installed_on) * 1000000)::bigint, "
                + "execution_time, success FROM "
                + qualified(contract.historySchema(), contract.historyTable())
                + " WHERE installed_rank <= ? ORDER BY installed_rank";
        MessageDigest digest = sha256();
        int rows = 0;
        int maxRank = 0;
        boolean allSuccessful = true;
        boolean hasLegacyPrincipal = false;
        boolean postLegacyOwnedByMigration = true;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, sealedMaxInstalledRank);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    int rank = result.getInt(1);
                    String installedBy = result.getString(7);
                    append(digest, "history-v2");
                    for (int column = 1; column <= 10; column++) {
                        append(digest, column == 10
                                ? result.getBoolean(column)
                                : result.getObject(column));
                    }
                    allSuccessful &= result.getBoolean(10);
                    hasLegacyPrincipal |= rank <= legacyMaxInstalledRank
                            && !migrationPrincipal.equals(installedBy);
                    postLegacyOwnedByMigration &= rank <= legacyMaxInstalledRank
                            || migrationPrincipal.equals(installedBy);
                    rows++;
                    maxRank = Math.max(maxRank, rank);
                }
            }
        }
        return new HistoryDigestValue(
                rows,
                maxRank,
                HexFormat.of().formatHex(digest.digest()),
                allSuccessful,
                hasLegacyPrincipal,
                postLegacyOwnedByMigration);
    }

    static InventoryDigestValue digestStoredInventory(
            Connection connection,
            ContractView contract,
            Set<String> approvedOwners) throws SQLException {
        String sql = "SELECT object_class, object_oid::bigint, object_schema, object_identity, "
                + "adopted_owner FROM " + qualified(
                        MigrationAdoptionEvidenceTypes.CONTROL_SCHEMA,
                        MigrationAdoptionEvidenceTypes.INVENTORY_TABLE)
                + " WHERE stream_key = ? ORDER BY object_class, object_schema, "
                + "object_identity, object_oid";
        List<InventoryEntryValue> objects = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, contract.streamKey());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    InventoryEntryValue object = new InventoryEntryValue(
                            result.getString(1), result.getLong(2), result.getString(3),
                            result.getString(4), result.getString(5));
                    if (!approvedOwners.contains(object.owner())) {
                        throw failure(contract,
                                "stored inventory owner is not an approved protected-object owner");
                    }
                    objects.add(object);
                }
            }
        }
        return digestInventory(objects);
    }

    static InventoryDigestValue digestLiveInventory(
            Connection connection,
            ContractView contract,
            Set<String> approvedOwners) throws SQLException {
        return digestInventory(liveInventory(connection, contract, approvedOwners));
    }

    static List<InventoryEntryValue> liveInventory(
            Connection connection,
            ContractView contract,
            Set<String> approvedOwners) throws SQLException {
        List<InventoryEntryValue> objects = new ArrayList<>();
        for (ProtectedSchemaObjectInventory.Entry entry
                : ProtectedSchemaObjectInventory.read(
                        connection, contract.protectedSchemas())) {
            InventoryEntryValue object = new InventoryEntryValue(
                    entry.objectClass(), entry.oid(), entry.schema(),
                    entry.identity(), entry.owner());
            if (!approvedOwners.contains(object.owner())) {
                throw failure(contract,
                        "live protected object is not owned by an approved protected-object owner");
            }
            objects.add(object);
        }
        return List.copyOf(objects);
    }

    static InventoryDigestValue digestInventory(
            List<? extends InventoryEntryView> objects) {
        Objects.requireNonNull(objects, "objects must not be null");
        List<? extends InventoryEntryView> canonical = objects.stream()
                .sorted(Comparator.comparing(InventoryEntryView::objectClass)
                        .thenComparing(InventoryEntryView::schema)
                        .thenComparing(InventoryEntryView::identity)
                        .thenComparingLong(InventoryEntryView::oid))
                .toList();
        MessageDigest digest = sha256();
        for (InventoryEntryView object : canonical) {
            append(digest, "inventory-v2");
            append(digest, object.objectClass());
            append(digest, object.oid());
            append(digest, object.schema());
            append(digest, object.identity());
            append(digest, object.owner());
        }
        return new InventoryDigestValue(
                canonical.size(), HexFormat.of().formatHex(digest.digest()));
    }

    static String digestReceipt(ReceiptView receipt) {
        MessageDigest digest = sha256();
        append(digest, "migration-adoption-receipt-v2");
        append(digest, receipt.streamKey());
        append(digest, receipt.contractVersion());
        append(digest, receipt.databaseName());
        append(digest, receipt.historySchema());
        append(digest, receipt.historyTable());
        append(digest, receipt.legacyMaxInstalledRank());
        append(digest, receipt.sealedHistoryMaxInstalledRank());
        append(digest, receipt.sealedHistoryRowCount());
        append(digest, receipt.sealedHistorySha256());
        append(digest, receipt.inventoryObjectCount());
        append(digest, receipt.inventorySha256());
        append(digest, receipt.migrationPrincipal());
        append(digest, receipt.controlReference());
        append(digest, receipt.sealedAtUtc());
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void append(MessageDigest digest, Object value) {
        if (value == null) {
            digest.update("-1:".getBytes(StandardCharsets.US_ASCII));
            return;
        }
        String canonical = value instanceof Boolean bool
                ? (bool ? "true" : "false")
                : value.toString();
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String qualified(String schema, String table) {
        if (schema == null || !schema.matches("[a-z][a-z0-9_]{0,62}")
                || table == null || !table.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("schema/table must be canonical SQL identifiers");
        }
        return '"' + schema + "\".\"" + table + '"';
    }

    private static IllegalStateException failure(ContractView contract, String message) {
        return new IllegalStateException(
                "Migration adoption rejected for " + contract.streamKey() + ": " + message);
    }
}
