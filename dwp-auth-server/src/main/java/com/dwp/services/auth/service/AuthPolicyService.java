package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.AuthPolicyResponse;
import com.dwp.services.auth.entity.AuthPolicy;
import com.dwp.services.auth.repository.AuthPolicyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@Service
public class AuthPolicyService {

    private final AuthPolicyRepository authPolicyRepository;

    public AuthPolicyService(AuthPolicyRepository authPolicyRepository) {
        this.authPolicyRepository = authPolicyRepository;
    }

    @Transactional(readOnly = true)
    public AuthPolicyResponse getPolicy(Long tenantId) {
        AuthPolicy policy = authPolicyRepository.findByTenantId(tenantId)
                .orElseGet(() -> AuthPolicy.builder().tenantId(tenantId).build());

        List<String> allowedLoginTypes = authPolicyRepository.findAllowedLoginTypes(tenantId);
        if (allowedLoginTypes.isEmpty()) {
            allowedLoginTypes = Arrays.stream(policy.getAllowedLoginTypes().split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .toList();
        }

        return AuthPolicyResponse.builder()
                .tenantId(tenantId)
                .defaultLoginType(policy.getDefaultLoginType())
                .allowedLoginTypes(allowedLoginTypes)
                .localLoginEnabled(policy.getLocalLoginEnabled())
                .ssoLoginEnabled(policy.getSsoLoginEnabled())
                .ssoProviderKey(policy.getSsoProviderKey())
                .requireMfa(policy.getRequireMfa())
                .build();
    }
}
