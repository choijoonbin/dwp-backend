package com.dwp.services.platform.workplace.workplacevisits;

import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitDtos.*;
import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitProviderPort.*;
import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitRepository.*;

/**
 * Durable worker entry point. Provider mutations are claimed once; uncertain outcomes are
 * reconciled by status lookup using the immutable provider binding snapshot.
 */
@Service
public class WorkplaceVisitProviderWorker {
    private static final Logger log = LoggerFactory.getLogger(WorkplaceVisitProviderWorker.class);
    private final WorkplaceVisitRepository repository;
    private final WorkplaceVisitProviderResultService results;
    private final List<WorkplaceVisitProviderPort> ports;
    private final Clock clock;

    @Autowired
    public WorkplaceVisitProviderWorker(
            WorkplaceVisitRepository repository,
            WorkplaceVisitProviderResultService results,
            List<WorkplaceVisitProviderPort> ports) {
        this(repository, results, ports, Clock.systemUTC());
    }

    WorkplaceVisitProviderWorker(
            WorkplaceVisitRepository repository,
            WorkplaceVisitProviderResultService results,
            List<WorkplaceVisitProviderPort> ports,
            Clock clock) {
        this.repository = repository;
        this.results = results;
        this.ports = List.copyOf(ports);
        this.clock = clock;
    }

    public int processPending(long tenantId, int limit) {
        int processed = 0;
        for (OutboxRow row : repository.pendingOutbox(tenantId, limit)) {
            if (processOne(tenantId, row.id())) processed++;
        }
        return processed;
    }

    public int processPending(int limit) {
        int processed = 0;
        for (OutboxRow row : repository.pendingOutbox(limit)) {
            try {
                if (processOne(row.tenantId(), row.id())) processed++;
            } catch (RuntimeException failure) {
                log.warn("Visit provider delivery did not complete for outboxId={}",
                        row.id(), failure);
            }
        }
        return processed;
    }

    public boolean processOne(long tenantId, UUID outboxId) {
        OutboxRow queued = repository.outbox(tenantId, outboxId).orElse(null);
        if (queued == null || !("PENDING".equals(queued.deliveryState())
                || "RETRY".equals(queued.deliveryState()))) return false;
        OutboxRow governed = queued;
        boolean lookup = "CHECK_PROVIDER_STATUS".equals(queued.operationType());
        if (lookup) {
            if (queued.relatedOutboxId() == null) return false;
            governed = repository.outbox(tenantId, queued.relatedOutboxId()).orElse(null);
            if (governed == null || !"RESULT_UNKNOWN".equals(governed.deliveryState())) return false;
        }
        ProviderKind kind = providerKind(governed.operationType());
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!repository.claimOutbox(tenantId, queued.id(), now)) return false;
        if (governed.providerKind() == null || governed.providerCode() == null
                || governed.providerConfigurationVersion() == null
                || !kind.name().equals(governed.providerKind())) {
            results.apply(tenantId, queued.id(), unknown(governed,
                    "PROVIDER_BINDING_SNAPSHOT_MISSING"));
            return true;
        }
        if (!lookup && !"REVOKE_ACCESS".equals(governed.operationType())) {
            ProviderRow binding = repository.provider(tenantId, kind).orElse(null);
            ProviderTruth truth = binding == null ? null
                    : WorkplaceVisitProviderTruth.evaluate(binding, now);
            if (binding == null || truth.state() != ProviderTruthState.READY
                    || !binding.providerCode().equals(governed.providerCode())
                    || binding.configurationVersion()
                        != governed.providerConfigurationVersion()) {
                results.apply(tenantId, queued.id(), failed(governed,
                        "PROVIDER_BINDING_CHANGED_OR_NOT_READY"));
                return true;
            }
        }
        OutboxRow operationRow = governed;
        List<WorkplaceVisitProviderPort> matchingPorts = ports.stream()
                .filter(candidate -> candidate.supports(kind, operationRow.providerCode()))
                .toList();
        if (matchingPorts.size() != 1) {
            String detail = matchingPorts.isEmpty() ? "PROVIDER_ADAPTER_NOT_CONFIGURED"
                    : "PROVIDER_ADAPTER_AMBIGUOUS";
            results.apply(tenantId, queued.id(), lookup
                    ? unknown(governed, detail) : failed(governed, detail));
            return true;
        }
        WorkplaceVisitProviderPort port = matchingPorts.getFirst();
        ProviderOperation operation = new ProviderOperation(operationRow.id(), tenantId,
                operationRow.visitId(), kind, operationRow.providerCode(),
                operationRow.providerConfigurationVersion(), operationRow.operationType());
        ProviderOutcome outcome;
        try {
            outcome = lookup ? port.lookup(operation) : port.dispatch(operation);
            if (outcome == null) throw new IllegalStateException("Provider returned no outcome");
        } catch (RuntimeException uncertain) {
            outcome = new ProviderOutcome(OutcomeState.RESULT_UNKNOWN,
                    "operation:" + governed.id(), "PROVIDER_TRANSPORT_OUTCOME_UNKNOWN");
        }
        results.apply(tenantId, queued.id(), outcome);
        return true;
    }

    private static ProviderOutcome failed(OutboxRow row, String detail) {
        return new ProviderOutcome(OutcomeState.FAILED,
                "operation:" + row.id(), detail);
    }

    private static ProviderOutcome unknown(OutboxRow row, String detail) {
        return new ProviderOutcome(OutcomeState.RESULT_UNKNOWN,
                "operation:" + row.id(), detail);
    }

    private static ProviderKind providerKind(String operationType) {
        return switch (operationType) {
            case "SEND_INVITATION", "NOTIFY_HOST" -> ProviderKind.VISITOR;
            case "REQUEST_ACCESS", "REVOKE_ACCESS" -> ProviderKind.ACCESS;
            default -> throw new IllegalArgumentException("Unsupported provider operation: "
                    + operationType);
        };
    }
}
