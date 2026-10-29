package com.dwp.services.messaging.attachment;

public interface AttachmentScanner {

    ScanResult scan(ScanRequest request, byte[] content);

    default ProviderHealth health() {
        return ProviderHealth.unavailable("SCANNER_HEALTH_UNSUPPORTED");
    }

    record ProviderHealth(boolean effective, boolean productionReady, String reasonCode) {

        static ProviderHealth ready() {
            return new ProviderHealth(true, true, null);
        }

        static ProviderHealth developmentOnly(String reasonCode) {
            return new ProviderHealth(true, false, reasonCode);
        }

        static ProviderHealth unavailable(String reasonCode) {
            return new ProviderHealth(false, false, reasonCode);
        }
    }

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
