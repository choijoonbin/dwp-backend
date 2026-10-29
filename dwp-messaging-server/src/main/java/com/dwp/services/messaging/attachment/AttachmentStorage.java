package com.dwp.services.messaging.attachment;

public interface AttachmentStorage {

    void store(String objectKey, byte[] content);

    byte[] load(String objectKey);

    void delete(String objectKey);

    default ProviderHealth health() {
        return ProviderHealth.unavailable("STORAGE_HEALTH_UNSUPPORTED");
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
}
