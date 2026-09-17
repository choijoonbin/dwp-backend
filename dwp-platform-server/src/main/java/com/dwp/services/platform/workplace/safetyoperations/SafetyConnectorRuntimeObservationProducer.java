package com.dwp.services.platform.workplace.safetyoperations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.*;

/** Pulls provider-owned connector health rather than inferring readiness from configuration. */
@Component
@ConditionalOnProperty(
        name = "dwp.workplace.safety.connector-observation-producer-enabled",
        havingValue = "true")
class SafetyConnectorRuntimeObservationProducer {
    private static final Logger log = LoggerFactory.getLogger(
            SafetyConnectorRuntimeObservationProducer.class);

    private final SafetyIncidentRepository repository;
    private final SafetyConnectorService connectors;
    private final SafetyHttpDispatchProvider provider;
    private final SafetyProviderRelayBindings bindings;
    private final int batchSize;

    SafetyConnectorRuntimeObservationProducer(
            SafetyIncidentRepository repository,
            SafetyConnectorService connectors,
            SafetyHttpDispatchProvider provider,
            SafetyProviderRelayBindings bindings,
            @Value("${dwp.workplace.safety.connector-observation-batch-size:50}")
            int batchSize) {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException(
                    "safety connector observation batchSize must be between 1 and 500");
        }
        this.repository = repository;
        this.connectors = connectors;
        this.provider = provider;
        this.bindings = bindings;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString =
            "${dwp.workplace.safety.connector-observation-delay-ms:60000}")
    void observeConfiguredConnectors() {
        for (SafetyIncidentRepository.ObservableConnectorRow row
                : repository.observableConnectors(batchSize)) {
            try {
                DeliveryChannel channel = channel(row.kind());
                SafetyDispatchProvider.ProviderContext context = bindings.resolve(channel,
                        row.provider(), row.configurationVersion()).orElse(null);
                if (context == null || !provider.ready(context)) continue;
                SafetyHttpDispatchProvider.RuntimeObservation observed =
                        provider.observe(row.tenantId(), row.kind(), context);
                OffsetDateTime receivedAt = OffsetDateTime.now(ZoneOffset.UTC);
                connectors.observe(new ConnectorObservation(row.tenantId(), row.kind(),
                        row.provider(), row.configurationVersion(), observed.reportedState(),
                        observed.evidenceReference(), observed.sourceAt(), receivedAt,
                        observed.lastSuccessAt(), observed.errorCode()));
            } catch (RuntimeException failure) {
                log.warn("Safety connector observation failed for tenant={} connector={} type={}",
                        row.tenantId(), row.kind(), failure.getClass().getSimpleName());
            }
        }
    }

    private static DeliveryChannel channel(ConnectorKind kind) {
        return switch (kind) {
            case EBS -> DeliveryChannel.EBS;
            case BLE_MESH -> DeliveryChannel.BLE_MESH;
            case EMERGENCY_119, WORM, GOVERNMENT_LOG -> DeliveryChannel.APP_PUSH;
        };
    }
}
