package com.dwp.services.platform.workplace.workplacenavigation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceDeviceCommandProvider.*;

/** Pulls provider-authored runtime truth without treating local configuration as health. */
@Component
class WorkplaceDeviceProviderObservationWorker {
    private static final Logger log =
            LoggerFactory.getLogger(WorkplaceDeviceProviderObservationWorker.class);

    private final WorkplaceDeviceService service;
    private final Optional<WorkplaceDeviceCommandProvider> provider;
    private final boolean enabled;
    private final int batchSize;
    private final Clock clock;

    @Autowired
    WorkplaceDeviceProviderObservationWorker(
            WorkplaceDeviceService service,
            Optional<WorkplaceDeviceCommandProvider> provider,
            @Value("${dwp.workplace.navigation.provider-observation.enabled:true}") boolean enabled,
            @Value("${dwp.workplace.navigation.provider-observation.batch-size:50}") int batchSize) {
        this(service, provider, enabled, batchSize, Clock.systemUTC());
    }

    WorkplaceDeviceProviderObservationWorker(
            WorkplaceDeviceService service,
            Optional<WorkplaceDeviceCommandProvider> provider,
            boolean enabled,
            int batchSize,
            Clock clock) {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("batchSize must be between 1 and 500");
        }
        this.service = service;
        this.provider = provider;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString =
            "${dwp.workplace.navigation.provider-observation.poll-delay-ms:60000}")
    void scheduledObserve() {
        if (!enabled || provider.isEmpty()) return;
        for (var candidate : service.providerObservationCandidates(batchSize)) {
            ProviderBinding binding = candidate.binding();
            if (binding == null || !provider.get().ready(binding)) continue;
            try {
                provider.get().observe(new ProviderObservationCommand(
                                candidate.truth().tenantId(), candidate.truth().capability(), binding))
                        .ifPresent(observation -> service.applyProviderObservation(
                                candidate, observation, OffsetDateTime.now(clock)));
            } catch (RuntimeException unavailable) {
                log.warn("Workplace device provider observation failed for tenantId={} capability={}",
                        candidate.truth().tenantId(), candidate.truth().capability(), unavailable);
            }
        }
    }
}
