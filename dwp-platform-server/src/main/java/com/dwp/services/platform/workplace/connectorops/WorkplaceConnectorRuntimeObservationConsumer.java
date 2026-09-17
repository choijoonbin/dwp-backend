package com.dwp.services.platform.workplace.connectorops;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos.*;

/** Provider-only adapter for runtime observations; there is deliberately no HTTP write route. */
@Component
@ConditionalOnProperty(
        name = "dwp.workplace.connector-runtime.observation-consumer-enabled",
        havingValue = "true")
public class WorkplaceConnectorRuntimeObservationConsumer {
    static final String TYPE = "workplace.connector.runtime-observed.v1";
    static final String AGGREGATE = "WORKPLACE_CONNECTOR";
    private static final Set<String> FIELDS = Set.of(
            "provider", "configurationVersion", "adapterId", "adapterVersion",
            "reportedState", "capabilities", "sourceObservedAt", "lastSuccessAt",
            "lagSeconds", "checkpointReference", "retryQueueDepth",
            "deadLetterQueueDepth", "errorCode", "payloadFingerprint");

    private final ObjectMapper objectMapper;
    private final DomainEventContractRegistry contracts;
    private final WorkplaceConnectorOpsService service;

    public WorkplaceConnectorRuntimeObservationConsumer(
            ObjectMapper objectMapper,
            DomainEventContractRegistry contracts,
            WorkplaceConnectorOpsService service) {
        this.objectMapper = objectMapper;
        this.contracts = contracts;
        this.service = service;
        contracts.register(TYPE, 1, 1);
    }

    @KafkaListener(
            topics = "${dwp.workplace.connector-runtime.observation-topic:dwp.provider-workplace-connector-runtime.v1}",
            groupId = "${dwp.workplace.connector-runtime.observation-group-id:dwp-platform-workplace-connector-runtime}")
    public void onMessage(String payload) {
        observe(parse(payload));
    }

    boolean observe(DomainEventEnvelope event) {
        contracts.requireCompatible(event);
        ProviderObservation observation = validate(event);
        return service.observeProvider(observation);
    }

    private ProviderObservation validate(DomainEventEnvelope event) {
        if (!TYPE.equals(event.type()) || !AGGREGATE.equals(event.aggregateType())
                || event.tenantId() == null || event.tenantId() <= 0
                || event.data() == null || !event.data().isObject()) {
            throw invalid();
        }
        Set<String> fields = new HashSet<>();
        event.data().fieldNames().forEachRemaining(fields::add);
        if (!FIELDS.equals(fields)) throw invalid();
        JsonNode data = event.data();
        ConnectorKind kind = enumValue(ConnectorKind.class, event.aggregateId());
        String provider = text(data, "provider");
        List<Capability> capabilities = new ArrayList<>();
        JsonNode capabilityValues = data.get("capabilities");
        if (capabilityValues == null || !capabilityValues.isArray()) throw invalid();
        capabilityValues.forEach(value -> capabilities.add(
                enumValue(Capability.class, textual(value))));
        if (new HashSet<>(capabilities).size() != capabilities.size()) throw invalid();
        return new ProviderObservation(event.id(), event.tenantId(), kind, event.source(), provider,
                nonNegativeLong(data.get("configurationVersion")), text(data, "adapterId"),
                text(data, "adapterVersion"), enumValue(ProviderReportedState.class,
                        text(data, "reportedState")), List.copyOf(capabilities),
                OffsetDateTime.parse(text(data, "sourceObservedAt")),
                OffsetDateTime.now(ZoneOffset.UTC), nullableInstant(data.get("lastSuccessAt")),
                nullableNonNegativeLong(data.get("lagSeconds")), nullableText(data.get("checkpointReference")),
                nullableNonNegativeLong(data.get("retryQueueDepth")),
                nullableNonNegativeLong(data.get("deadLetterQueueDepth")),
                nullableText(data.get("errorCode")), event.aggregateSequence(),
                text(data, "payloadFingerprint"));
    }

    private DomainEventEnvelope parse(String payload) {
        try {
            return objectMapper.readerFor(DomainEventEnvelope.class)
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION).readValue(payload);
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw invalid();
        return value.textValue();
    }

    private static String textual(JsonNode value) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw invalid();
        return value.textValue();
    }

    private static String nullableText(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.textValue().isBlank()) throw invalid();
        return value.textValue();
    }

    private static OffsetDateTime nullableInstant(JsonNode value) {
        String text = nullableText(value);
        return text == null ? null : OffsetDateTime.parse(text);
    }

    private static long nonNegativeLong(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 0) throw invalid();
        return value.longValue();
    }

    private static Long nullableNonNegativeLong(JsonNode value) {
        if (value == null || value.isNull()) return null;
        return nonNegativeLong(value);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid Workplace connector runtime observation.");
    }
}
