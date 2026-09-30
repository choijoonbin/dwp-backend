package com.dwp.services.auth.tenantcapabilityoverride;

import java.time.Instant;

/** Runtime fail-closed read boundary for active tenant capability suppressions. */
@FunctionalInterface
public interface TenantCapabilityOverrideReader {

    boolean isDisabled(Long tenantId, String contractKey, Instant evaluatedAt);

    default String effectiveRevision(Long tenantId, Instant evaluatedAt) {
        return "0";
    }

    static TenantCapabilityOverrideReader none() {
        return (tenantId, contractKey, evaluatedAt) -> false;
    }
}
