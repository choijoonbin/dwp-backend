package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.IdentityProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IdentityProviderRepository extends JpaRepository<IdentityProvider, Long> {

    List<IdentityProvider> findByTenantIdAndEnabledTrueOrderByProviderKey(Long tenantId);

    Optional<IdentityProvider> findByTenantIdAndProviderKey(Long tenantId, String providerKey);
}
