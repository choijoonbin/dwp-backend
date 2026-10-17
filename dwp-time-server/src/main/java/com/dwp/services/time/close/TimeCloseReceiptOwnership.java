package com.dwp.services.time.close;

import java.util.Optional;
import java.util.UUID;

/** Stable owner seam for a later central command-receipt provider adapter. */
public interface TimeCloseReceiptOwnership {
    boolean owns(long tenantId, UUID receiptId);

    Optional<TimeCloseModels.TimeCloseCommandReceiptV1> reauthorizedRead(
            long tenantId, UUID receiptId, OriginAuthority authority);

    /** Provider-neutral callback that re-evaluates the immutable origin profile. */
    @FunctionalInterface
    interface OriginAuthority {
        boolean permits(TimeCloseModels.StoredReceipt receipt);
    }
}
