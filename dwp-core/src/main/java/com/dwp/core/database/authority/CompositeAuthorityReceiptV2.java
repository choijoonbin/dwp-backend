package com.dwp.core.database.authority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import com.dwp.core.database.MigrationAdoptionGuard;

import static com.dwp.core.database.authority.RuntimeStartupValues.*;

/** Contract successor only. No current scalar/composite producer, native pool proof or thirteen-stream activation. */
public record CompositeAuthorityReceiptV2(String schemaVersion, String topologyRevision,
        String manifestSha256, String controlReference, String previousCompositeReceiptSha256,
        List<StreamSeal> streams, String receiptSha256) {
    public static final String VERSION = "2.0";
    public static final String EMPTY_HISTORY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    public static final String STAGED_SOURCE_VERSION = "DWP_STAGED_MIGRATION_SOURCE_V1";
    public static final String EMPTY_SOURCE_SHA256 = emptySourceDigest();

    public CompositeAuthorityReceiptV2 {
        require(VERSION.equals(schemaVersion) && (ScalarNineRuntimeRegistryContract.TOPOLOGY.equals(topologyRevision)
                || ExactStreamTopology.VERSION.equals(topologyRevision)), "composite v2 topology or version differs");
        digest(manifestSha256); RuntimeStartupValues.controlReference(controlReference);
        optionalDigest(previousCompositeReceiptSha256); digest(receiptSha256);
        streams = ordered(streams, StreamSeal::streamKey, "composite v2 streams");
        require(!streams.isEmpty(), "composite v2 streams missing");
        for (int index = 0; index < streams.size(); index++) {
            var stream = streams.get(index);
            require(stream.bootstrapOrder() == index + 1, "composite bootstrap order differs");
            if (stream.adoptedHistory() != null) {
                require(stream.adoptedHistory().receipt().controlReference().equals(controlReference), "adoption current control differs");
            }
        }
    }

    public record StreamSeal(String service, String streamKey, String database, String schema, String historyTable,
            String authoritySha256, String migrationPrincipal, String postgresVersion,
            int pendingMigrationCount, int historyMaxInstalledRank, int historyRowCount, String historySha256,
            int inventoryObjectCount, String definitionAclOwnershipInventorySha256,
            boolean temporaryPrivilegeRevoked, int bootstrapOrder,
            NativeHistoryProof nativeHistory, AdoptedHistoryProof adoptedHistory) {
        public StreamSeal {
            var oracle = ExactStreamTopology.BY_KEY.get(streamKey);
            require(oracle != null && oracle.service().equals(service) && oracle.schema().equals(schema)
                    && oracle.historyTable().equals(historyTable), "composite stream identity differs");
            identifier(database); identifier(migrationPrincipal); digest(authoritySha256); digest(historySha256);
            digest(definitionAclOwnershipInventorySha256);
            require(postgresVersion != null && postgresVersion.matches("[1-9][0-9]*(?:\\.[0-9]+){0,2}"), "postgres version invalid");
            require(pendingMigrationCount == 0 && historyRowCount >= 0 && historyMaxInstalledRank >= historyRowCount
                    && (historyRowCount > 0 || historyMaxInstalledRank == 0) && inventoryObjectCount >= 1
                    && bootstrapOrder > 0 && temporaryPrivilegeRevoked, "composite history or inventory not preapplied");
            require((nativeHistory == null) != (adoptedHistory == null), "native and adopted evidence must be exclusive");
            if (nativeHistory != null) {
                require(nativeHistory.historyRelationOid() > 0, "native history relation missing");
                if (historyRowCount == 0) {
                    require(historySha256.equals(EMPTY_HISTORY_SHA256) && nativeHistory.sourceInventory().genuinelyEmpty(),
                            "empty history requires independently pinned genuinely empty staged sources");
                } else {
                    require(historyRowCount >= nativeHistory.sourceInventory().fileCount(), "unapplied staged migration sources");
                }
            } else {
                var receipt = adoptedHistory.receipt();
                require(receipt.streamKey().equals(streamKey) && receipt.databaseName().equals(database)
                        && receipt.historySchema().equals(schema) && receipt.historyTable().equals(historyTable)
                        && receipt.migrationPrincipal().equals(migrationPrincipal)
                        && receipt.sealedHistoryMaxInstalledRank() == historyMaxInstalledRank
                        && receipt.sealedHistoryRowCount() == historyRowCount && receipt.sealedHistorySha256().equals(historySha256)
                        && receipt.inventoryObjectCount() == inventoryObjectCount
                        && receipt.inventorySha256().equals(definitionAclOwnershipInventorySha256), "adoption original provenance differs");
            }
        }
    }

    public record StagedSourceInventory(String compilerVersion, int versionedFileCount, int repeatableFileCount,
            int fileCount, String inventorySha256) {
        public StagedSourceInventory {
            require(STAGED_SOURCE_VERSION.equals(compilerVersion) && versionedFileCount >= 0 && repeatableFileCount >= 0
                    && fileCount == (long) versionedFileCount + repeatableFileCount, "staged source inventory invalid");
            digest(inventorySha256);
            require(fileCount != 0 || inventorySha256.equals(EMPTY_SOURCE_SHA256), "empty staged source digest differs");
        }
        public boolean genuinelyEmpty() { return fileCount == 0 && inventorySha256.equals(EMPTY_SOURCE_SHA256); }
    }

    public record NativeHistoryProof(long historyRelationOid, String historyRelationDefinitionSha256,
            StagedSourceInventory sourceInventory) {
        public NativeHistoryProof {
            require(historyRelationOid > 0 && sourceInventory != null, "native history relation or staged source proof missing");
            digest(historyRelationDefinitionSha256);
        }
    }

    /** Full v2 receipt embeds the original full-field history digest; never rewrites installed_by/checksum. */
    public record AdoptedHistoryProof(MigrationAdoptionGuard.Receipt receipt, String previousAdoptionReceiptSha256) {
        public AdoptedHistoryProof {
            require(receipt != null && receipt.contractVersion() == 2 && receipt.legacyMaxInstalledRank() > 0
                    && receipt.sealedHistoryMaxInstalledRank() >= receipt.legacyMaxInstalledRank()
                    && receipt.sealedHistoryRowCount() > 0 && receipt.inventoryObjectCount() > 0, "adoption v2 boundary invalid");
            key(receipt.streamKey()); identifier(receipt.databaseName()); identifier(receipt.historySchema()); identifier(receipt.historyTable());
            identifier(receipt.migrationPrincipal()); RuntimeStartupValues.controlReference(receipt.controlReference()); instant(receipt.sealedAtUtc());
            digest(receipt.sealedHistorySha256()); digest(receipt.inventorySha256()); digest(receipt.receiptSha256());
            require(MigrationAdoptionGuard.digestReceipt(receipt).equals(receipt.receiptSha256()), "adoption v2 original digest differs");
            optionalDigest(previousAdoptionReceiptSha256);
        }
    }

    static void optionalDigest(String value) { require(value != null, "optional digest missing"); if (!value.isEmpty()) digest(value); }
    private static String emptySourceDigest() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                "dwp-staged-migration-source-inventory-v1\n[]".getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw failure("staged source digest unavailable"); }
    }
}
