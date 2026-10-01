package com.dwp.services.auth.tenantsettings;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public final class InternalEntitlementAdapterRegistry {

    private final List<InternalEntitlementAdapter> adapters;

    public InternalEntitlementAdapterRegistry(List<InternalEntitlementAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
        if (adapters.isEmpty()) {
            throw new IllegalStateException("At least one internal entitlement adapter is required.");
        }
    }

    Projection project(Long tenantId, List<Long> userIds, Instant observedAt) {
        Map<Long, List<TenantSettingsDtos.AccessGrant>> grants = new LinkedHashMap<>();
        userIds.forEach(id -> grants.put(id, new ArrayList<>()));
        List<TenantSettingsDtos.OwnerCoverage> owners = new ArrayList<>();
        for (InternalEntitlementAdapter adapter : adapters) {
            InternalEntitlementAdapter.AdapterProjection projected =
                    adapter.project(tenantId, userIds, observedAt);
            projected.grants().forEach((userId, source) ->
                    grants.computeIfAbsent(userId, ignored -> new ArrayList<>()).addAll(source));
            owners.addAll(projected.owners());
        }
        grants.replaceAll((ignored, value) -> List.copyOf(value));
        return new Projection(Map.copyOf(grants), List.copyOf(owners));
    }

    record Projection(
            Map<Long, List<TenantSettingsDtos.AccessGrant>> grants,
            List<TenantSettingsDtos.OwnerCoverage> owners) {
    }
}
