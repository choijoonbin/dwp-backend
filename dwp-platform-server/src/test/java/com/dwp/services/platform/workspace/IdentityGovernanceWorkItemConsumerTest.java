package com.dwp.services.platform.workspace;

import com.dwp.core.event.DomainEventConsumerFactory;
import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.IdempotentDomainEventConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IdentityGovernanceWorkItemConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final DomainEventContractRegistry contracts = mock(DomainEventContractRegistry.class);
    private final DomainEventConsumerFactory factory = mock(DomainEventConsumerFactory.class);
    private final IdempotentDomainEventConsumer idempotent =
            mock(IdempotentDomainEventConsumer.class);
    private final IdentityGovernanceWorkItemProjectionRepository repository =
            mock(IdentityGovernanceWorkItemProjectionRepository.class);
    private IdentityGovernanceWorkItemConsumer consumer;

    @BeforeEach
    void setUp() {
        when(factory.create("identity-governance-work-items.v1")).thenReturn(idempotent);
        when(idempotent.consume(any(), any())).thenAnswer(invocation -> {
            DomainEventEnvelope event = invocation.getArgument(0);
            IdempotentDomainEventConsumer.DomainEventHandler handler = invocation.getArgument(1);
            handler.handle(event);
            return new IdempotentDomainEventConsumer.DeliveryResult(
                    IdempotentDomainEventConsumer.DeliveryState.PROCESSED, 1, null);
        });
        consumer = new IdentityGovernanceWorkItemConsumer(
                objectMapper, contracts, factory, repository);
    }

    @Test
    void assignedEventCreatesOnlyTheOpaqueReviewerProjection() {
        UUID eventId = UUID.randomUUID();
        UUID ref = UUID.randomUUID();
        Instant dueAt = Instant.parse("2026-08-25T03:00:00Z");
        when(repository.assigned(1L, eventId, 1L, ref, 7L, dueAt)).thenReturn(true);

        consumer.consume(event(
                eventId,
                IdentityGovernanceWorkItemConsumer.ASSIGNED,
                1L,
                ref,
                7L,
                dueAt,
                "PENDING",
                "ACTIVE",
                0L));

        verify(repository).assigned(1L, eventId, 1L, ref, 7L, dueAt);
    }

    @Test
    void decidedEventKeepsAggregateSequenceIndependentFromTheObjectVersion() {
        UUID eventId = UUID.randomUUID();
        UUID ref = UUID.randomUUID();
        Instant dueAt = Instant.parse("2026-08-25T03:00:00Z");
        when(repository.decided(1L, eventId, 2L, ref, "REVOKE")).thenReturn(true);

        consumer.consume(event(
                eventId,
                IdentityGovernanceWorkItemConsumer.DECIDED,
                2L,
                ref,
                7L,
                dueAt,
                "REVOKE",
                "ACTIVE",
                41L));

        verify(repository).decided(1L, eventId, 2L, ref, "REVOKE");
    }

    @Test
    void tenantOrAggregateReferenceCannotBeSmuggledInThePayload() {
        UUID ref = UUID.randomUUID();
        DomainEventEnvelope event = event(
                UUID.randomUUID(),
                IdentityGovernanceWorkItemConsumer.ASSIGNED,
                1L,
                ref,
                7L,
                Instant.parse("2026-08-25T03:00:00Z"),
                "PENDING",
                "ACTIVE",
                0L);
        DomainEventEnvelope mismatched = new DomainEventEnvelope(
                event.specVersion(), event.id(), event.source(), event.type(),
                event.schemaVersion(), event.time(), event.subject(), event.tenantId(),
                event.aggregateType(), UUID.randomUUID().toString(), event.aggregateSequence(),
                event.correlationId(), event.causationId(), event.traceParent(),
                event.data(), event.extensions());

        assertThatThrownBy(() -> consumer.consume(mismatched))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assignmentMustStartAtSequenceOneAndObjectVersionZero() {
        UUID ref = UUID.randomUUID();

        assertThatThrownBy(() -> consumer.consume(event(
                UUID.randomUUID(),
                IdentityGovernanceWorkItemConsumer.ASSIGNED,
                2L,
                ref,
                7L,
                Instant.parse("2026-08-25T03:00:00Z"),
                "PENDING",
                "ACTIVE",
                0L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sharedTopicPrefilterSkipsUnrelatedTypesAndSourcesBeforeTheInbox() throws Exception {
        DomainEventEnvelope base = event(
                UUID.randomUUID(), IdentityGovernanceWorkItemConsumer.ASSIGNED, 1L,
                UUID.randomUUID(), 7L, Instant.parse("2026-08-25T03:00:00Z"),
                "PENDING", "ACTIVE", 0L);
        DomainEventEnvelope unrelatedType = copy(base, base.source(), "workplace.booking.created.v1");
        DomainEventEnvelope unrelatedSource = copy(
                base, "urn:dwp:platform:workplace", IdentityGovernanceWorkItemConsumer.ASSIGNED);

        consumer.onMessage(objectMapper.writeValueAsString(unrelatedType));
        consumer.onMessage(objectMapper.writeValueAsString(unrelatedSource));

        verifyNoInteractions(idempotent, repository);
    }

    @Test
    void duplicateDeliveryIsAcknowledgedWithoutASecondProjection() throws Exception {
        doReturn(delivery(IdempotentDomainEventConsumer.DeliveryState.DUPLICATE))
                .when(idempotent).consume(any(), any());

        consumer.onMessage(objectMapper.writeValueAsString(event(
                UUID.randomUUID(), IdentityGovernanceWorkItemConsumer.ASSIGNED, 1L,
                UUID.randomUUID(), 7L, Instant.parse("2026-08-25T03:00:00Z"),
                "PENDING", "ACTIVE", 0L)));

        verifyNoInteractions(repository);
    }

    @Test
    void outOfOrderDeliveryRequestsTransportRedeliveryWithoutProjection() throws Exception {
        doReturn(delivery(IdempotentDomainEventConsumer.DeliveryState.OUT_OF_ORDER))
                .when(idempotent).consume(any(), any());
        String payload = objectMapper.writeValueAsString(event(
                UUID.randomUUID(), IdentityGovernanceWorkItemConsumer.DECIDED, 2L,
                UUID.randomUUID(), 7L, Instant.parse("2026-08-25T03:00:00Z"),
                "APPROVE", "ACTIVE", 7L));

        assertThatThrownBy(() -> consumer.onMessage(payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OUT_OF_ORDER");
        verifyNoInteractions(repository);
    }

    private IdempotentDomainEventConsumer.DeliveryResult delivery(
            IdempotentDomainEventConsumer.DeliveryState state) {
        return new IdempotentDomainEventConsumer.DeliveryResult(state, 1, null);
    }

    private DomainEventEnvelope copy(
            DomainEventEnvelope event,
            String source,
            String type) {
        return new DomainEventEnvelope(
                event.specVersion(), event.id(), source, type, event.schemaVersion(),
                event.time(), event.subject(), event.tenantId(), event.aggregateType(),
                event.aggregateId(), event.aggregateSequence(), event.correlationId(),
                event.causationId(), event.traceParent(), event.data(), event.extensions());
    }

    private DomainEventEnvelope event(
            UUID eventId,
            String type,
            long sequence,
            UUID ref,
            Long reviewer,
            Instant dueAt,
            String decision,
            String assignmentState,
            long objectVersion) {
        ObjectNode data = objectMapper.createObjectNode()
                .put("workItemRef", ref.toString())
                .put("reviewerUserId", reviewer)
                .put("dueAt", dueAt.toString())
                .put("decision", decision)
                .put("assignmentState", assignmentState)
                .put("objectVersion", objectVersion);
        return new DomainEventEnvelope(
                "1.0",
                eventId,
                IdentityGovernanceWorkItemConsumer.SOURCE,
                type,
                1,
                Instant.parse("2026-08-24T03:00:00Z"),
                IdentityGovernanceWorkItemConsumer.AGGREGATE + '/' + ref,
                1L,
                IdentityGovernanceWorkItemConsumer.AGGREGATE,
                ref.toString(),
                sequence,
                "corr-1",
                null,
                null,
                data,
                Map.of());
    }
}
