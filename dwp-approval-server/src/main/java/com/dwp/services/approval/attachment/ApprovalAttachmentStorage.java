package com.dwp.services.approval.attachment;

public interface ApprovalAttachmentStorage {
    record Stored(String objectKey, String versionId, long sizeBytes, String sha256) { }
    String readiness();
    Stored put(String objectKey, byte[] content, String sha256);
    Stored reconcile(String objectKey, long sizeBytes, String sha256);
    byte[] load(Stored stored);
    void deleteUnbound(Stored stored);
}
