package com.dwp.services.auth.tenantsettings;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
final class ProductCapabilityEntitlementAdapter implements InternalEntitlementAdapter {

    private final ProductCapabilityEntitlementRepository repository;

    ProductCapabilityEntitlementAdapter(ProductCapabilityEntitlementRepository repository) {
        this.repository = repository;
    }

    @Override
    public AdapterProjection project(Long tenantId, List<Long> userIds, Instant observedAt) {
        Instant source = repository.freshest(tenantId);
        String state = source == null ? "NO_DATA" : "OBSERVED";
        String freshness = source == null ? "NO_DATA"
                : source.isBefore(observedAt.minus(Duration.ofMinutes(5))) ? "STALE" : "FRESH";
        return new AdapterProjection(
                repository.grants(tenantId, userIds),
                List.of(
                        new TenantSettingsDtos.OwnerCoverage(
                                "AUTH_PRODUCT_AUTHORIZATION_CATALOG", state, freshness,
                                observedAt, source, List.of("VIEW_DETAIL", "OPEN_OWNER"),
                                List.of(
                                        "ROUTE_PREDICATES_EVALUATED_ONLY_AT_REQUEST_TIME",
                                        "SCOPED_CAPABILITIES_REQUIRE_RUNTIME_ROUTE_EVALUATION")),
                        new TenantSettingsDtos.OwnerCoverage(
                                "AUTH_TENANT_CAPABILITY_OVERRIDE", state, freshness,
                                observedAt, source,
                                List.of("VIEW_DETAIL", "REQUEST_RESTRICTIVE_OVERRIDE"),
                                List.of())));
    }
}
