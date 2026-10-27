package com.dwp.services.platform.workplace.connectorops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos.ConnectorKind;

/** Keeps only the bundled verification source fresh; no customer connector is synthesized. */
@Component
@ConditionalOnProperty(
        name = "dwp.workplace.connector-runtime.bundled-verification-presence-enabled",
        havingValue = "true",
        matchIfMissing = true)
final class BundledVerificationPresencePublisher {
    private static final Logger log = LoggerFactory.getLogger(
            BundledVerificationPresencePublisher.class);

    private final WorkplaceConnectorOpsRepository repository;
    private final WorkplaceConnectorOpsService service;
    private final BundledVerificationPresenceAdapter adapter;

    BundledVerificationPresencePublisher(
            WorkplaceConnectorOpsRepository repository,
            WorkplaceConnectorOpsService service,
            BundledVerificationPresenceAdapter adapter) {
        this.repository = repository;
        this.service = service;
        this.adapter = adapter;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onApplicationReady() {
        publish();
    }

    @Scheduled(fixedDelayString =
            "${dwp.workplace.connector-runtime.bundled-verification-presence-delay-ms:60000}")
    void publish() {
        repository.observableConfigurationForIdentity(
                        "default",
                        BundledVerificationPresenceAdapter.VERIFICATION_USER_ID,
                        UUID.fromString(BundledVerificationPresenceAdapter.VERIFICATION_PERSON_ID),
                        ConnectorKind.ACTUAL_PRESENCE,
                        BundledVerificationPresenceAdapter.PROVIDER,
                        BundledVerificationPresenceAdapter.CONFIGURATION_REFERENCE)
                .ifPresent(this::observe);
    }

    private void observe(WorkplaceConnectorOpsRepository.RuntimeRow configuration) {
        WorkplaceConnectorReplayAdapter.ProviderContext context =
                WorkplaceConnectorOpsService.providerContext(configuration);
        if (!adapter.ready(context)) return;
        try {
            service.observePolledRuntime(configuration, adapter.observe(context));
        } catch (RuntimeException failure) {
            log.warn("Bundled verification presence observation failed for tenant={} "
                            + "errorCode=VERIFICATION_PRESENCE_OBSERVATION_UNAVAILABLE",
                    configuration.tenantId());
        }
    }
}
