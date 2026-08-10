package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.AuthPolicyResponse;
import com.dwp.services.auth.entity.AuthPolicy;
import com.dwp.services.auth.repository.AuthPolicyRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthPolicyServiceTest {

    @Test
    void returnsLocalLoginPolicyWhenTenantHasNoOverride() {
        AuthPolicyRepository repository = mock(AuthPolicyRepository.class);
        when(repository.findByTenantId(7L)).thenReturn(Optional.empty());

        AuthPolicyResponse response = new AuthPolicyService(repository).getPolicy(7L);

        assertThat(response.getTenantId()).isEqualTo(7L);
        assertThat(response.getAllowedLoginTypes()).containsExactly("LOCAL");
        assertThat(response.getLocalLoginEnabled()).isTrue();
        assertThat(response.getSsoLoginEnabled()).isFalse();
    }

    @Test
    void normalizedLoginTypesTakePrecedenceOverLegacyCsv() {
        AuthPolicyRepository repository = mock(AuthPolicyRepository.class);
        when(repository.findByTenantId(7L)).thenReturn(Optional.of(AuthPolicy.builder()
                .tenantId(7L)
                .allowedLoginTypes("LOCAL")
                .defaultLoginType("SSO")
                .ssoLoginEnabled(true)
                .build()));
        when(repository.findAllowedLoginTypes(7L)).thenReturn(List.of("SSO"));

        AuthPolicyResponse response = new AuthPolicyService(repository).getPolicy(7L);

        assertThat(response.getAllowedLoginTypes()).containsExactly("SSO");
        assertThat(response.getDefaultLoginType()).isEqualTo("SSO");
    }
}
