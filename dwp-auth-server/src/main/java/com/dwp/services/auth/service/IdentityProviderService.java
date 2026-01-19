package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.IdentityProviderResponse;
import com.dwp.services.auth.entity.IdentityProvider;
import com.dwp.services.auth.repository.IdentityProviderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Identity Provider 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class IdentityProviderService {
    
    private final IdentityProviderRepository identityProviderRepository;
    
    /**
     * 테넌트별 활성화된 Identity Provider 조회
     * 
     * @param tenantId 테넌트 ID
     * @return 활성화된 Identity Provider 목록 (없으면 빈 목록)
     */
    @Transactional(readOnly = true)
    public List<IdentityProviderResponse> getIdentityProviders(Long tenantId) {
        List<IdentityProvider> providers = identityProviderRepository.findByTenantIdAndEnabledTrue(tenantId);
        
        return providers.stream()
                .map(this::toIdentityProviderResponse)
                .collect(Collectors.toList());
    }
    
    /**
     * 테넌트별 특정 Provider Key의 Identity Provider 조회
     * 
     * @param tenantId 테넌트 ID
     * @param providerKey Provider Key (예: AZURE_AD)
     * @return Identity Provider (없으면 null)
     */
    @Transactional(readOnly = true)
    public IdentityProviderResponse getIdentityProviderByKey(Long tenantId, String providerKey) {
        IdentityProvider provider = identityProviderRepository.findByTenantIdAndProviderKey(tenantId, providerKey)
                .orElse(null);
        
        if (provider == null || !provider.getEnabled()) {
            return null;
        }
        
        return toIdentityProviderResponse(provider);
    }
    
    /**
     * IdentityProvider를 IdentityProviderResponse로 변환
     */
    private IdentityProviderResponse toIdentityProviderResponse(IdentityProvider provider) {
        // ext 필드를 맵으로 변환
        Map<String, Object> ext = new HashMap<>();
        if (provider.getExt1() != null) {
            ext.put("ext1", provider.getExt1());
        }
        if (provider.getExt2() != null) {
            ext.put("ext2", provider.getExt2());
        }
        if (provider.getExt3() != null) {
            ext.put("ext3", provider.getExt3());
        }
        
        return IdentityProviderResponse.builder()
                .tenantId(provider.getTenantId())
                .enabled(provider.getEnabled())
                .providerType(provider.getProviderType())
                .providerKey(provider.getProviderKey())
                .authUrl(provider.getAuthUrl())
                .metadataUrl(provider.getMetadataUrl())
                .clientId(provider.getClientId())
                .ext(ext.isEmpty() ? null : ext)
                .build();
    }
}
