package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.AuthPolicyResponse;
import com.dwp.services.auth.repository.AuthPolicyRepository;
import org.junit.jupiter.api.Test;

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
}
