package com.dwp.services.provider.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface ProviderTenantRepository
        extends JpaRepository<ProviderTenant, UUID>, JpaSpecificationExecutor<ProviderTenant> {

    Optional<ProviderTenant> findByTenantKey(String tenantKey);
}
