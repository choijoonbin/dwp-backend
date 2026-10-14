package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.audit.AuditEvent;
import com.dwp.contracts.hris.xcon.v1.Xcon021ApprovedCompensationPlanSnapshotPublishedV2;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Moves sealed Performance outbox records to the shared durable event outbox.
 * The shared relay owns broker delivery; this handoff never performs network I/O.
 */
@Component
class CompensationPlanningEventHandoffWorker {

    static final String SNAPSHOT_PUBLISHED =
            "ApprovedCompensationPlanSnapshotPublished.v2";
    static final String SNAPSHOT_PUBLISHED_TOPIC =
            "dwp.hris.modern.approved-compensation-plan-snapshot-published.v2";

    private static final Logger log = LoggerFactory.getLogger(
            CompensationPlanningEventHandoffWorker.class);
    private static final String SOURCE = "urn:dwp:people:performance:compensation-planning";
    private static final Map<String, EventContract> EVENT_CONTRACTS = eventContracts();
    private static final Set<String> AGGREGATE_TYPES = EVENT_CONTRACTS.values().stream()
            .map(EventContract::aggregateType)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> STANDARD_FIELDS = Set.of(
            "aggregateId", "fromState", "toState", "aggregateVersion",
            "occurredAt", "correlationId");

    private final CompensationPlanningEventHandoffRepository repository;
    private final DomainEventRecorder recorder;
    private final AuditOutboxRecorder audit;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    private final String workerId;
    private final int batchSize;
    private final Duration visibilityLease;
    private final int maximumAttempts;
    private final Consumer<CompensationPlanningEventHandoffRepository.ClaimedEvent>
            afterRecordHook;

    @Autowired
    CompensationPlanningEventHandoffWorker(
            CompensationPlanningEventHandoffRepository repository,
            DomainEventRecorder recorder,
            AuditOutboxRecorder audit,
            DomainEventContractRegistry contracts,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @Value("${dwp.compensation-planning.event-handoff.enabled:true}")
            boolean enabled,
            @Value("${spring.application.name:dwp-people-server}") String serviceName,
            @Value("${dwp.events.service-instance:${HOSTNAME:local}}")
            String serviceInstance,
            @Value("${dwp.compensation-planning.event-handoff.batch-size:50}")
            int batchSize,
            @Value("${dwp.compensation-planning.event-handoff.visibility-lease:PT30S}")
            Duration visibilityLease,
            @Value("${dwp.compensation-planning.event-handoff.maximum-attempts:20}")
            int maximumAttempts) {
        this(repository, recorder, audit, contracts, objectMapper, transactionManager,
                enabled, workerId(serviceName, serviceInstance), batchSize,
                visibilityLease, maximumAttempts, ignored -> { });
    }

    CompensationPlanningEventHandoffWorker(
            CompensationPlanningEventHandoffRepository repository,
            DomainEventRecorder recorder,
            AuditOutboxRecorder audit,
            DomainEventContractRegistry contracts,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            boolean enabled,
            String workerId,
            int batchSize,
            Duration visibilityLease,
            int maximumAttempts,
            Consumer<CompensationPlanningEventHandoffRepository.ClaimedEvent>
                    afterRecordHook) {
        this.repository = repository;
        this.recorder = recorder;
        this.audit = audit;
        this.mapper = objectMapper.copy().findAndRegisterModules();
        this.transaction = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
        this.workerId = workerId;
        this.batchSize = Math.min(200, Math.max(1, batchSize));
        this.visibilityLease = positive(visibilityLease, "visibilityLease");
        this.maximumAttempts = Math.max(1, maximumAttempts);
        this.afterRecordHook = afterRecordHook;
        EVENT_CONTRACTS.values().stream()
                .map(EventContract::topic)
                .forEach(topic -> contracts.register(topic, 2, 2));
    }

    @Scheduled(
            fixedDelayString =
                    "${dwp.compensation-planning.event-handoff.interval-ms:2000}")
    public void handoffPending() {
        if (enabled) handoffOnce();
    }

    int handoffOnce() {
        if (!enabled) return 0;
        int delivered = 0;
        for (CompensationPlanningEventHandoffRepository.ClaimedEvent event
                : repository.claim(
                        workerId, batchSize, visibilityLease,
                        AGGREGATE_TYPES)) {
            try {
                Boolean completed = transaction.execute(status -> {
                    recorder.record(envelope(event));
                    afterRecordHook.accept(event);
                    if (!repository.markDelivered(event)) {
                        throw new StaleHandoffClaimException(event.eventId());
                    }
                    return Boolean.TRUE;
                });
                if (Boolean.TRUE.equals(completed)) delivered++;
            } catch (InvalidPerformanceEventException exception) {
                persistFailure(event, false, exception);
            } catch (StaleHandoffClaimException exception) {
                log.info(
                        "Ignored stale compensation event handoff outcome for {} attempt {}",
                        event.eventId(), event.deliveryAttempt());
            } catch (RuntimeException exception) {
                persistFailure(event, true, exception);
            }
        }
        return delivered;
    }

    private DomainEventEnvelope envelope(
            CompensationPlanningEventHandoffRepository.ClaimedEvent event) {
        EventContract contract = EVENT_CONTRACTS.get(event.eventName());
        if (contract == null) {
            throw invalid(event, "Unsupported event name.");
        }
        if (!contract.aggregateType().equals(event.aggregateType())) {
            throw invalid(event, "Aggregate type does not match the event contract.");
        }
        EventIdentity identity = validatePayload(event, contract);
        return new DomainEventEnvelope(
                "1.0",
                event.eventId(),
                SOURCE,
                contract.topic(),
                2,
                identity.occurredAt(),
                event.aggregateType().toLowerCase().replace('_', '-')
                        + '/' + event.aggregateId(),
                event.tenantId(),
                event.aggregateType(),
                event.aggregateId().toString(),
                event.aggregateVersion(),
                identity.correlationId().toString(),
                event.commandReceiptId().toString(),
                null,
                event.payload(),
                Map.of());
    }

    private EventIdentity validatePayload(
            CompensationPlanningEventHandoffRepository.ClaimedEvent event,
            EventContract contract) {
        try {
            JsonNode payload = event.payload();
            if (!payload.isObject()) throw invalid(event, "Payload must be an object.");
            UUID aggregateId;
            long aggregateVersion;
            UUID correlationId;
            Instant occurredAt;
            if (SNAPSHOT_PUBLISHED.equals(event.eventName())) {
                Xcon021ApprovedCompensationPlanSnapshotPublishedV2 value =
                        mapper.treeToValue(
                                payload,
                                Xcon021ApprovedCompensationPlanSnapshotPublishedV2.class);
                aggregateId = value.aggregateId();
                aggregateVersion = value.aggregateVersion();
                correlationId = value.correlationId();
                occurredAt = value.occurredAt();
                validateTransition(event, contract, value.fromState(), value.toState());
                if (!value.aggregateId().equals(value.cycleId())
                        || !value.aggregateId().equals(value.planId())) {
                    throw invalid(event,
                            "XCON-021 aggregate, cycle, and plan identities differ.");
                }
                if (value.aggregateVersion() <= 1L
                        || value.sourceVersion()
                        != value.aggregateVersion() - 1L) {
                    throw invalid(event,
                            "XCON-021 source and aggregate versions are not consecutive.");
                }
            } else {
                if (!fieldNames(payload).equals(STANDARD_FIELDS)) {
                    throw invalid(event, "Transition payload fields do not match the contract.");
                }
                aggregateId = requiredUuid(payload, "aggregateId");
                aggregateVersion = requiredPositiveLong(payload, "aggregateVersion");
                correlationId = requiredUuid(payload, "correlationId");
                occurredAt = Instant.parse(requiredText(payload, "occurredAt"));
                validateTransition(
                        event,
                        contract,
                        requiredText(payload, "fromState"),
                        requiredText(payload, "toState"));
            }
            if (!event.aggregateId().equals(aggregateId)
                    || event.aggregateVersion() != aggregateVersion) {
                throw invalid(event, "Payload identity differs from the outbox header.");
            }
            if (!event.createdAt().equals(occurredAt)) {
                throw invalid(event,
                        "Payload occurrence time differs from the outbox creation time.");
            }
            return new EventIdentity(correlationId, occurredAt);
        } catch (InvalidPerformanceEventException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidPerformanceEventException(
                    "Invalid compensation event " + event.eventId() + '.', exception);
        }
    }

    private static void validateTransition(
            CompensationPlanningEventHandoffRepository.ClaimedEvent event,
            EventContract contract,
            String fromState,
            String toState) {
        if (!contract.transitions().contains(new Transition(fromState, toState))) {
            throw invalid(event, "State transition does not match the event contract.");
        }
    }

    private void persistFailure(
            CompensationPlanningEventHandoffRepository.ClaimedEvent event,
            boolean retryable,
            RuntimeException exception) {
        try {
            CompensationPlanningEventHandoffRepository.FailureDisposition disposition =
                    transaction.execute(ignored -> {
                        CompensationPlanningEventHandoffRepository.FailureDisposition value =
                                repository.markFailed(
                                        event, maximumAttempts, retryable);
                        if (value == CompensationPlanningEventHandoffRepository
                                .FailureDisposition.QUARANTINED) {
                            recordQuarantineAudit(event, retryable);
                        }
                        return value;
                    });
            if (disposition == CompensationPlanningEventHandoffRepository
                    .FailureDisposition.STALE) {
                log.info(
                        "Ignored stale compensation event failure for {} attempt {}",
                        event.eventId(), event.deliveryAttempt());
            }
        } catch (RuntimeException persistenceFailure) {
            log.error(
                    "Unable to persist compensation event handoff failure for {}",
                    event.eventId(), persistenceFailure);
        }
        log.warn(
                "Compensation event handoff failed for {} on attempt {}",
                event.eventId(), event.deliveryAttempt(), exception);
    }

    private void recordQuarantineAudit(
            CompensationPlanningEventHandoffRepository.ClaimedEvent event,
            boolean retryable) {
        String failureCode = retryable
                ? "DELIVERY_ATTEMPTS_EXHAUSTED"
                : "INVALID_SEALED_EVENT";
        audit.record(AuditEvent.builder()
                .tenantId(event.tenantId())
                .category("SYSTEM_EVENT")
                .action("compensation.event-handoff.quarantined")
                .outcome("FAILED")
                .severity("HIGH")
                .riskScore(85)
                .actorType("SYSTEM")
                .actorId(workerId)
                .sourceService("dwp-people-server")
                .sourceModule("hris-performance-compensation")
                .targetType("COMPENSATION_EVENT_HANDOFF")
                .targetId(event.eventId().toString())
                .reason(failureCode)
                .beforeState(Map.of(
                        "deliveryState", "PENDING",
                        "deliveryAttempts", event.deliveryAttempt()))
                .afterState(Map.of(
                        "deliveryState", "QUARANTINED",
                        "deliveryAttempts", event.deliveryAttempt()))
                .metadata(Map.of(
                        "aggregateType", event.aggregateType(),
                        "aggregateId", event.aggregateId(),
                        "aggregateVersion", event.aggregateVersion(),
                        "eventName", event.eventName(),
                        "maximumAttempts", maximumAttempts,
                        "failureCode", failureCode))
                .retentionClass("EXTENDED")
                .build());
    }

    private static Set<String> fieldNames(JsonNode payload) {
        java.util.HashSet<String> names = new java.util.HashSet<>();
        payload.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }

    private static String requiredText(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Missing event field " + field + '.');
        }
        return value.textValue();
    }

    private static UUID requiredUuid(JsonNode payload, String field) {
        String text = requiredText(payload, field);
        UUID value = UUID.fromString(text);
        if (!value.toString().equals(text)) {
            throw new IllegalArgumentException(
                    "Event field " + field + " is not a canonical UUID.");
        }
        return value;
    }

    private static long requiredPositiveLong(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() <= 0L) {
            throw new IllegalArgumentException(
                    "Event field " + field + " must be a positive integer.");
        }
        return value.longValue();
    }

    private static InvalidPerformanceEventException invalid(
            CompensationPlanningEventHandoffRepository.ClaimedEvent event,
            String message) {
        return new InvalidPerformanceEventException(
                message + " Event " + event.eventId() + '.');
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return value;
    }

    private static String workerId(String serviceName, String serviceInstance) {
        return serviceName + ':' + serviceInstance + ':' + UUID.randomUUID();
    }

    static Set<String> supportedCycleEventNames() {
        return eventNamesFor("COMPENSATION_CYCLE");
    }

    static Set<String> supportedProposalEventNames() {
        return eventNamesFor("COMPENSATION_PROPOSAL");
    }

    static Set<String> supportedAggregateTypes() {
        return AGGREGATE_TYPES;
    }

    private static Set<String> eventNamesFor(String aggregateType) {
        return EVENT_CONTRACTS.entrySet().stream()
                .filter(entry -> entry.getValue().aggregateType().equals(aggregateType))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Map<String, EventContract> eventContracts() {
        Map<String, EventContract> values = new LinkedHashMap<>();
        values.put(SNAPSHOT_PUBLISHED, contract(
                SNAPSHOT_PUBLISHED_TOPIC,
                "COMPENSATION_CYCLE",
                transition("APPROVED", "PUBLISHED")));
        values.put("CompensationBudgetAllocated.v2", contract(
                "dwp.hris.modern.compensation-budget-allocated.v2",
                "COMPENSATION_CYCLE",
                transition("DRAFT", "OPEN"),
                transition("OPEN", "OPEN")));
        values.put("CompensationCycleCancelled.v2", contract(
                "dwp.hris.modern.compensation-cycle-cancelled.v2",
                "COMPENSATION_CYCLE",
                transition("DRAFT", "CANCELLED"),
                transition("OPEN", "CANCELLED"),
                transition("PROPOSAL_REVIEW", "CANCELLED")));
        values.put("CompensationCycleCreated.v2", contract(
                "dwp.hris.modern.compensation-cycle-created.v2",
                "COMPENSATION_CYCLE",
                transition("NONE", "DRAFT")));
        values.put("CompensationCycleRevised.v2", contract(
                "dwp.hris.modern.compensation-cycle-revised.v2",
                "COMPENSATION_CYCLE",
                transition("DRAFT", "DRAFT"),
                transition("OPEN", "OPEN")));
        values.put("CompensationPlanApprovalRecorded.v2", contract(
                "dwp.hris.modern.compensation-plan-approval-recorded.v2",
                "COMPENSATION_CYCLE",
                transition("PROPOSAL_REVIEW", "APPROVED")));
        values.put("CompensationProposalDrafted.v2", contract(
                "dwp.hris.modern.compensation-proposal-drafted.v2",
                "COMPENSATION_PROPOSAL",
                transition("NONE", "DRAFT"),
                transition("DRAFT", "DRAFT")));
        values.put("CompensationProposalSubmitted.v2", contract(
                "dwp.hris.modern.compensation-proposal-submitted.v2",
                "COMPENSATION_PROPOSAL",
                transition("DRAFT", "SUBMITTED")));
        return Map.copyOf(values);
    }

    private static EventContract contract(
            String topic,
            String aggregateType,
            Transition... transitions) {
        return new EventContract(topic, aggregateType, Set.of(transitions));
    }

    private static Transition transition(String fromState, String toState) {
        return new Transition(fromState, toState);
    }

    private record EventContract(
            String topic,
            String aggregateType,
            Set<Transition> transitions) {
    }

    private record Transition(String fromState, String toState) {
    }

    private record EventIdentity(UUID correlationId, Instant occurredAt) {
    }

    private static final class InvalidPerformanceEventException
            extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private InvalidPerformanceEventException(String message) {
            super(message);
        }

        private InvalidPerformanceEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class StaleHandoffClaimException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private StaleHandoffClaimException(UUID eventId) {
            super("Compensation event claim is stale: " + eventId);
        }
    }
}
