package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.IdentityProviderResponse;
import com.dwp.services.auth.entity.IdentityProvider;
import com.dwp.services.auth.repository.IdentityProviderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class IdentityProviderService {

    private final IdentityProviderRepository identityProviderRepository;

    public IdentityProviderService(IdentityProviderRepository identityProviderRepository) {
        this.identityProviderRepository = identityProviderRepository;
    }

    @Transactional(readOnly = true)
    public List<IdentityProviderResponse> getEnabledProviders(Long tenantId) {
        return identityProviderRepository.findByTenantIdAndEnabledTrueOrderByProviderKey(tenantId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private IdentityProviderResponse toResponse(IdentityProvider provider) {
        return IdentityProviderResponse.builder()
                .enabled(provider.getEnabled())
                .providerType(provider.getProviderType())
                .providerKey(provider.getProviderKey())
                .build();
    }
}
