package com.dwp.services.platform.mail;

import java.util.UUID;
import java.time.Instant;

/** Trusted content scanner boundary used before a compose upload becomes sendable. */
public interface MailAttachmentScanner {

    ScanResult scan(ScanRequest request);

    default Readiness readiness() {
        return new Readiness(true, "IN_PROCESS", Instant.now(), null);
    }

    enum Verdict {
        CLEAN,
        REJECTED
    }

    record ScanRequest(
            long tenantId,
            long userId,
            UUID attachmentId,
            String fileName,
            String contentType,
            String checksumSha256,
            byte[] content) {

        public ScanRequest {
            if (tenantId <= 0 || userId <= 0 || attachmentId == null) {
                throw new IllegalArgumentException("Attachment scan identity is required.");
            }
            if (fileName == null || fileName.isBlank()
                    || contentType == null || contentType.isBlank()) {
                throw new IllegalArgumentException("Attachment scan metadata is required.");
            }
            if (checksumSha256 == null || !checksumSha256.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Attachment scan checksum is invalid.");
            }
            if (content == null || content.length == 0) {
                throw new IllegalArgumentException("Attachment scan content is required.");
            }
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    record ScanResult(Verdict verdict, String evidence) {

        public ScanResult {
            if (verdict == null) {
                throw new IllegalArgumentException("Attachment scan verdict is required.");
            }
            evidence = evidence == null ? null : evidence.trim();
            if (verdict == Verdict.CLEAN && (evidence == null || evidence.isBlank())) {
                throw new IllegalArgumentException(
                        "A clean attachment scan requires nonblank evidence.");
            }
        }
    }

    record Readiness(
            boolean ready,
            String owner,
            Instant checkedAt,
            String errorCode) {

        public Readiness {
            owner = owner == null || owner.isBlank() ? "UNKNOWN" : owner.trim();
            if (checkedAt == null) throw new IllegalArgumentException("checkedAt is required");
            errorCode = errorCode == null ? null : errorCode.trim();
        }
    }
}
