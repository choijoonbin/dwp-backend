package com.dwp.services.platform.productivity;

import com.dwp.services.platform.audit.PlatformAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.productivity.ProductivityTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductivityServiceTest {

    private ProductivityRepository repository;
    private ProductivityCrypto crypto;
    private ProductivityCredentialResolver credentials;
    private ProductivityService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProductivityRepository.class);
        crypto = mock(ProductivityCrypto.class);
        credentials = mock(ProductivityCredentialResolver.class);
        service = new ProductivityService(
                repository,
                crypto,
                credentials,
                mock(MicrosoftGraphClient.class),
                mock(PlatformAuditService.class),
                10);
        when(crypto.available()).thenReturn(true);
        when(credentials.validReference(anyString())).thenReturn(true);
        when(credentials.resolve(anyString())).thenReturn(Optional.of("secret"));
    }

    @Test
    void configurationCheckRequiresPolicyAndLeastPrivilegeScopes() {
        UUID connectorId = UUID.randomUUID();
        ProductivityRepository.ConnectorRecord connector = connector(
                connectorId,
                PolicyState.REVIEW_REQUIRED,
                List.of("openid", "offline_access", "User.Read", "Mail.Read", "Calendars.Read"));
        when(repository.connector(1L, connectorId)).thenReturn(Optional.of(connector));

        ProductivityDtos.ConfigurationCheck result = service.checkConfiguration(1L, connectorId);

        assertThat(result.ready()).isFalse();
        assertThat(result.blockingCodes())
                .contains("REQUIRED_DELEGATED_SCOPES", "LEAST_PRIVILEGE_SCOPE_POLICY",
                        "TENANT_POLICY_APPROVAL");
        verify(repository).configurationResult(
                eq(1L), eq(connectorId), eq(ConnectorHealth.CONFIGURATION_REQUIRED),
                anyString(), any());
    }

    @Test
    void configurationCheckIsReadyWithoutClaimingSyncHealth() {
        UUID connectorId = UUID.randomUUID();
        ProductivityRepository.ConnectorRecord connector = connector(
                connectorId,
                PolicyState.APPROVED,
                List.of("openid", "profile", "offline_access", "User.Read",
                        "Mail.ReadBasic", "Calendars.Read"));
        when(repository.connector(1L, connectorId)).thenReturn(Optional.of(connector));

        ProductivityDtos.ConfigurationCheck result = service.checkConfiguration(1L, connectorId);

        assertThat(result.ready()).isTrue();
        assertThat(result.healthState()).isEqualTo(ConnectorHealth.DEGRADED);
        verify(repository).configurationResult(
                eq(1L), eq(connectorId), eq(ConnectorHealth.DEGRADED),
                eq("AWAITING_FIRST_SUCCESSFUL_SYNC"), any());
    }

    private ProductivityRepository.ConnectorRecord connector(
            UUID connectorId,
            PolicyState policyState,
            List<String> scopes) {
        return new ProductivityRepository.ConnectorRecord(
                connectorId, 1L, "MICROSOFT_365", "Microsoft 365",
                ProviderType.MICROSOFT_GRAPH, AuthMode.DELEGATED,
                "organizations", "11111111-1111-1111-1111-111111111111",
                "env:DWP_MS_GRAPH_CLIENT_SECRET", "https://localhost/callback",
                scopes, List.of("DELTA_SYNC"), ConnectorLifecycle.DRAFT,
                ConnectorHealth.CONFIGURATION_REQUIRED, policyState,
                null, null, null, 0, 0);
    }
}
