package com.dwp.services.auth.service;

import com.dwp.services.auth.entity.AuthPolicy;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.entity.UserAccount;
import com.dwp.services.auth.repository.AuthPolicyRepository;
import com.dwp.services.auth.repository.AuthSessionRepository;
import com.dwp.services.auth.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdentityAccountServiceTest {

    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final AuthPolicyRepository policies = mock(AuthPolicyRepository.class);
    private final AuthSessionRepository sessions = mock(AuthSessionRepository.class);
    private final IdentityAccountService service = new IdentityAccountService(accounts, policies, sessions);

    @Test
    void createsInvitedLocalAccountFromManagedWorkEmail() {
        User user = User.builder()
                .userId(10L)
                .tenantId(1L)
                .email("Employee@Example.COM")
                .status("ACTIVE")
                .build();
        when(policies.findByTenantId(1L)).thenReturn(Optional.of(AuthPolicy.builder()
                .tenantId(1L)
                .allowedLoginTypes("SSO,LOCAL")
                .localLoginEnabled(true)
                .build()));
        when(policies.findAllowedLoginTypes(1L)).thenReturn(List.of("LOCAL", "SSO"));
        when(accounts.findByTenantIdAndUserIdAndProviderTypeAndProviderId(
                1L, 10L, "LOCAL", "local")).thenReturn(Optional.empty());
        when(accounts.saveAndFlush(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.synchronizeManagedUser(user);

        var captor = org.mockito.ArgumentCaptor.forClass(UserAccount.class);
        verify(accounts).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPrincipal()).isEqualTo("employee@example.com");
        assertThat(captor.getValue().getStatus()).isEqualTo("INVITED");
    }

    @Test
    void normalizedPolicyCanDisableLegacyLocalAccountProvisioning() {
        User user = User.builder()
                .userId(10L)
                .tenantId(1L)
                .email("employee@example.com")
                .status("ACTIVE")
                .build();
        when(policies.findByTenantId(1L)).thenReturn(Optional.of(AuthPolicy.builder()
                .tenantId(1L)
                .allowedLoginTypes("LOCAL,SSO")
                .localLoginEnabled(true)
                .build()));
        when(policies.findAllowedLoginTypes(1L)).thenReturn(List.of("SSO"));

        service.synchronizeManagedUser(user);

        verify(accounts, never()).saveAndFlush(any(UserAccount.class));
    }

    @Test
    void oidcAccountUsesIssuerAndSubjectWithoutPassword() {
        User user = User.builder().userId(10L).tenantId(1L).status("ACTIVE").build();
        when(accounts.findByTenantIdAndProviderTypeAndIssuerUriAndPrincipal(
                1L, "OIDC", "https://issuer.example.com", "subject-1"))
                .thenReturn(Optional.empty());
        when(accounts.findByTenantIdAndUserIdAndProviderTypeAndProviderId(
                1L, 10L, "OIDC", "entra")).thenReturn(Optional.empty());
        when(accounts.saveAndFlush(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserAccount account = service.linkOidcAccount(
                user, "entra", "https://issuer.example.com", "subject-1");

        assertThat(account.getIssuerUri()).isEqualTo("https://issuer.example.com");
        assertThat(account.getPrincipal()).isEqualTo("subject-1");
        assertThat(account.getPasswordHash()).isNull();
    }
}
