package com.dwp.services.platform.savedview;

import java.util.LinkedHashMap;
import java.util.Map;

final class SavedViewAuditSnapshots {

    private SavedViewAuditSnapshots() {
    }

    static Map<String, Object> view(SavedViewRepository.Row row) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("savedViewId", row.id());
        snapshot.put("surfaceKey", row.surfaceKey());
        snapshot.put("scope", row.scope());
        snapshot.put("ownerUserId", row.ownerUserId());
        snapshot.put("ownerGroupRef", row.ownerGroupRef());
        snapshot.put("lifecycleState", row.lifecycleState());
        snapshot.put("version", row.version());
        return snapshot;
    }

    static Map<String, Object> transfer(SavedViewDtos.OwnershipTransfer transfer) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("transferBatchId", transfer.transferBatchId());
        snapshot.put("sourceOwnerUserId", transfer.sourceOwnerUserId());
        snapshot.put("sourceOwnerDisplayName", transfer.sourceOwnerDisplayName());
        snapshot.put("targetOwnerUserId", transfer.targetOwnerUserId());
        snapshot.put("targetOwnerDisplayName", transfer.targetOwnerDisplayName());
        snapshot.put("disposition", transfer.disposition());
        snapshot.put("reasonCode", transfer.reasonCode());
        snapshot.put("reason", transfer.reason());
        snapshot.put("sourceReference", transfer.sourceReference());
        snapshot.put("retentionUntil", transfer.retentionUntil());
        snapshot.put("transferredCount", transfer.transferredCount());
        snapshot.put("ownershipFingerprint", transfer.ownershipFingerprint());
        return snapshot;
    }
}
