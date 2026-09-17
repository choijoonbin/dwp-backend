package com.dwp.services.platform.workplace.safetyoperations;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.List;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyIncidentRepository.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.*;

@Service
public class SafetyDispatchService {
    private final SafetyIncidentRepository repository;
    private final SafetyDispatchRecoveryRepository recovery;
    private final SafetyConnectorService connectors;
    private final List<SafetyDispatchProvider> providers;
    private final TransactionOperations transactions;
    private final SafetyProviderRelayBindings bindings;
    private final Duration dispatchTimeout;
    private final Clock clock;

    @Autowired
    public SafetyDispatchService(
            SafetyIncidentRepository repository,
            SafetyDispatchRecoveryRepository recovery,
            SafetyConnectorService connectors,
            List<SafetyDispatchProvider> providers,
            TransactionOperations transactions,
            SafetyProviderRelayBindings bindings,
            @Value("${dwp.workplace.safety.dispatch-processing-timeout:PT2M}")
            Duration dispatchTimeout) {
        this(repository, recovery, connectors, providers, transactions, bindings,
                dispatchTimeout, Clock.systemUTC());
    }

    SafetyDispatchService(
            SafetyIncidentRepository repository,
            SafetyDispatchRecoveryRepository recovery,
            SafetyConnectorService connectors,
            List<SafetyDispatchProvider> providers,
            TransactionOperations transactions,
            Clock clock) {
        this(repository, recovery, connectors, providers, transactions, null,
                Duration.ofMinutes(2), clock);
    }

    SafetyDispatchService(
            SafetyIncidentRepository repository,
            SafetyDispatchRecoveryRepository recovery,
            SafetyConnectorService connectors,
            List<SafetyDispatchProvider> providers,
            TransactionOperations transactions,
            SafetyProviderRelayBindings bindings,
            Duration dispatchTimeout,
            Clock clock) {
        this.repository = repository;
        this.recovery = recovery;
        this.connectors = connectors;
        this.providers = List.copyOf(providers);
        this.transactions = transactions;
        this.bindings = bindings;
        if (dispatchTimeout.isNegative() || dispatchTimeout.isZero()) {
            throw new IllegalArgumentException("dispatch processing timeout must be positive");
        }
        this.dispatchTimeout = dispatchTimeout;
        this.clock = clock;
    }

    public boolean dispatch(DispatchWorkRow work) {
        OffsetDateTime acceptedAt = now();
        SafetyDispatchProvider.ProviderContext context = providerContext(work);
        Boolean claimed = transactions.execute(status -> context == null
                ? repository.claimDispatch(work.tenantId(), work.attemptId(), acceptedAt)
                : repository.claimDispatch(work.tenantId(), work.attemptId(), context, acceptedAt,
                        acceptedAt.minus(dispatchTimeout)));
        if (!Boolean.TRUE.equals(claimed)) return false;
        ConnectorKind required = connectors.requiredConnector(work.channel());
        if (required != null
                && connectors.truth(work.tenantId()).stream()
                .filter(item -> item.kind() == required)
                .noneMatch(item -> item.state() == ConnectorTruthState.READY
                        && context != null
                        && java.util.Objects.equals(
                                item.providerCode(), context.providerCode())
                        && item.configurationVersion()
                            == context.providerConfigurationVersion())) {
            transactions.executeWithoutResult(status -> repository.markOffline(
                    work.tenantId(), work.attemptId(), "PROVIDER_NOT_VERIFIED", now()));
            return true;
        }
        SafetyDispatchProvider provider = providers.stream()
                .filter(candidate -> candidate.supports(work.channel()))
                .filter(candidate -> candidate.ready(context)).findFirst().orElse(null);
        if (provider == null) {
            transactions.executeWithoutResult(status -> repository.markOffline(
                    work.tenantId(), work.attemptId(), "PROVIDER_NOT_CONFIGURED", now()));
            return true;
        }

        SafetyDispatchProvider.DispatchResult result;
        try {
            result = provider.dispatch(new SafetyDispatchProvider.DispatchRequest(
                    work.attemptId(), work.tenantId(), work.incidentId(), work.channel(),
                    work.subjectKey(), work.subjectUserId(), work.severity(),
                    work.message(), work.safetyAction(), context));
        } catch (SafetyDispatchProvider.OutcomeUnknownException uncertain) {
            result = new SafetyDispatchProvider.DispatchResult(AttemptState.RESULT_UNKNOWN,
                    uncertain.providerOperationReference(), "PROVIDER_CALL_OUTCOME_UNKNOWN", null);
        } catch (RuntimeException uncertain) {
            result = new SafetyDispatchProvider.DispatchResult(AttemptState.RESULT_UNKNOWN,
                    null, "PROVIDER_CALL_OUTCOME_UNKNOWN", null);
        }
        SafetyDispatchProvider.DispatchResult finalResult = result;
        return Boolean.TRUE.equals(transactions.execute(status -> repository.recordOutcome(
                new DispatchOutcome(work.tenantId(), work.attemptId(), finalResult.state(),
                        finalResult.providerOperationReference(), finalResult.resultCode(),
                        finalResult.evidenceReference(), now(), now()))));
    }

    public boolean reconcile(SafetyDispatchRecoveryRepository.RecoveryWorkRow work) {
        SafetyDispatchProvider provider = providers.stream()
                .filter(candidate -> candidate.supports(work.channel()))
                .filter(candidate -> candidate.ready(work.providerContext()))
                .findFirst().orElse(null);
        if (provider == null) {
            return Boolean.TRUE.equals(transactions.execute(
                    status -> recovery.deferUnavailable(work, now())));
        }
        OffsetDateTime claimedAt = now();
        Boolean claimed = transactions.execute(status -> recovery.claim(work, claimedAt));
        if (!Boolean.TRUE.equals(claimed)) return false;
        SafetyDispatchProvider.DispatchResult result;
        try {
            result = provider.lookupStatus(new SafetyDispatchProvider.LookupRequest(
                    work.attemptId(), work.tenantId(), work.incidentId(), work.channel(),
                    work.providerOperationReference(), work.providerContext()));
        } catch (RuntimeException unavailable) {
            result = new SafetyDispatchProvider.DispatchResult(AttemptState.RESULT_UNKNOWN,
                    work.providerOperationReference(), "STATUS_LOOKUP_UNAVAILABLE", null);
        }
        SafetyDispatchProvider.DispatchResult outcome = result;
        return Boolean.TRUE.equals(transactions.execute(
                status -> recovery.record(work, outcome, now())));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private SafetyDispatchProvider.ProviderContext providerContext(DispatchWorkRow work) {
        if (work.providerContext() != null) return work.providerContext();
        if (bindings == null) return null;
        SafetyIncidentRepository.ConnectorRow connector =
                connectors.configuredConnector(work.tenantId(), work.channel());
        return connector == null
                ? bindings.resolve(work.channel()).orElse(null)
                : bindings.resolve(work.channel(), connector.provider(),
                        connector.configurationVersion()).orElse(null);
    }
}
