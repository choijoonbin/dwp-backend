package com.dwp.services.people.integration;

import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.HrDomainFoundationService;
import com.dwp.services.people.security.PeopleRequestContext;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.time.OffsetDateTime;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HrisImportServiceConnectorTest {

    private static final Long TENANT_ID = 3L;
    private static final Long ACTOR_ID = 9L;

    private final HrisIntegrationRepository repository = mock(HrisIntegrationRepository.class);
    private final HrisImportService service = new HrisImportService(
            repository,
            mock(WorkdayReferenceMapper.class),
            new ObjectMapper(),
            mock(HrDomainFoundationService.class),
            true);

    @BeforeEach
    void setContext() {
        PeopleRequestContext.set(ACTOR_ID, TENANT_ID, Set.of("HR_ADMIN"));
    }

    @AfterEach
    void clearContext() {
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "clear");
        PeopleRequestContext.clear();
    }

    @Test
    void rejectsRawCredentialBeforePersistingConnector() {
        HrisDtos.CreateConnectorRequest request = request("raw-client-secret");

        assertThatThrownBy(() -> service.createConnector(request, "corr-1"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("secret reference");

        verify(repository, never()).upsertSource(any(), any(), any(), any(), any());
        verify(repository, never()).createConnector(any(), any(), anyLong(), any());
    }

    @Test
    void storesOnlySecretReferenceAndRedactsAuditSnapshot() {
        UUID connectorId = UUID.randomUUID();
        HrisDtos.CreateConnectorRequest request = request("vault://tenant/hris/workday");
        HrisDtos.ConnectorInstance connector = new HrisDtos.ConnectorInstance(
                connectorId, 17L, "workday-primary", "workday-rest", "WORKDAY_REST",
                "https://example.workday.com/api", "OAUTH2_CLIENT_CREDENTIALS",
                "vault://tenant/hris/workday", "0 */15 * * * *", "DRAFT", "UNKNOWN",
                null, null, null, null, 0, 0L);
        when(repository.upsertSource(
                TENANT_ID, ACTOR_ID, "workday-primary", "WORKDAY", "Primary Workday"))
                .thenReturn(17L);
        when(repository.createConnector(TENANT_ID, ACTOR_ID, 17L, request))
                .thenReturn(connector);

        HrisDtos.ConnectorInstance result = service.createConnector(request, "corr-2");

        assertThat(result.connectorInstanceId()).isEqualTo(connectorId);
        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        verify(repository).auditConnector(
                eq(TENANT_ID), eq(ACTOR_ID), eq(connectorId),
                eq("people.hris-connector.created"), eq("corr-2"), snapshot.capture());
        assertThat(snapshot.getValue()).contains("vault://***");
        assertThat(snapshot.getValue()).doesNotContain("tenant/hris/workday");
    }

    @Test
    void rejectsConnectorChangesFromTenantAdministration() {
        PeopleRequestContext.set(ACTOR_ID, TENANT_ID, Set.of("TENANT_ADMIN"));

        assertThatThrownBy(() -> service.createConnector(
                request("vault://tenant/hris/workday"), "corr-3"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("HR administrator");

        verify(repository, never()).upsertSource(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsSyntheticImportWhenDevelopmentGateIsDisabled() {
        HrisImportService disabledService = new HrisImportService(
                repository,
                mock(WorkdayReferenceMapper.class),
                new ObjectMapper(),
                mock(HrDomainFoundationService.class),
                false);

        assertThatThrownBy(() -> disabledService.importSyntheticWorkdayFixture(null, null))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void exactAppConfigAuthorityDoesNotRequireAGlobalAdminRole() {
        PeopleRequestContext.set(ACTOR_ID, TENANT_ID, Set.of(),
                Set.of("ACTION.WORKFORCE_DATA_OPERATIONS:CONFIGURE"));
        setExactIntegrationContext();
        HrisDtos.CreateConnectorRequest request = new HrisDtos.CreateConnectorRequest(
                "workday-primary", "WORKDAY", "Primary Workday", "workday-rest",
                "WORKDAY_REST", "https://example.workday.com/api", "NONE", null, null);
        UUID connectorId = UUID.randomUUID();
        HrisDtos.ConnectorInstance connector = new HrisDtos.ConnectorInstance(
                connectorId, 17L, "workday-primary", "workday-rest", "WORKDAY_REST",
                "https://example.workday.com/api", "NONE", null, null,
                "DRAFT", "UNKNOWN", null, null, null, null, 0, 0L);
        when(repository.upsertSource(
                TENANT_ID, ACTOR_ID, "workday-primary", "WORKDAY", "Primary Workday"))
                .thenReturn(17L);
        when(repository.createConnector(TENANT_ID, ACTOR_ID, 17L, request))
                .thenReturn(connector);

        assertThat(service.createConnector(request, "corr-exact").connectorInstanceId())
                .isEqualTo(connectorId);
    }

    @Test
    void governedIntegrationPredicateRejectsCredentialReferenceBeforeMutation() {
        PeopleRequestContext.set(ACTOR_ID, TENANT_ID, Set.of(),
                Set.of("ACTION.WORKFORCE_DATA_OPERATIONS:CONFIGURE"));
        setExactIntegrationContext();

        assertThatThrownBy(() -> service.createConnector(
                request("vault://tenant/hris/workday"), "corr-secret"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("credentialReference is forbidden");
        verify(repository, never()).upsertSource(any(), any(), any(), any(), any());
    }

    private void setExactIntegrationContext() {
        String route = "route.hcm.management.integration-config.action";
        HcmV3PepRegistry.RouteAuthority authority = new HcmV3PepRegistry.RouteAuthority(
                route, "ACTION", "full-management", false,
                Set.of("predicate.hcm-integration-nonsecret-update.v1"),
                Set.of("RESOURCE_SET"), route + ".binding.01",
                "hcm.integration.configure", null, "POST", "/api/people/hris", null);
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "set",
                new HcmPepContext.Evidence(
                        authority, "psr-" + "a".repeat(64),
                        OffsetDateTime.parse("2099-01-01T00:00:00Z"),
                        "hcm.management", "scope-config", "110"));
    }

    private HrisDtos.CreateConnectorRequest request(String credentialReference) {
        return new HrisDtos.CreateConnectorRequest(
                "workday-primary", "WORKDAY", "Primary Workday",
                "workday-rest", "WORKDAY_REST", "https://example.workday.com/api",
                "OAUTH2_CLIENT_CREDENTIALS", credentialReference, "0 */15 * * * *");
    }
}
