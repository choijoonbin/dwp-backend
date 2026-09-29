package com.dwp.core.database;

import java.util.List;

/** Neutral evidence values prevent the public guard facade and evidence engine from cycling. */
final class MigrationAdoptionEvidenceTypes {

    static final String CONTROL_SCHEMA = "dwp_migration_control";
    static final String RECEIPT_TABLE = "adoption_receipt";
    static final String INVENTORY_TABLE = "adoption_inventory";

    private MigrationAdoptionEvidenceTypes() {
    }

    interface ContractView {
        String streamKey();

        String historySchema();

        String historyTable();

        List<String> protectedSchemas();
    }

    interface ReceiptView {
        String streamKey();

        int contractVersion();

        String databaseName();

        String historySchema();

        String historyTable();

        int legacyMaxInstalledRank();

        int sealedHistoryMaxInstalledRank();

        int sealedHistoryRowCount();

        String sealedHistorySha256();

        int inventoryObjectCount();

        String inventorySha256();

        String migrationPrincipal();

        String controlReference();

        String sealedAtUtc();
    }

    interface InventoryEntryView {
        String objectClass();

        long oid();

        String schema();

        String identity();

        String owner();
    }

    record HistoryDigestValue(
            int rowCount,
            int maxInstalledRank,
            String sha256,
            boolean allSuccessful,
            boolean hasLegacyPrincipal,
            boolean postLegacyOwnedByMigration) {
    }

    record InventoryDigestValue(int objectCount, String sha256) {
    }

    record InventoryEntryValue(
            String objectClass,
            long oid,
            String schema,
            String identity,
            String owner) implements InventoryEntryView {
    }
}
