package com.dwp.services.provider.support;

import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.dwp.services.provider.tenant.ProviderTenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderSupportLedgerServiceTest {

    private final ProviderTenantRepository tenantRepository = mock(ProviderTenantRepository.class);
    private final ProviderSupportLedgerRepository ledgerRepository =
            mock(ProviderSupportLedgerRepository.class);
    private final ProviderSupportSessionLifecycleService lifecycleService =
            mock(ProviderSupportSessionLifecycleService.class);
    private final ProviderSupportLedgerService service = new ProviderSupportLedgerService(
            tenantRepository, ledgerRepository, lifecycleService);

    @AfterEach
    void clearContext() {
        ProviderRequestContext.clear();
    }

    @Test
    void estateReadAloneCannotLoadThePrivilegedLedger() {
        setActor(Set.of("PROVIDER_TENANT_PROVISIONER"), Set.of("ESTATE_READ"));

        assertThatThrownBy(() -> service.accessRequests(null))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("SUPPORT_ACCESS_READ");
        assertThatThrownBy(() -> service.sessions(null))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("SUPPORT_ACCESS_READ");

        verify(lifecycleService, never()).expireElapsedSessions();
        verify(ledgerRepository, never()).accessRequests(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(ledgerRepository, never()).sessions(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dedicatedReadAuthorityExpiresFirstThenQueriesTheActorScopedProjection() {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        setActor(
                Set.of("PROVIDER_SUPPORT"),
                Set.of("ESTATE_READ", "SUPPORT_ACCESS_READ", "SUPPORT_SESSION_WRITE"));
        when(tenantRepository.existsById(tenantId)).thenReturn(true);
        when(ledgerRepository.accessRequests(
                org.mockito.ArgumentMatchers.eq(tenantId),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        assertThat(service.accessRequests(tenantId)).isEmpty();

        verify(lifecycleService).expireElapsedSessions();
        verify(ledgerRepository).accessRequests(
                org.mockito.ArgumentMatchers.eq(tenantId),
                org.mockito.ArgumentMatchers.argThat(actor ->
                        actor.operatorId() == 71L
                                && actor.permissions().contains("SUPPORT_ACCESS_READ")));
    }

    private void setActor(Set<String> roles, Set<String> permissions) {
        ProviderRequestContext.set(new ProviderRequestContext.Actor(
                71L,
                7001L,
                1L,
                "Scoped provider operator",
                roles,
                permissions,
                UUID.fromString("71000000-0000-0000-0000-000000000001")));
    }
}
