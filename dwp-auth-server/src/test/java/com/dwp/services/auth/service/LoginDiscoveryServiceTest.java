package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.LoginOptionsResponse;
import com.dwp.services.auth.entity.AuthPolicy;
import com.dwp.services.auth.entity.IdentityProvider;
import com.dwp.services.auth.repository.AuthPolicyRepository;
import com.dwp.services.auth.repository.IdentityProviderRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginDiscoveryServiceTest {

    private final AuthPolicyRepository policies = mock(AuthPolicyRepository.class);
    private final IdentityProviderRepository providers = mock(IdentityProviderRepository.class);
    private final LoginDiscoveryService service = new LoginDiscoveryService(policies, providers);

    @Test
    void unknownTenantReturnsTheSameFailClosedProjectionWithoutSynthesizingLocalLogin() {
        when(policies.findByTenantId(404L)).thenReturn(Optional.empty());

        LoginOptionsResponse response = service.getLoginOptions(404L);

        assertThat(response.isLocalLoginAvailable()).isFalse();
        assertThat(response.isSsoLoginAvailable()).isFalse();
        assertThat(response.getPreferredLoginType()).isEqualTo("NONE");
        verify(policies, never()).findAllowedLoginTypes(404L);
        verify(providers, never()).findByTenantIdAndEnabledTrueOrderByProviderKey(404L);
    }

    @Test
    void exposesOnlyLoginAffordancesAndKeepsTheSelectedProviderKeyServerSide() {
        when(policies.findByTenantId(7L)).thenReturn(Optional.of(AuthPolicy.builder()
                .tenantId(7L)
                .allowedLoginTypes("LOCAL,SSO")
                .localLoginEnabled(true)
                .ssoLoginEnabled(true)
                .ssoProviderKey("workforce-oidc")
                .defaultLoginType("SSO")
                .requireMfa(true)
                .build()));
        when(policies.findAllowedLoginTypes(7L)).thenReturn(List.of("LOCAL", "SSO"));
        when(providers.findByTenantIdAndEnabledTrueOrderByProviderKey(7L)).thenReturn(List.of(
                IdentityProvider.builder()
                        .tenantId(7L)
                        .providerType("OIDC")
                        .providerKey("workforce-oidc")
                        .issuerUri("https://identity.example.test")
                        .clientId("sensitive-client")
                        .enabled(true)
                        .build()));

        LoginOptionsResponse response = service.getLoginOptions(7L);

        assertThat(response.isLocalLoginAvailable()).isTrue();
        assertThat(response.isSsoLoginAvailable()).isTrue();
        assertThat(response.getPreferredLoginType()).isEqualTo("SSO");
        assertThat(service.requireSsoProviderKey(7L)).isEqualTo("workforce-oidc");
    }

    @Test
    void missingConfiguredProviderKeepsSsoClosedAndUsesOneGenericCredentialError() {
        when(policies.findByTenantId(7L)).thenReturn(Optional.of(AuthPolicy.builder()
                .tenantId(7L)
                .allowedLoginTypes("SSO")
                .localLoginEnabled(false)
                .ssoLoginEnabled(true)
                .ssoProviderKey("missing-provider")
                .defaultLoginType("SSO")
                .build()));
        when(policies.findAllowedLoginTypes(7L)).thenReturn(List.of("SSO"));
        when(providers.findByTenantIdAndEnabledTrueOrderByProviderKey(7L)).thenReturn(List.of());

        assertThat(service.getLoginOptions(7L).isSsoLoginAvailable()).isFalse();
        assertThatThrownBy(() -> service.requireSsoProviderKey(7L))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS));
    }
}
