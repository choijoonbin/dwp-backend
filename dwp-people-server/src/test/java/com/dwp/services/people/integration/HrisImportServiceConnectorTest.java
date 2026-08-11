package com.dwp.services.people.integration;

import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;
import java.util.UUID;

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
            true);

    @BeforeEach
    void setContext() {
        PeopleRequestContext.set(ACTOR_ID, TENANT_ID, Set.of("HR_ADMIN"));
    }

    @AfterEach
    void clearContext() {
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
                null, null, 0L);
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
                false);

        assertThatThrownBy(() -> disabledService.importSyntheticWorkdayFixture(null, null))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("disabled");
    }

    private HrisDtos.CreateConnectorRequest request(String credentialReference) {
        return new HrisDtos.CreateConnectorRequest(
                "workday-primary", "WORKDAY", "Primary Workday",
                "workday-rest", "WORKDAY_REST", "https://example.workday.com/api",
                "OAUTH2_CLIENT_CREDENTIALS", credentialReference, "0 */15 * * * *");
    }
}
