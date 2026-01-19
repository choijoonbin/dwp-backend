package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.IdentityProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Identity Provider Repository
 */
@Repository
public interface IdentityProviderRepository extends JpaRepository<IdentityProvider, Long> {
    
    /**
     * 테넌트 ID로 활성화된 Identity Provider 조회
     */
    List<IdentityProvider> findByTenantIdAndEnabledTrue(Long tenantId);
    
    /**
     * 테넌트 ID와 Provider Key로 조회
     */
    Optional<IdentityProvider> findByTenantIdAndProviderKey(Long tenantId, String providerKey);
    
    /**
     * 테넌트 ID와 Provider ID로 조회
     */
    Optional<IdentityProvider> findByTenantIdAndProviderId(Long tenantId, String providerId);
    
    /**
     * 테넌트 ID로 모든 Identity Provider 조회
     */
    List<IdentityProvider> findByTenantId(Long tenantId);
}
