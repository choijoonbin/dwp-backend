package com.dwp.services.platform.workplace.safetyoperations;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyIncidentRepository.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SafetyConnectorRuntimeObservationProducerTest {
    private static final String REFERENCE =
            "secret-manager://workplace/safety-runtime/v4";

    @Test
    void providerOwnedRuntimeEvidenceIsPersistedWithObservedConfigurationVersion() {
        SafetyIncidentRepository repository = mock(SafetyIncidentRepository.class);
        SafetyConnectorService connectors = mock(SafetyConnectorService.class);
        SafetyHttpDispatchProvider provider = mock(SafetyHttpDispatchProvider.class);
        SafetyProviderRelayBindings bindings = new SafetyProviderRelayBindings(
                "RUNTIME_EBS@4=" + REFERENCE, "");
        ObservableConnectorRow row = new ObservableConnectorRow(
                71, ConnectorKind.EBS, "RUNTIME_EBS", 4);
        when(repository.observableConnectors(25)).thenReturn(List.of(row));
        when(provider.ready(any())).thenReturn(true);
        when(provider.observe(eq(71L), eq(ConnectorKind.EBS), any())).thenReturn(
                new SafetyHttpDispatchProvider.RuntimeObservation(
                        ProviderReportedState.READY, "evidence:runtime-ebs",
                        OffsetDateTime.parse("2026-09-17T02:00:00Z"),
                        OffsetDateTime.parse("2026-09-17T01:59:00Z"), null));

        new SafetyConnectorRuntimeObservationProducer(repository, connectors, provider,
                bindings, 25).observeConfiguredConnectors();

        verify(connectors).observe(argThat(observation ->
                observation.tenantId() == 71
                        && observation.kind() == ConnectorKind.EBS
                        && observation.providerCode().equals("RUNTIME_EBS")
                        && observation.observedConfigurationVersion() == 4
                        && observation.reportedState() == ProviderReportedState.READY
                        && observation.evidenceReference().equals("evidence:runtime-ebs")));
    }

    @Test
    void unboundConnectorFailsClosedWithoutCallingProvider() {
        SafetyIncidentRepository repository = mock(SafetyIncidentRepository.class);
        SafetyConnectorService connectors = mock(SafetyConnectorService.class);
        SafetyHttpDispatchProvider provider = mock(SafetyHttpDispatchProvider.class);
        when(repository.observableConnectors(25)).thenReturn(List.of(
                new ObservableConnectorRow(72, ConnectorKind.BLE_MESH, "UNBOUND", 9)));

        new SafetyConnectorRuntimeObservationProducer(repository, connectors, provider,
                new SafetyProviderRelayBindings("", ""), 25)
                .observeConfiguredConnectors();

        verifyNoInteractions(connectors, provider);
    }
}
