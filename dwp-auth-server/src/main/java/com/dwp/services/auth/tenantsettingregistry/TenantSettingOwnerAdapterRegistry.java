package com.dwp.services.auth.tenantsettingregistry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public final class TenantSettingOwnerAdapterRegistry {

    private final Map<String, TenantSettingOwnerAdapter> bySetting;

    public TenantSettingOwnerAdapterRegistry(List<TenantSettingOwnerAdapter> adapters) {
        Map<String, TenantSettingOwnerAdapter> indexed = new LinkedHashMap<>();
        for (TenantSettingOwnerAdapter adapter : adapters) {
            if (indexed.putIfAbsent(adapter.settingKey(), adapter) != null) {
                throw new IllegalStateException(
                        "Duplicate tenant setting adapter: " + adapter.settingKey());
            }
        }
        bySetting = Map.copyOf(indexed);
    }

    public TenantSettingOwnerAdapter require(
            String settingKey, String expectedOwnerKey) {
        TenantSettingOwnerAdapter adapter = bySetting.get(settingKey);
        if (adapter == null || !adapter.ownerKey().equals(expectedOwnerKey)) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    "The active setting owner has no matching application adapter.");
        }
        return adapter;
    }

    public boolean supports(String settingKey, String expectedOwnerKey) {
        TenantSettingOwnerAdapter adapter = bySetting.get(settingKey);
        return adapter != null && adapter.ownerKey().equals(expectedOwnerKey);
    }

    public Optional<TenantSettingOwnerAdapter> find(
            String settingKey, String expectedOwnerKey) {
        TenantSettingOwnerAdapter adapter = bySetting.get(settingKey);
        return adapter != null && adapter.ownerKey().equals(expectedOwnerKey)
                ? Optional.of(adapter) : Optional.empty();
    }
}
