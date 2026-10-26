package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class IntegrationDispatchWorkerTest {

    private static final long TENANT = 71L;
    private static final UUID EXECUTION = UUID.fromString(
            "82000000-0000-4000-8000-000000000001");
    private static final UUID EVENT = UUID.fromString(
            "82000000-0000-4000-8000-000000000002");
    private static final UUID ORIGINAL_SUBJECT = UUID.fromString(
            "82000000-0000-4000-8000-000000000004");
    private static final String CORRELATION =
            "82000000-0000-4000-8000-000000000003";

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private IntegrationRuntimeService runtime;
    private IntegrationDispatchWorker worker;

    @BeforeEach
    void setUp() {
        DomainEventContractRegistry contracts = new DomainEventContractRegistry();
        new Sys008EventContract(contracts);
        runtime = mock(IntegrationRuntimeService.class);
        worker = new IntegrationDispatchWorker(json, contracts, runtime);
    }

    @Test
    void acceptedExactEnvelopeAndPartitionInvokesInternalDispatchOnce()
            throws Exception {
        DomainEventEnvelope event = event("ACCEPTED", EXECUTION.toString());

        worker.onMessage(record(partitionKey(event), json.writeValueAsBytes(event)));

        verify(runtime).dispatchAccepted(TENANT, EXECUTION, ORIGINAL_SUBJECT);
        verifyNoMoreInteractions(runtime);
    }

    @Test
    void nonAcceptedSys008StateIsConsumedWithoutDispatch() throws Exception {
        DomainEventEnvelope event = event("ACKNOWLEDGED", EXECUTION.toString());

        worker.onMessage(record(partitionKey(event), json.writeValueAsBytes(event)));

        verifyNoInteractions(runtime);
    }

    @Test
    void unrelatedEventIsIgnoredWithoutRegisteringOrDispatching() throws Exception {
        DomainEventEnvelope unrelated = new DomainEventEnvelope(
                "1.0", EVENT, "urn:dwp:unrelated", "dwp.unrelated.changed.v1",
                1, Instant.parse("2026-10-26T07:00:00Z"),
                "UNRELATED/" + EXECUTION, TENANT, "UNRELATED",
                EXECUTION.toString(), 1, CORRELATION, null, null,
                json.createObjectNode().put("unrelated", true), Map.of());

        worker.onMessage(record(
                partitionKey(unrelated), json.writeValueAsBytes(unrelated)));

        verifyNoInteractions(runtime);
    }

    @Test
    void wrongPartitionKeyIsRejectedBeforeDispatch() throws Exception {
        DomainEventEnvelope event = event("ACCEPTED", EXECUTION.toString());

        assertThatThrownBy(() -> worker.onMessage(
                record("71|wrong|partition|key", json.writeValueAsBytes(event))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SYS008_DISPATCH_EVENT_INVALID");
        verifyNoInteractions(runtime);
    }

    @Test
    void unknownPayloadFieldIsRejectedBeforeDispatch() throws Exception {
        DomainEventEnvelope event = event("ACCEPTED", EXECUTION.toString());
        ObjectNode envelope = json.valueToTree(event);
        ((ObjectNode) envelope.get("data")).put("unexpected", "forbidden");

        assertThatThrownBy(() -> worker.onMessage(record(
                partitionKey(event), json.writeValueAsBytes(envelope))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SYS008_DISPATCH_EVENT_INVALID");
        verifyNoInteractions(runtime);
    }

    @Test
    void aggregateAndPayloadIdentityMismatchIsRejectedBeforeDispatch()
            throws Exception {
        UUID different = UUID.fromString(
                "82000000-0000-4000-8000-000000000009");
        DomainEventEnvelope event = event("ACCEPTED", different.toString());

        assertThatThrownBy(() -> worker.onMessage(
                record(partitionKey(event), json.writeValueAsBytes(event))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SYS008_DISPATCH_EVENT_INVALID");
        verifyNoInteractions(runtime);
    }

    @Test
    void nonUuidOriginalSubjectIsRejectedBeforeDispatch() throws Exception {
        DomainEventEnvelope valid = event("ACCEPTED", EXECUTION.toString());
        DomainEventEnvelope invalid = new DomainEventEnvelope(
                valid.specVersion(), valid.id(), valid.source(), valid.type(),
                valid.schemaVersion(), valid.time(), "not-a-principal",
                valid.tenantId(), valid.aggregateType(), valid.aggregateId(),
                valid.aggregateSequence(), valid.correlationId(),
                valid.causationId(), valid.traceParent(), valid.data(),
                valid.extensions());

        assertThatThrownBy(() -> worker.onMessage(record(
                partitionKey(invalid), json.writeValueAsBytes(invalid))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SYS008_DISPATCH_EVENT_INVALID");
        verifyNoInteractions(runtime);
    }

    private DomainEventEnvelope event(String state, String aggregateId) {
        Sys008EventContract.Payload payload = new Sys008EventContract.Payload(
                EXECUTION, state, 2, 0, 0, 0, null);
        return new DomainEventEnvelope(
                "1.0", EVENT, Sys008EventContract.SOURCE,
                Sys008EventContract.EVENT_TYPE, 1,
                Instant.parse("2026-10-26T07:00:00Z"),
                ORIGINAL_SUBJECT.toString(),
                TENANT, Sys008EventContract.AGGREGATE_TYPE, aggregateId,
                1, CORRELATION, null, null,
                json.valueToTree(payload), Map.of());
    }

    private static String partitionKey(DomainEventEnvelope event) {
        return event.tenantId() + "|" + event.source() + "|"
                + event.aggregateType() + "|" + event.aggregateId();
    }

    private static ConsumerRecord<String, byte[]> record(
            String key, byte[] value) {
        return new ConsumerRecord<>(
                "dwp.domain-events.v1", 0, 0L, key, value);
    }
}
