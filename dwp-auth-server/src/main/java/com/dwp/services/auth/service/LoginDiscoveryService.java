package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.LoginOptionsResponse;
import com.dwp.services.auth.entity.AuthPolicy;
import com.dwp.services.auth.entity.IdentityProvider;
import com.dwp.services.auth.repository.AuthPolicyRepository;
import com.dwp.services.auth.repository.IdentityProviderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Produces the deliberately small pre-authentication login discovery contract.
 *
 * <p>The public response never contains a tenant identifier or an IdP key. The selected IdP key
 * remains server-side and is resolved again when the browser starts the OIDC redirect.</p>
 */
@Service
public class LoginDiscoveryService {

    static final String LOCAL = "LOCAL";
    static final String SSO = "SSO";
    static final String NONE = "NONE";

    private final AuthPolicyRepository authPolicyRepository;
    private final IdentityProviderRepository identityProviderRepository;

    public LoginDiscoveryService(
            AuthPolicyRepository authPolicyRepository,
            IdentityProviderRepository identityProviderRepository) {
        this.authPolicyRepository = authPolicyRepository;
        this.identityProviderRepository = identityProviderRepository;
    }

    @Transactional(readOnly = true)
    public LoginOptionsResponse getLoginOptions(Long tenantId) {
        ResolvedLoginOptions options = resolve(tenantId);
        return LoginOptionsResponse.builder()
                .localLoginAvailable(options.localLoginAvailable())
                .ssoLoginAvailable(options.ssoLoginAvailable())
                .preferredLoginType(options.preferredLoginType())
                .build();
    }

    @Transactional(readOnly = true)
    public String requireSsoProviderKey(Long tenantId) {
        return resolve(tenantId).providerKey()
                .orElseThrow(() -> new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS));
    }

    private ResolvedLoginOptions resolve(Long tenantId) {
        if (tenantId == null) return ResolvedLoginOptions.closed();
        Optional<AuthPolicy> storedPolicy = authPolicyRepository.findByTenantId(tenantId);
        if (storedPolicy.isEmpty()) return ResolvedLoginOptions.closed();

        AuthPolicy policy = storedPolicy.get();
        List<String> allowedLoginTypes = authPolicyRepository.findAllowedLoginTypes(tenantId);
        if (allowedLoginTypes.isEmpty()) {
            allowedLoginTypes = legacyLoginTypes(policy.getAllowedLoginTypes());
        }
        boolean localAvailable = Boolean.TRUE.equals(policy.getLocalLoginEnabled())
                && allowedLoginTypes.contains(LOCAL);
        Optional<String> providerKey = resolveProviderKey(policy, allowedLoginTypes);
        boolean ssoAvailable = providerKey.isPresent();
        String preferred = preferredLoginType(
                policy.getDefaultLoginType(), localAvailable, ssoAvailable);
        return new ResolvedLoginOptions(
                localAvailable, ssoAvailable, preferred, providerKey);
    }

    private Optional<String> resolveProviderKey(
            AuthPolicy policy,
            List<String> allowedLoginTypes) {
        if (!Boolean.TRUE.equals(policy.getSsoLoginEnabled())
                || !allowedLoginTypes.contains(SSO)) {
            return Optional.empty();
        }
        List<IdentityProvider> enabledProviders = identityProviderRepository
                .findByTenantIdAndEnabledTrueOrderByProviderKey(policy.getTenantId())
                .stream()
                .filter(provider -> "OIDC".equalsIgnoreCase(provider.getProviderType()))
                .toList();
        String configuredKey = trimToNull(policy.getSsoProviderKey());
        if (configuredKey != null) {
            return enabledProviders.stream()
                    .map(IdentityProvider::getProviderKey)
                    .filter(configuredKey::equals)
                    .findFirst();
        }
        return enabledProviders.stream()
                .map(IdentityProvider::getProviderKey)
                .filter(key -> key != null && !key.isBlank())
                .findFirst();
    }

    private List<String> legacyLoginTypes(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .map(item -> item.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private String preferredLoginType(
            String configuredDefault,
            boolean localAvailable,
            boolean ssoAvailable) {
        if (SSO.equalsIgnoreCase(configuredDefault) && ssoAvailable) return SSO;
        if (LOCAL.equalsIgnoreCase(configuredDefault) && localAvailable) return LOCAL;
        if (localAvailable) return LOCAL;
        if (ssoAvailable) return SSO;
        return NONE;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private record ResolvedLoginOptions(
            boolean localLoginAvailable,
            boolean ssoLoginAvailable,
            String preferredLoginType,
            Optional<String> providerKey) {

        private static ResolvedLoginOptions closed() {
            return new ResolvedLoginOptions(false, false, NONE, Optional.empty());
        }
    }
}
