package com.dwp.services.platform.workplace.connectorops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsRepository.RuntimeRow;

/** Polls configured provider relays and persists provider-owned runtime observations. */
@Component
@ConditionalOnProperty(
        name = "dwp.workplace.connector-runtime.observation-producer-enabled",
        havingValue = "true")
class WorkplaceConnectorRuntimeObservationProducer {
    private static final Logger log = LoggerFactory.getLogger(
            WorkplaceConnectorRuntimeObservationProducer.class);

    private final WorkplaceConnectorOpsRepository repository;
    private final WorkplaceConnectorOpsService service;
    private final int batchSize;

    WorkplaceConnectorRuntimeObservationProducer(
            WorkplaceConnectorOpsRepository repository,
            WorkplaceConnectorOpsService service,
            @Value("${dwp.workplace.connector-runtime.observation-producer-batch-size:50}")
            int batchSize) {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("observation producer batchSize must be between 1 and 500");
        }
        this.repository = repository;
        this.service = service;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString =
            "${dwp.workplace.connector-runtime.observation-producer-delay-ms:60000}")
    void observeConfiguredConnectors() {
        for (RuntimeRow configuration : repository.observableConfigurations(batchSize)) {
            try {
                WorkplaceConnectorReplayAdapter.ProviderContext context =
                        WorkplaceConnectorOpsService.providerContext(configuration);
                WorkplaceConnectorReplayAdapter adapter = service.adapter(context);
                if (adapter == null) continue;
                service.observePolledRuntime(configuration, adapter.observe(context));
            } catch (RuntimeException failure) {
                log.warn("Provider runtime observation failed for tenant={} connector={} "
                                + "errorCode=PROVIDER_RUNTIME_OBSERVATION_UNAVAILABLE",
                        configuration.tenantId(), configuration.kind());
            }
        }
    }
}
