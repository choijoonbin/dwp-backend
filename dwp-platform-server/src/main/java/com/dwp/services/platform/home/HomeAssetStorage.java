package com.dwp.services.platform.home;

import org.springframework.core.io.Resource;

public interface HomeAssetStorage {

    String store(Long tenantId, String extension, byte[] content);

    Resource load(Long tenantId, String storageKey);

    void delete(Long tenantId, String storageKey);
}
