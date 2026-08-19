package com.dwp.services.notification.integration;

import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;
import com.dwp.services.notification.security.NotificationRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class ApprovalNotificationEventTranslator {

    static final String EVENT_ID_HEADER = "dwp-event-id";
    static final String EVENT_TYPE_HEADER = "dwp-event-type";
    static final String TENANT_ID_HEADER = "dwp-tenant-id";
    static final String SOURCE_SERVICE = "dwp-approval-server";

    private static final Map<String, Mapping> MAPPINGS = Map.of(
            "approval.task.assigned",
            new Mapping(
                    "APPROVAL.ACTION_REQUIRED",
                    "dwp.approval.task.assigned",
                    "ASSIGNED",
                    true),
            "approval.request.submitted",
            new Mapping(
                    "APPROVAL.REQUEST_SUBMITTED",
                    "approval.request.submitted",
                    "SUBMITTED",
                    false),
            "approval.request.approved",
            new Mapping(
                    "APPROVAL.REQUEST_APPROVED",
                    "approval.request.approved",
                    "APPROVED",
                    false),
            "approval.request.rejected",
            new Mapping(
                    "APPROVAL.REQUEST_REJECTED",
                    "approval.request.rejected",
                    "REJECTED",
                    false));

    private final ObjectMapper objectMapper;
    private final Set<String> allowedEventTypes;

    public ApprovalNotificationEventTranslator(
            ObjectMapper objectMapper,
            @Value("${dwp.notification.approval-pilot.allowed-event-types:"
                    + "approval.task.assigned,approval.request.submitted,"
                    + "approval.request.approved,approval.request.rejected}")
            String allowedEventTypes) {
        this.objectMapper = objectMapper;
        this.allowedEventTypes = parseAllowlist(allowedEventTypes);
    }

    public Translation translate(ConsumerRecord<String, String> record) {
        UUID eventId = uuid(requiredHeader(record.headers(), EVENT_ID_HEADER), "event ID");
        String headerEventType = requiredHeader(record.headers(), EVENT_TYPE_HEADER);
        long headerTenantId = positiveLong(
                requiredHeader(record.headers(), TENANT_ID_HEADER), "tenant header");
        JsonNode root = object(record.value());
        requireText(root, "specVersion", 20, true, "1.0");
        String eventType = requireText(root, "eventType", 200, true, null);
        long tenantId = requirePositiveLong(root, "tenantId");

        if (tenantId != headerTenantId) {
            throw permanent(
                    ApprovalNotificationEventException.Classification.TENANT_MISMATCH,
                    "Approval event tenant header does not match its payload.");
        }
        if (!headerEventType.equals(eventType)) {
            throw permanent(
                    ApprovalNotificationEventException.Classification.EVENT_TYPE_MISMATCH,
                    "Approval event type header does not match its payload.");
        }
        Mapping mapping = MAPPINGS.get(eventType);
        if (mapping == null || !allowedEventTypes.contains(eventType)) {
            throw permanent(
                    ApprovalNotificationEventException.Classification.EVENT_TYPE_NOT_ALLOWED,
                    "Approval event type is not allowlisted for notification materialization.");
        }

        UUID requestId = uuid(
                requireText(root, "requestId", 36, true, null), "request ID");
        JsonNode payload = root.get("payload");
        if (payload == null || !payload.isObject()) {
            throw contract("Approval event payload must be an object.");
        }
        long recipientUserId = requirePositiveLong(payload, "recipientUserId");
        String requestTitle = requireText(payload, "requestTitle", 300, true, null);
        validateDecision(payload, mapping.decision());

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("requestId", requestId.toString());
        variables.put("requestTitle", requestTitle);
        variables.put("decision", mapping.decision());
        DirectMaterializationRequest request = new DirectMaterializationRequest(
                eventId,
                mapping.sourceEventType(),
                1,
                mapping.typeKey(),
                java.util.List.of(recipientUserId),
                "approval-request:" + requestId,
                "ko-KR",
                "DIRECT",
                null,
                "approval-request:" + requestId,
                "/approvals/requests/" + requestId,
                optionalInstant(root, "occurredAt"),
                null,
                mapping.actionRequired(),
                Map.copyOf(variables));
        NotificationRequestContext.Actor actor = new NotificationRequestContext.Actor(
                tenantId,
                null,
                Set.of(),
                Set.of(),
                true,
                SOURCE_SERVICE);
        return new Translation(actor, request, optionalText(root, "correlationId", 160));
    }

    private Set<String> parseAllowlist(String value) {
        Set<String> parsed = value == null || value.isBlank()
                ? Set.of()
                : Arrays.stream(value.split(","))
                        .map(String::trim)
                        .filter(item -> !item.isBlank())
                        .collect(Collectors.toUnmodifiableSet());
        if (!MAPPINGS.keySet().containsAll(parsed)) {
            throw new IllegalArgumentException(
                    "Approval notification allowlist contains an unmapped event type.");
        }
        return parsed;
    }

    private JsonNode object(String value) {
        try {
            JsonNode root = objectMapper.readTree(value);
            if (root == null || !root.isObject()) {
                throw permanent(
                        ApprovalNotificationEventException.Classification.MALFORMED,
                        "Approval event must be a JSON object.");
            }
            return root;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            if (exception instanceof ApprovalNotificationEventException classified) {
                throw classified;
            }
            throw new ApprovalNotificationEventException(
                    ApprovalNotificationEventException.Classification.MALFORMED,
                    "Approval event contains malformed JSON.",
                    exception);
        }
    }

    private String requiredHeader(Headers headers, String name) {
        Iterator<Header> iterator = headers.headers(name).iterator();
        if (!iterator.hasNext()) throw malformed("Missing Kafka header: " + name);
        Header header = iterator.next();
        if (iterator.hasNext()) throw malformed("Duplicate Kafka header: " + name);
        if (header.value() == null) throw malformed("Empty Kafka header: " + name);
        String value = new String(header.value(), StandardCharsets.UTF_8).trim();
        if (value.isBlank() || value.length() > 200 || containsControl(value)) {
            throw malformed("Invalid Kafka header: " + name);
        }
        return value;
    }

    private String requireText(
            JsonNode parent,
            String field,
            int maximumLength,
            boolean required,
            String exactValue) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            if (!required) return null;
            throw contract("Approval event is missing " + field + ".");
        }
        if (!value.isTextual()) throw contract("Approval event " + field + " must be text.");
        String normalized = value.textValue().trim();
        if ((required && normalized.isBlank())
                || normalized.length() > maximumLength
                || containsControl(normalized)
                || (exactValue != null && !exactValue.equals(normalized))) {
            throw contract("Approval event " + field + " is invalid.");
        }
        return normalized;
    }

    private String optionalText(JsonNode parent, String field, int maximumLength) {
        return requireText(parent, field, maximumLength, false, null);
    }

    private long requirePositiveLong(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw contract("Approval event " + field + " must be an integer.");
        }
        long parsed = value.longValue();
        if (parsed <= 0) throw contract("Approval event " + field + " must be positive.");
        return parsed;
    }

    private long positiveLong(String value, String label) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0) return parsed;
        } catch (NumberFormatException ignored) {
            // Converted to a classified permanent contract failure below.
        }
        throw malformed("Approval event " + label + " is invalid.");
    }

    private UUID uuid(String value, String label) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw malformed("Approval event " + label + " is invalid.");
        }
    }

    private Instant optionalInstant(JsonNode parent, String field) {
        String value = optionalText(parent, field, 40);
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw contract("Approval event " + field + " is invalid.");
        }
    }

    private void validateDecision(JsonNode payload, String expected) {
        JsonNode supplied = payload.get("decision");
        if (supplied == null || supplied.isNull()) return;
        if (!supplied.isTextual() || !expected.equalsIgnoreCase(supplied.textValue().trim())) {
            throw contract("Approval event decision contradicts its event type.");
        }
    }

    private boolean containsControl(String value) {
        return value.chars().anyMatch(character -> character < 32 || character == 127);
    }

    private ApprovalNotificationEventException malformed(String message) {
        return permanent(ApprovalNotificationEventException.Classification.MALFORMED, message);
    }

    private ApprovalNotificationEventException contract(String message) {
        return permanent(
                ApprovalNotificationEventException.Classification.PAYLOAD_CONTRACT_VIOLATION,
                message);
    }

    private ApprovalNotificationEventException permanent(
            ApprovalNotificationEventException.Classification classification,
            String message) {
        return new ApprovalNotificationEventException(classification, message);
    }

    public record Translation(
            NotificationRequestContext.Actor actor,
            DirectMaterializationRequest request,
            String correlationId) {
    }

    private record Mapping(
            String typeKey,
            String sourceEventType,
            String decision,
            boolean actionRequired) {
    }
}
