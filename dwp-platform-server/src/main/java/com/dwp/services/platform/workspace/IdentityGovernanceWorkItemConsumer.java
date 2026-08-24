package com.dwp.services.platform.workspace;

import com.dwp.core.event.DomainEventConsumerFactory;
import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.IdempotentDomainEventConsumer;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Kafka adapter for the Auth-owned review-work event contract. */
@Component
@ConditionalOnProperty(
        name = "dwp.workspace.identity-governance-consumer-enabled",
        havingValue = "true")
public class IdentityGovernanceWorkItemConsumer {

    static final String ASSIGNED = "identity.access-review.item.assigned.v1";
    static final String DECIDED = "identity.access-review.item.decided.v1";
    static final String REVOKED = "identity.access-review.item.revoked.v1";
    static final String SOURCE = "urn:dwp:auth:access-review";
    static final String AGGREGATE = "ACCESS_REVIEW_WORK_ITEM";
    private static final Set<String> TYPES = Set.of(ASSIGNED, DECIDED, REVOKED);
    private static final Set<String> DATA_FIELDS = Set.of(
            "workItemRef",
            "reviewerUserId",
            "dueAt",
            "decision",
            "assignmentState",
            "objectVersion");

    private final ObjectMapper objectMapper;
    private final IdentityGovernanceWorkItemProjectionRepository repository;
    private final IdempotentDomainEventConsumer consumer;

    public IdentityGovernanceWorkItemConsumer(
            ObjectMapper objectMapper,
            DomainEventContractRegistry contracts,
            DomainEventConsumerFactory consumers,
            IdentityGovernanceWorkItemProjectionRepository repository) {
        this.objectMapper = objectMapper;
        this.repository = repository;
        TYPES.forEach(type -> contracts.register(type, 1, 1));
        this.consumer = consumers.create("identity-governance-work-items.v1");
    }

    @KafkaListener(
            topics = "${dwp.workspace.identity-governance-topic:dwp.domain-events.v1}",
            groupId = "${dwp.workspace.identity-governance-group-id:"
                    + "dwp-platform-identity-governance-work-items}")
    public void onMessage(String payload) {
        DomainEventEnvelope event = parse(payload);
        if (!SOURCE.equals(event.source()) || !TYPES.contains(event.type())) return;
        IdempotentDomainEventConsumer.DeliveryResult result = consume(event);
        if (Set.of(
                IdempotentDomainEventConsumer.DeliveryState.OUT_OF_ORDER,
                IdempotentDomainEventConsumer.DeliveryState.DEFERRED,
                IdempotentDomainEventConsumer.DeliveryState.BUSY,
                IdempotentDomainEventConsumer.DeliveryState.RETRY_SCHEDULED)
                .contains(result.state())) {
            throw new IllegalStateException(
                    "Identity governance projection requires transport redelivery: "
                            + result.state());
        }
    }

    public IdempotentDomainEventConsumer.DeliveryResult consume(DomainEventEnvelope event) {
        return consumer.consume(event, this::project);
    }

    private void project(DomainEventEnvelope event) {
        Projection projection = validate(event);
        if (ASSIGNED.equals(event.type())) {
            if (!repository.assigned(
                    event.tenantId(),
                    event.id(),
                    event.aggregateSequence(),
                    projection.workItemRef(),
                    projection.reviewerUserId(),
                    projection.dueAt())) {
                throw new IllegalStateException("Assigned review projection was not advanced.");
            }
        } else if (DECIDED.equals(event.type())) {
            if (!repository.decided(
                    event.tenantId(),
                    event.id(),
                    event.aggregateSequence(),
                    projection.workItemRef(),
                    projection.decision())) {
                throw new IllegalStateException("Decided review projection was not advanced.");
            }
        } else if (!repository.revoked(
                event.tenantId(),
                event.id(),
                event.aggregateSequence(),
                projection.workItemRef())) {
            throw new IllegalStateException("Revoked review projection was not advanced.");
        }
    }

    private Projection validate(DomainEventEnvelope event) {
        if (event == null
                || !SOURCE.equals(event.source())
                || !TYPES.contains(event.type())
                || !AGGREGATE.equals(event.aggregateType())
                || event.tenantId() == null
                || event.tenantId() <= 0
                || event.data() == null
                || !event.data().isObject()) {
            throw invalid();
        }
        Set<String> fields = event.data().propertyStream()
                .map(entry -> entry.getKey())
                .collect(Collectors.toUnmodifiableSet());
        if (!DATA_FIELDS.equals(fields)) throw invalid();
        JsonNode data = event.data();
        UUID workItemRef = canonicalUuid(text(data, "workItemRef"));
        if (!workItemRef.toString().equals(event.aggregateId())) throw invalid();
        Long reviewerUserId = positiveLong(data.get("reviewerUserId"));
        Instant dueAt = Instant.parse(text(data, "dueAt"));
        String decision = text(data, "decision");
        String assignmentState = text(data, "assignmentState");
        long objectVersion = nonNegativeLong(data.get("objectVersion"));
        if (!Set.of("PENDING", "APPROVE", "REVOKE").contains(decision)
                || !Set.of("ACTIVE", "REVOKED").contains(assignmentState)
                || (ASSIGNED.equals(event.type())
                        && (event.aggregateSequence() != 1L
                                || !"PENDING".equals(decision)
                                || !"ACTIVE".equals(assignmentState)
                                || objectVersion != 0L))
                || (DECIDED.equals(event.type()) && "PENDING".equals(decision))
                || (REVOKED.equals(event.type()) && !"REVOKED".equals(assignmentState))) {
            throw invalid();
        }
        return new Projection(workItemRef, reviewerUserId, dueAt, decision);
    }

    private DomainEventEnvelope parse(String payload) {
        try {
            return objectMapper.readerFor(DomainEventEnvelope.class)
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .readValue(payload);
        } catch (java.io.IOException exception) {
            throw invalid();
        }
    }

    private String text(JsonNode data, String field) {
        JsonNode value = data.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw invalid();
        return value.textValue();
    }

    private UUID canonicalUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (parsed.toString().equals(value)) return parsed;
        } catch (IllegalArgumentException ignored) {
            // Fail below without exposing parser detail.
        }
        throw invalid();
    }

    private Long positiveLong(JsonNode value) {
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToLong() || value.longValue() <= 0) {
            throw invalid();
        }
        return value.longValue();
    }

    private long nonNegativeLong(JsonNode value) {
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalid();
        }
        return value.longValue();
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid Identity governance work-item event.");
    }

    private record Projection(
            UUID workItemRef,
            Long reviewerUserId,
            Instant dueAt,
            String decision) {
    }
}
