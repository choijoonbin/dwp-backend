package com.dwp.services.platform.workplace.connectorops;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos.Capability;
import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos.ConnectorKind;
import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos.ProviderReportedState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BundledVerificationPresencePublisherTest {

    @Test
    void springSelectsTheJdbcTemplateConstructorForTheBundledAdapter() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.register(BundledVerificationPresenceAdapter.class);
            context.refresh();

            assertThat(context.getBean(BundledVerificationPresenceAdapter.class)).isNotNull();
        }
    }

    @Test
    void publishesOnlyTheReadyBundledPresenceConfiguration() {
        WorkplaceConnectorOpsRepository repository = mock(WorkplaceConnectorOpsRepository.class);
        WorkplaceConnectorOpsService service = mock(WorkplaceConnectorOpsService.class);
        BundledVerificationPresenceAdapter adapter = mock(BundledVerificationPresenceAdapter.class);
        WorkplaceConnectorOpsRepository.RuntimeRow bundled = runtime(
                1, ConnectorKind.ACTUAL_PRESENCE,
                BundledVerificationPresenceAdapter.PROVIDER);
        WorkplaceConnectorReplayAdapter.ProviderContext context =
                WorkplaceConnectorOpsService.providerContext(bundled);
        WorkplaceConnectorReplayAdapter.RuntimeObservation observation = observation();
        when(repository.observableConfigurationForIdentity(
                "default",
                BundledVerificationPresenceAdapter.VERIFICATION_USER_ID,
                UUID.fromString(BundledVerificationPresenceAdapter.VERIFICATION_PERSON_ID),
                ConnectorKind.ACTUAL_PRESENCE,
                BundledVerificationPresenceAdapter.PROVIDER,
                BundledVerificationPresenceAdapter.CONFIGURATION_REFERENCE))
                .thenReturn(Optional.of(bundled));
        when(adapter.ready(context)).thenReturn(true);
        when(adapter.observe(context)).thenReturn(observation);

        new BundledVerificationPresencePublisher(repository, service, adapter).publish();

        verify(service).observePolledRuntime(bundled, observation);
        verify(repository, never()).observableConfigurations(100);
    }

    private WorkplaceConnectorOpsRepository.RuntimeRow runtime(
            long tenantId,
            ConnectorKind kind,
            String provider) {
        WorkplaceConnectorOpsRepository.RuntimeRow row =
                mock(WorkplaceConnectorOpsRepository.RuntimeRow.class);
        when(row.tenantId()).thenReturn(tenantId);
        when(row.kind()).thenReturn(kind);
        when(row.configuredProvider()).thenReturn(provider);
        when(row.configurationReference()).thenReturn(
                BundledVerificationPresenceAdapter.CONFIGURATION_REFERENCE);
        return row;
    }

    private WorkplaceConnectorReplayAdapter.RuntimeObservation observation() {
        OffsetDateTime observedAt = OffsetDateTime.parse("2026-10-27T07:20:30Z");
        return new WorkplaceConnectorReplayAdapter.RuntimeObservation(
                ProviderReportedState.HEALTHY,
                List.of(Capability.HEALTH, Capability.CHECKPOINT),
                observedAt,
                observedAt,
                0L,
                "local-verification-presence:29884760",
                0L,
                0L,
                null,
                "dwp-bundled-verification-presence",
                "1",
                "skax_presence_29884760");
    }
}
