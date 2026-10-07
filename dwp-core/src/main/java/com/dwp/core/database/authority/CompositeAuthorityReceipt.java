package com.dwp.core.database.authority;

import java.util.List;

import static com.dwp.core.database.authority.StreamAuthorityContract.*;

/** Proposed composite successor; current MigrationControlMain does not produce this contract. */
public record CompositeAuthorityReceipt(String schemaVersion, String topologyVersion,
        String manifestSha256, String controlReference, String previousCompositeReceiptSha256,
        String mode, List<StreamSeal> streams, String receiptSha256) {
    public static final String VERSION = "1.0";

    public CompositeAuthorityReceipt {
        require(VERSION.equals(schemaVersion), "unsupported composite receipt version");
        require(ExactStreamTopology.VERSION.equals(topologyVersion), "unsupported receipt topology");
        digest(manifestSha256, false); reference(controlReference);
        digest(previousCompositeReceiptSha256, true); digest(receiptSha256, false);
        require("NATIVE_PREAPPLIED".equals(mode), "adoption composite evidence is not implemented");
        streams = ordered(streams, StreamSeal::streamKey, "receipt streams");
        require(!streams.isEmpty(), "receipt streams missing");
        for (int index = 0; index < streams.size(); index++) {
            require(streams.get(index).bootstrapOrder() == index + 1, "bootstrap order must be exact");
        }
    }

    public StreamSeal stream(String key) {
        return streams.stream().filter(value -> value.streamKey().equals(key)).findFirst()
                .orElseThrow(() -> invalid("missing stream receipt"));
    }

    public record StreamSeal(String service, String streamKey, String database,
            String authoritySha256, String migrationPrincipal, String postgresVersion,
            int historyMaxInstalledRank, int historyRowCount, String historySha256,
            int inventoryObjectCount, String definitionAclOwnershipInventorySha256,
            String adoptionReceiptSha256, boolean temporaryPrivilegeRevoked, int bootstrapOrder) {
        public StreamSeal {
            identifier(service); key(streamKey); identifier(database); identifier(migrationPrincipal);
            require(ExactStreamTopology.BY_KEY.containsKey(streamKey), "unknown receipt stream");
            require(ExactStreamTopology.BY_KEY.get(streamKey).service().equals(service), "receipt stream service differs");
            digest(authoritySha256, false); digest(historySha256, false);
            digest(definitionAclOwnershipInventorySha256, false);
            require("".equals(adoptionReceiptSha256), "adoption composite provenance unsupported");
            require(postgresVersion != null && postgresVersion.matches("[1-9][0-9]*(?:\\.[0-9]+){0,2}"), "noncanonical postgres version");
            require(historyRowCount >= 1 && historyMaxInstalledRank >= historyRowCount
                    && inventoryObjectCount >= 2 && bootstrapOrder >= 1, "preapplied history/inventory required");
            require(temporaryPrivilegeRevoked, "temporary privilege was not revoked");
        }
    }
}
