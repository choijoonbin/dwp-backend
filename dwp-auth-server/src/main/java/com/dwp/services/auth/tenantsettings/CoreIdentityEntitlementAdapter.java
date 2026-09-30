package com.dwp.services.auth.tenantsettings;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
final class CoreIdentityEntitlementAdapter implements InternalEntitlementAdapter {

    private static final List<String> OWNERS = List.of(
            "AUTH_USER_DIRECTORY",
            "DIRECT_ROLE_ASSIGNMENTS",
            "GROUP_ROLE_ASSIGNMENTS",
            "PRIVILEGED_ACCESS_GRANTS",
            "APP_ADMIN_PRESET_ASSIGNMENTS",
            "AUTH_TENANT_APP_WORKFORCE_ASSIGNMENTS");
    private final TenantSettingsRepository repository;

    CoreIdentityEntitlementAdapter(TenantSettingsRepository repository) {
        this.repository = repository;
    }

    @Override
    public AdapterProjection project(Long tenantId, List<Long> userIds, Instant observedAt) {
        Instant source = repository.freshestProjectionSource(tenantId);
        List<TenantSettingsDtos.OwnerCoverage> owners = OWNERS.stream()
                .map(owner -> coverage(owner, source, observedAt)).toList();
        return new AdapterProjection(repository.grants(tenantId, userIds), owners);
    }

    private TenantSettingsDtos.OwnerCoverage coverage(
            String owner, Instant source, Instant observedAt) {
        return new TenantSettingsDtos.OwnerCoverage(
                owner, source == null ? "NO_DATA" : "OBSERVED",
                freshness(source, observedAt), observedAt, source,
                List.of("VIEW_DETAIL", "OPEN_OWNER"), List.of());
    }

    private String freshness(Instant source, Instant observedAt) {
        if (source == null) return "NO_DATA";
        return source.isBefore(observedAt.minus(Duration.ofMinutes(5))) ? "STALE" : "FRESH";
    }
}
