package com.dwp.services.notification.integration;

import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;
import com.dwp.services.notification.domain.NotificationTargetLifecycleService.TargetChange;
import com.dwp.services.notification.security.NotificationRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class NotificationDomainEventTranslator {

    private static final int MAXIMUM_INTENTS = 20;
    private static final int MAXIMUM_RECIPIENTS = 100;
    private static final int MAXIMUM_VARIABLES = 50;
    private static final int MAXIMUM_TARGET_CHANGES = 100;
    private static final Pattern TYPE_KEY = Pattern.compile("[A-Z][A-Z0-9_.-]{2,159}");
    private static final Pattern VARIABLE_KEY =
            Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,79}");

    private final ObjectMapper objectMapper;
    private final Map<String, String> producerSources;

    public NotificationDomainEventTranslator(
            ObjectMapper objectMapper,
            @Value("${dwp.notification.domain-events.producer-sources:"
                    + "urn:dwp:messaging=dwp-messaging-server}") String producerSources) {
        this.objectMapper = objectMapper;
        this.producerSources = parseProducerSources(producerSources);
    }

    public List<Translation> translate(String value) {
        return translateBatch(value).notifications();
    }

    public TranslationBatch translateBatch(String value) {
        DomainEventEnvelope event = envelope(value);
        JsonNode intents = event.data().get("notificationIntents");
        JsonNode targetChanges = event.data().get("notificationTargetChanges");
        boolean hasIntents = intents != null && !intents.isNull();
        boolean hasTargetChanges = targetChanges != null && !targetChanges.isNull();
        if (!hasIntents && !hasTargetChanges) {
            return new TranslationBatch(List.of(), List.of());
        }
        if (hasIntents
                && (!intents.isArray() || intents.isEmpty() || intents.size() > MAXIMUM_INTENTS)) {
            throw contract("notificationIntents must contain between 1 and 20 entries.");
        }
        if (hasTargetChanges && (!targetChanges.isArray()
                || targetChanges.isEmpty()
                || targetChanges.size() > MAXIMUM_TARGET_CHANGES)) {
            throw contract("notificationTargetChanges must contain between 1 and 100 entries.");
        }
        validateEnvelope(event);
        String producer = producerSources.get(event.source());
        if (producer == null) {
            throw contract("The domain-event producer source is not notification-onboarded.");
        }

        NotificationRequestContext.Actor actor = new NotificationRequestContext.Actor(
                event.tenantId(), null, Set.of(), Set.of(), true, producer);
        List<Translation> translations = new ArrayList<>();
        Set<String> typeKeys = new LinkedHashSet<>();
        if (hasIntents) {
            for (JsonNode intent : intents) {
                if (!intent.isObject()) {
                    throw contract("Each notification intent must be an object.");
                }
                String typeKey = text(intent, "typeKey", 160, true);
                if (!TYPE_KEY.matcher(typeKey).matches() || !typeKeys.add(typeKey)) {
                    throw contract("Notification type keys must be valid and unique per event.");
                }
                DirectMaterializationRequest request = new DirectMaterializationRequest(
                        event.id(),
                        event.type(),
                        event.schemaVersion(),
                        typeKey,
                        recipients(intent.get("recipientUserIds")),
                        text(intent, "threadKey", 200, false),
                        defaultText(intent, "locale", "ko-KR", 35),
                        defaultText(intent, "reasonCode", "DIRECT", 200),
                        text(intent, "actorReference", 300, false),
                        text(intent, "subjectReference", 300, false),
                        text(intent, "targetReference", 300, false),
                        event.time(),
                        instant(intent, "dueAt"),
                        booleanValue(intent, "actionRequired"),
                        variables(intent.get("variables")));
                translations.add(new Translation(actor, request, event.correlationId()));
            }
        }
        List<TargetChangeTranslation> changes = new ArrayList<>();
        if (hasTargetChanges) {
            for (JsonNode change : targetChanges) {
                if (!change.isObject()) {
                    throw contract("Each notification target change must be an object.");
                }
                changes.add(new TargetChangeTranslation(
                        actor,
                        new TargetChange(
                                text(change, "ownerAppKey", 80, true),
                                text(change, "targetReference", 300, true),
                                text(change, "state", 20, true),
                                text(change, "reason", 500, true)),
                        event.correlationId()));
            }
        }
        return new TranslationBatch(List.copyOf(translations), List.copyOf(changes));
    }

    private DomainEventEnvelope envelope(String value) {
        try {
            return objectMapper.readValue(value, DomainEventEnvelope.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new NotificationDomainEventException(
                    "The canonical domain event is malformed.", exception);
        }
    }

    private void validateEnvelope(DomainEventEnvelope event) {
        if (!"1.0".equals(event.specVersion())
                || event.id() == null
                || event.time() == null
                || event.tenantId() == null
                || event.tenantId() < 1
                || blank(event.source())
                || blank(event.type())
                || event.schemaVersion() < 1
                || blank(event.aggregateType())
                || blank(event.aggregateId())
                || event.aggregateSequence() < 1
                || blank(event.correlationId())) {
            throw contract("The canonical domain-event envelope is incomplete.");
        }
    }

    private List<Long> recipients(JsonNode node) {
        if (node == null || !node.isArray()
                || node.isEmpty() || node.size() > MAXIMUM_RECIPIENTS) {
            throw contract("recipientUserIds must contain between 1 and 100 users.");
        }
        Set<Long> recipients = new LinkedHashSet<>();
        node.forEach(value -> {
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) {
                throw contract("Notification recipients must be positive user IDs.");
            }
            recipients.add(value.longValue());
        });
        if (recipients.isEmpty()) throw contract("At least one recipient is required.");
        return List.copyOf(recipients);
    }

    private Map<String, Object> variables(JsonNode node) {
        if (node == null || !node.isObject() || node.size() > MAXIMUM_VARIABLES) {
            throw contract("Notification variables must be an object with at most 50 entries.");
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (!VARIABLE_KEY.matcher(key).matches()) {
                throw contract("A notification variable key is invalid.");
            }
            if (value == null || value.isNull()) {
                variables.put(key, null);
            } else if (value.isBoolean()) {
                variables.put(key, value.booleanValue());
            } else if (value.isIntegralNumber()) {
                variables.put(key, value.longValue());
            } else if (value.isFloatingPointNumber()) {
                variables.put(key, value.decimalValue());
            } else if (value.isTextual() && safe(value.textValue(), 500)) {
                variables.put(key, value.textValue().trim());
            } else {
                throw contract("Notification variables must contain only safe scalar values.");
            }
        });
        return Collections.unmodifiableMap(variables);
    }

    private String text(JsonNode parent, String field, int maximumLength, boolean required) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            if (required) throw contract("Notification intent is missing " + field + '.');
            return null;
        }
        if (!value.isTextual() || !safe(value.textValue(), maximumLength)) {
            throw contract("Notification intent " + field + " is invalid.");
        }
        String normalized = value.textValue().trim();
        if (required && normalized.isBlank()) {
            throw contract("Notification intent " + field + " is required.");
        }
        return normalized.isBlank() ? null : normalized;
    }

    private String defaultText(
            JsonNode parent, String field, String defaultValue, int maximumLength) {
        String value = text(parent, field, maximumLength, false);
        return value == null ? defaultValue : value;
    }

    private boolean booleanValue(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return false;
        if (!value.isBoolean()) throw contract("Notification intent " + field + " must be boolean.");
        return value.booleanValue();
    }

    private Instant instant(JsonNode parent, String field) {
        String value = text(parent, field, 40, false);
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw contract("Notification intent " + field + " must be an ISO-8601 instant.");
        }
    }

    private Map<String, String> parseProducerSources(String value) {
        Map<String, String> sources = new LinkedHashMap<>();
        Arrays.stream(value == null ? new String[0] : value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .forEach(item -> {
                    int separator = item.indexOf('=');
                    if (separator < 1 || separator == item.length() - 1) {
                        throw new IllegalArgumentException(
                                "Notification producer sources must use source=service entries.");
                    }
                    String source = item.substring(0, separator).trim();
                    String service = item.substring(separator + 1).trim();
                    if (!source.startsWith("urn:dwp:")
                            || !service.matches("dwp-[a-z0-9-]+-server")) {
                        throw new IllegalArgumentException(
                                "A notification producer source mapping is invalid.");
                    }
                    if (sources.putIfAbsent(source, service) != null) {
                        throw new IllegalArgumentException(
                                "Notification producer sources must be unique.");
                    }
                });
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("At least one notification producer is required.");
        }
        return Map.copyOf(sources);
    }

    private boolean safe(String value, int maximumLength) {
        return value != null
                && value.length() <= maximumLength
                && value.chars().noneMatch(character -> character < 32 && character != '\n' && character != '\t');
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private NotificationDomainEventException contract(String message) {
        return new NotificationDomainEventException(message);
    }

    public record Translation(
            NotificationRequestContext.Actor actor,
            DirectMaterializationRequest request,
            String correlationId) {
    }

    public record TargetChangeTranslation(
            NotificationRequestContext.Actor actor,
            TargetChange change,
            String correlationId) {
    }

    public record TranslationBatch(
            List<Translation> notifications,
            List<TargetChangeTranslation> targetChanges) {
    }
}
