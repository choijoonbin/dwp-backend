package com.dwp.services.platform.workplace.workplacenavigation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceDeviceCommandProvider.*;

/** Durable dispatcher and GET-only reconciliation loop for device provider commands. */
@Component
public class WorkplaceDeviceCommandWorker {
    private static final Logger log = LoggerFactory.getLogger(WorkplaceDeviceCommandWorker.class);

    private final WorkplaceDeviceService service;
    private final Optional<WorkplaceDeviceCommandProvider> provider;
    private final boolean enabled;
    private final int batchSize;
    private final Duration processingTimeout;

    @Autowired
    public WorkplaceDeviceCommandWorker(
            WorkplaceDeviceService service,
            Optional<WorkplaceDeviceCommandProvider> provider,
            @Value("${dwp.workplace.navigation.command-worker.enabled:true}") boolean enabled,
            @Value("${dwp.workplace.navigation.command-worker.batch-size:20}") int batchSize,
            @Value("${dwp.workplace.navigation.command-worker.processing-timeout:PT2M}")
            Duration processingTimeout) {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("batchSize must be between 1 and 500");
        }
        if (processingTimeout == null || processingTimeout.compareTo(Duration.ofSeconds(10)) < 0
                || processingTimeout.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException(
                    "processingTimeout must be between 10 seconds and 1 hour");
        }
        this.service = service;
        this.provider = provider;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.processingTimeout = processingTimeout;
    }

    WorkplaceDeviceCommandWorker(
            WorkplaceDeviceService service,
            Optional<WorkplaceDeviceCommandProvider> provider) {
        this(service, provider, true, 20, Duration.ofMinutes(2));
    }

    @Scheduled(fixedDelayString =
            "${dwp.workplace.navigation.command-worker.poll-delay-ms:2000}")
    public void scheduledDispatch() {
        if (!enabled) return;
        try {
            service.recoverStaleDispatches(processingTimeout);
            boolean dispatchFirst = true;
            for (int index = 0; index < batchSize; index++) {
                boolean worked = dispatchFirst ? dispatchNext() : reconcileNext();
                if (!worked) {
                    worked = dispatchFirst ? reconcileNext() : dispatchNext();
                }
                if (!worked) break;
                dispatchFirst = !dispatchFirst;
            }
        } catch (RuntimeException unavailable) {
            log.warn("Workplace device command worker cycle did not complete", unavailable);
        }
    }

    public boolean dispatchNext() {
        WorkplaceDeviceService.DispatchEnvelope envelope = service.prepareDispatch();
        if (envelope == null) return false;
        ProviderCommandOutcome outcome;
        if (envelope.preflightFailure() != null) {
            outcome = unknown(envelope.command(), envelope.preflightFailure());
        } else if (provider.isEmpty()) {
            outcome = failed("PROVIDER_ADAPTER_NOT_CONFIGURED");
        } else {
            ProviderCommand command = service.providerCommand(envelope);
            if (!provider.get().ready(command.binding())) {
                outcome = failed("PROVIDER_RELAY_NOT_CONFIGURED");
            } else {
                try {
                    outcome = normalized(provider.get().execute(command), command);
                } catch (RuntimeException uncertain) {
                    outcome = unknown(command, "PROVIDER_TRANSPORT_OUTCOME_UNKNOWN");
                }
            }
        }
        service.finishDispatch(envelope, outcome);
        return true;
    }

    public boolean reconcileNext() {
        WorkplaceDeviceService.DispatchEnvelope envelope = service.prepareReconciliation();
        if (envelope == null) return false;
        ProviderCommandOutcome outcome;
        if (envelope.preflightFailure() != null) {
            outcome = unknown(envelope.command(), envelope.preflightFailure());
        } else if (provider.isEmpty()) {
            outcome = unknown(service.providerCommand(envelope),
                    "PROVIDER_ADAPTER_NOT_CONFIGURED");
        } else {
            ProviderCommand command = service.providerCommand(envelope);
            if (!provider.get().ready(command.binding())) {
                outcome = unknown(command, "PROVIDER_RELAY_NOT_CONFIGURED");
            } else {
                try {
                    outcome = normalized(provider.get().status(command), command);
                } catch (RuntimeException unavailable) {
                    outcome = unknown(command, "PROVIDER_STATUS_UNAVAILABLE");
                }
            }
        }
        service.finishDispatch(envelope, outcome);
        return true;
    }

    private static ProviderCommandOutcome normalized(
            ProviderCommandOutcome outcome, ProviderCommand command) {
        if (outcome == null || outcome.state() == null
                || tooLong(outcome.providerOperationReference(), 320)
                || tooLong(outcome.resultCode(), 120)) {
            return unknown(command, "PROVIDER_RESPONSE_INVALID");
        }
        return outcome;
    }

    private static boolean tooLong(String value, int maximum) {
        return value != null && value.length() > maximum;
    }

    private static ProviderCommandOutcome failed(String code) {
        return new ProviderCommandOutcome(OutcomeState.FAILED, null, code);
    }

    private static ProviderCommandOutcome unknown(ProviderCommand command, String code) {
        return unknown(command.commandId(), code);
    }

    private static ProviderCommandOutcome unknown(
            WorkplaceNavigationDtos.CommandRow command, String code) {
        return unknown(command.commandId(), code);
    }

    private static ProviderCommandOutcome unknown(java.util.UUID commandId, String code) {
        return new ProviderCommandOutcome(
                OutcomeState.RESULT_UNKNOWN, "command:" + commandId, code);
    }
}
