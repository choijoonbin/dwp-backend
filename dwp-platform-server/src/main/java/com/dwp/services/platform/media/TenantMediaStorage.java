package com.dwp.services.platform.media;

import org.springframework.core.io.Resource;

public interface TenantMediaStorage {

    String store(Long tenantId, String category, String extension, byte[] content);

    Resource load(Long tenantId, String storageKey);

    void delete(Long tenantId, String storageKey);
}
