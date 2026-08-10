package com.dwp.services.provider.entitlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantEntitlementRepository extends JpaRepository<TenantEntitlement, Long> {

    List<TenantEntitlement> findByProviderTenantIdOrderByTenantEntitlementIdAsc(UUID tenantId);

    Optional<TenantEntitlement> findByProviderTenantIdAndEntitlementId(
            UUID tenantId, Long entitlementId);
}
