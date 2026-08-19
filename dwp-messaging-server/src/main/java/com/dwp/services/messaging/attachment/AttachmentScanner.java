package com.dwp.services.messaging.attachment;

public interface AttachmentScanner {

    ScanResult scan(ScanRequest request, byte[] content);

    record ScanRequest(String filename, String extension, String declaredContentType) {
    }

    record ScanResult(boolean clean, String detectedContentType, String reason) {

        static ScanResult clean(String detectedContentType) {
            return new ScanResult(true, detectedContentType, null);
        }

        static ScanResult rejected(String detectedContentType, String reason) {
            return new ScanResult(false, detectedContentType, reason);
        }
    }
}
