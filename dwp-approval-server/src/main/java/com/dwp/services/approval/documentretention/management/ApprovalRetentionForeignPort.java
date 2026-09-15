package com.dwp.services.approval.documentretention.management;

import static com.dwp.services.approval.documentretention.management.ApprovalRetentionForeignDtos.*;

/** Owner interface: only each consumer can enumerate and erase its declared delivered copies. */
public interface ApprovalRetentionForeignPort {
    String consumerService();
    default boolean configured() { return true; }
    SignedAck deleteDeclaredCopies(DeletionRequest request);

    /**
     * Resolve a previously unknown command outcome without issuing a new delete command.
     * Implementations must query or idempotently reconcile the same deletionRequestId.
     */
    SignedAck reconcileDeclaredCopies(DeletionRequest request);
}
