package com.dwp.services.platform.workplace.connectorops;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos.*;
import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsRepository.RuntimeRow;
import static org.mockito.Mockito.*;

class WorkplaceConnectorRuntimeObservationProducerTest {
    @Test
    void pollsOnlyThroughAReadyProviderAdapterAndPersistsOwnedTruth() {
        WorkplaceConnectorOpsRepository repository = mock(WorkplaceConnectorOpsRepository.class);
        WorkplaceConnectorOpsService service = mock(WorkplaceConnectorOpsService.class);
        WorkplaceConnectorReplayAdapter adapter = mock(WorkplaceConnectorReplayAdapter.class);
        RuntimeRow configuration = mock(RuntimeRow.class);
        when(configuration.tenantId()).thenReturn(77L);
        when(configuration.kind()).thenReturn(ConnectorKind.CALENDAR);
        when(configuration.configuredProvider()).thenReturn("msgraph");
        when(configuration.configurationReference()).thenReturn("secret-manager://calendar");
        when(repository.observableConfigurations(25)).thenReturn(List.of(configuration));
        WorkplaceConnectorReplayAdapter.ProviderContext context =
                WorkplaceConnectorOpsService.providerContext(configuration);
        when(service.adapter(context)).thenReturn(adapter);
        WorkplaceConnectorReplayAdapter.RuntimeObservation observation =
                new WorkplaceConnectorReplayAdapter.RuntimeObservation(
                        ProviderReportedState.HEALTHY, List.of(Capability.HEALTH),
                        OffsetDateTime.parse("2026-09-16T03:00:00Z"), null, 0L,
                        "opaque", 0L, 0L, null, "relay", "2.0.0",
                        "sha256:1234567890abcdef");
        when(adapter.observe(context)).thenReturn(observation);

        new WorkplaceConnectorRuntimeObservationProducer(repository, service, 25)
                .observeConfiguredConnectors();

        verify(service).observePolledRuntime(configuration, observation);
    }

    @Test
    void missingApprovedAdapterFailsClosedWithoutSyntheticObservation() {
        WorkplaceConnectorOpsRepository repository = mock(WorkplaceConnectorOpsRepository.class);
        WorkplaceConnectorOpsService service = mock(WorkplaceConnectorOpsService.class);
        RuntimeRow configuration = mock(RuntimeRow.class);
        when(configuration.tenantId()).thenReturn(78L);
        when(configuration.kind()).thenReturn(ConnectorKind.CALENDAR);
        when(configuration.configuredProvider()).thenReturn("unapproved");
        when(configuration.configurationReference()).thenReturn("secret-manager://calendar");
        when(repository.observableConfigurations(25)).thenReturn(List.of(configuration));

        new WorkplaceConnectorRuntimeObservationProducer(repository, service, 25)
                .observeConfiguredConnectors();

        verify(service, never()).observePolledRuntime(any(), any());
    }

    @Test
    void providerFailureIsContainedWithoutPersistingSyntheticOrProviderSuppliedText() {
        WorkplaceConnectorOpsRepository repository = mock(WorkplaceConnectorOpsRepository.class);
        WorkplaceConnectorOpsService service = mock(WorkplaceConnectorOpsService.class);
        WorkplaceConnectorReplayAdapter adapter = mock(WorkplaceConnectorReplayAdapter.class);
        RuntimeRow configuration = mock(RuntimeRow.class);
        when(configuration.tenantId()).thenReturn(79L);
        when(configuration.kind()).thenReturn(ConnectorKind.CALENDAR);
        when(configuration.configuredProvider()).thenReturn("msgraph");
        when(configuration.configurationReference()).thenReturn("secret-manager://calendar");
        when(repository.observableConfigurations(25)).thenReturn(List.of(configuration));
        WorkplaceConnectorReplayAdapter.ProviderContext context =
                WorkplaceConnectorOpsService.providerContext(configuration);
        when(service.adapter(context)).thenReturn(adapter);
        when(adapter.observe(context)).thenThrow(new IllegalStateException(
                "person@example.invalid bearer-secret-from-provider"));

        new WorkplaceConnectorRuntimeObservationProducer(repository, service, 25)
                .observeConfiguredConnectors();

        verify(service, never()).observePolledRuntime(any(), any());
    }
}
