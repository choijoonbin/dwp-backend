package com.dwp.services.approval.documentretention.management;

import static com.dwp.services.approval.documentretention.management.ApprovalRetentionForeignDtos.*;

/** Owner interface: only each consumer can enumerate and erase its declared delivered copies. */
public interface ApprovalRetentionForeignPort {
    String consumerService();
    SignedAck deleteDeclaredCopies(DeletionRequest request);
}
