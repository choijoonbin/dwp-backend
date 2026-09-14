package com.dwp.services.approval.attachment;

import java.time.Instant;

public interface ApprovalAttachmentScanner {
    enum Verdict { AV_CLEAR, MALWARE, INDETERMINATE }
    record Result(Verdict verdict, String reason, String engineVersion, Instant definitionsAt, Instant scannedAt, String contentSha256) { }
    String readiness();
    Result scan(byte[] bytes, String sha256);
}
