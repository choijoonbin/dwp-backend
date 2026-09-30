package com.dwp.services.auth.tenantsettings;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Extensible internal authority source for the tenant principal access projection. */
public interface InternalEntitlementAdapter {

    AdapterProjection project(Long tenantId, List<Long> userIds, Instant observedAt);

    record AdapterProjection(
            Map<Long, List<TenantSettingsDtos.AccessGrant>> grants,
            List<TenantSettingsDtos.OwnerCoverage> owners) {

        public AdapterProjection {
            grants = Map.copyOf(grants);
            owners = List.copyOf(owners);
        }
    }
}
