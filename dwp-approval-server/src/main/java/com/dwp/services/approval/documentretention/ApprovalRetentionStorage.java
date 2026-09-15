package com.dwp.services.approval.documentretention;

import com.dwp.services.approval.attachment.ApprovalAttachmentStorage.Stored;

public interface ApprovalRetentionStorage {
    default boolean configured() { return true; }
    String locatorSha256();
    Stored reconcile(String key, long size, String sha256);
    void verifyPresence(Stored stored);
    boolean deleteAndConfirmAbsent(Stored stored);
}
