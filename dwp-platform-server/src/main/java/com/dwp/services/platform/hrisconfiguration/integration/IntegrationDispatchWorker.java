package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/** Internal recovery-capable consumer for accepted SYS-008 executions. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.integration-control-plane.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class IntegrationDispatchWorker {

    static final String GROUP = "dwp-platform-sys008-dispatch-v1";
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "specVersion", "id", "source", "type", "schemaVersion", "time",
            "subject", "tenantId", "aggregateType", "aggregateId",
            "aggregateSequence", "correlationId", "causationId", "traceParent",
            "data", "extensions");
    private static final Set<String> PAYLOAD_FIELDS = Set.of(
            "executionPublicId", "state", "sourceCount", "acceptedCount",
            "rejectedCount", "quarantinedCount", "reconciliationDigest");

    private final ObjectMapper json;
    private final DomainEventContractRegistry contracts;
    private final IntegrationRuntimeService runtime;

    IntegrationDispatchWorker(
            ObjectMapper json,
            DomainEventContractRegistry contracts,
            IntegrationRuntimeService runtime) {
        this.json = json.copy()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.contracts = contracts;
        this.runtime = runtime;
    }

    @KafkaListener(
            topics = "dwp.domain-events.v1",
            groupId = GROUP,
            properties = {
                    "key.deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                    "value.deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer"
            })
    void onMessage(ConsumerRecord<String, byte[]> record) {
        DomainEventEnvelope event = parse(record.value());
        if (!Sys008EventContract.EVENT_TYPE.equals(event.type())) return;
        contracts.requireCompatible(event);
        requirePartitionKey(record.key(), event);
        Sys008EventContract.Payload payload = validate(event);
        if ("ACCEPTED".equals(payload.state())) {
            runtime.dispatchAccepted(event.tenantId(), payload.executionPublicId(),
                    originalSubject(event));
        }
    }

    private DomainEventEnvelope parse(byte[] bytes) {
        try {
            String payload = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            JsonNode tree = json.readTree(payload);
            if (tree == null || !tree.isObject()) throw invalid();
            JsonNode type = tree.get("type");
            if (type == null || !type.isTextual()) throw invalid();
            if (Sys008EventContract.EVENT_TYPE.equals(type.textValue())
                    && !fields(tree).equals(ENVELOPE_FIELDS)) {
                throw invalid();
            }
            return json.treeToValue(tree, DomainEventEnvelope.class);
        } catch (CharacterCodingException exception) {
            throw invalid();
        } catch (java.io.IOException | RuntimeException exception) {
            if (exception instanceof IllegalArgumentException invalid
                    && "SYS008_DISPATCH_EVENT_INVALID".equals(
                    invalid.getMessage())) {
                throw invalid;
            }
            throw invalid();
        }
    }

    private Sys008EventContract.Payload validate(DomainEventEnvelope event) {
        if (!"1.0".equals(event.specVersion())
                || event.tenantId() == null || event.tenantId() < 1
                || !Sys008EventContract.SOURCE.equals(event.source())
                || !Sys008EventContract.AGGREGATE_TYPE.equals(
                    event.aggregateType())
                || event.schemaVersion() != 1 || event.aggregateSequence() < 1
                || event.extensions() == null || !event.extensions().isEmpty()
                || event.data() == null || !event.data().isObject()
                || !fields(event.data()).equals(PAYLOAD_FIELDS)) {
            throw invalid();
        }
        try {
            Sys008EventContract.Payload payload = json.treeToValue(
                    event.data(), Sys008EventContract.Payload.class);
            if (!payload.executionPublicId().toString()
                    .equals(event.aggregateId())) {
                throw invalid();
            }
            return payload;
        } catch (java.io.IOException | RuntimeException exception) {
            throw invalid();
        }
    }

    private static void requirePartitionKey(
            String actual, DomainEventEnvelope event) {
        String expected = event.tenantId() + "|" + event.source() + "|"
                + event.aggregateType() + "|" + event.aggregateId();
        if (!expected.equals(actual)) throw invalid();
    }

    private static UUID originalSubject(DomainEventEnvelope event) {
        try {
            UUID subject = UUID.fromString(event.subject());
            if (!subject.toString().equals(event.subject())
                    || subject.equals(new UUID(0L, 0L))) {
                throw invalid();
            }
            return subject;
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    private static Set<String> fields(JsonNode object) {
        java.util.HashSet<String> fields = new java.util.HashSet<>();
        object.fieldNames().forEachRemaining(fields::add);
        return Set.copyOf(fields);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("SYS008_DISPATCH_EVENT_INVALID");
    }
}
