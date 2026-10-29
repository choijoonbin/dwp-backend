package com.dwp.services.notification.integration;

import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;
import com.dwp.services.notification.domain.NotificationModels.MaterializationContext;
import com.dwp.services.notification.domain.NotificationModels.MaterializationContextKind;
import com.dwp.services.notification.domain.NotificationStructuredContexts;
import com.dwp.services.notification.domain.NotificationTargetLifecycleService.TargetChange;
import com.dwp.services.notification.security.NotificationRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class NotificationDomainEventTranslator {

    private static final int MAXIMUM_INTENTS = 20;
    private static final int MAXIMUM_RECIPIENTS = 100;
    private static final int MAXIMUM_VARIABLES = 50;
    private static final int MAXIMUM_TARGET_CHANGES = 100;
    private static final Set<String> CONTEXT_FIELDS = Set.of(
            "kind", "key", "displayHint", "matchable");
    private static final String HRIS_EXPERIENCE_SOURCE =
            "urn:dwp:people:hrm:dwp-experience";
    private static final String HRIS_EXPERIENCE_PRODUCER =
            "dwp-people-server";
    private static final String HRIS_EMPLOYEE_CHANGE_APPLIED_EVENT =
            "EmployeeChangeAppliedNotificationRequested.v1";
    private static final String HRIS_EMPLOYEE_CHANGE_TYPE_KEY =
            "HCM.EMPLOYEE_CHANGE_APPLIED";
    private static final String CALENDAR_SOURCE =
            "urn:dwp:platform:calendar";
    private static final String CALENDAR_PRODUCER =
            "dwp-platform-server";
    private static final String CALENDAR_REMINDER_DUE_EVENT =
            "calendar.event-reminder.due.v1";
    private static final Set<String> HRIS_EMPLOYEE_CHANGE_DATA_FIELDS = Set.of(
            "notificationIntents");
    private static final Set<String> HRIS_EMPLOYEE_CHANGE_INTENT_FIELDS = Set.of(
            "typeKey",
            "recipientUserIds",
            "threadKey",
            "locale",
            "reasonCode",
            "actorReference",
            "subjectReference",
            "targetReference",
            "dueAt",
            "actionRequired",
            "variables");
    private static final Set<String> HRIS_EMPLOYEE_CHANGE_VARIABLES = Set.of(
            "effectiveDate", "requestPublicId", "requestReference", "targetType");
    private static final Map<String, ExactSourceEventOnboarding>
            RESERVED_EXACT_SOURCE_EVENT_ONBOARDINGS = Map.of(
                    HRIS_EXPERIENCE_SOURCE,
                    new ExactSourceEventOnboarding(
                            HRIS_EXPERIENCE_PRODUCER,
                            Set.of(HRIS_EMPLOYEE_CHANGE_APPLIED_EVENT)),
                    CALENDAR_SOURCE,
                    new ExactSourceEventOnboarding(
                            CALENDAR_PRODUCER,
                            Set.of(CALENDAR_REMINDER_DUE_EVENT)));
    private static final Pattern TYPE_KEY = Pattern.compile("[A-Z][A-Z0-9_.-]{2,159}");
    private static final Pattern EVENT_TYPE =
            Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,199}");
    private static final Pattern HRIS_LOCALE =
            Pattern.compile("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*");
    private static final Pattern VARIABLE_KEY =
            Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,79}");

    private final ObjectMapper objectMapper;
    private final Map<String, String> producerSources;
    private final Map<String, Set<String>> exactSourceEventTypes;

    @Autowired
    public NotificationDomainEventTranslator(
            ObjectMapper objectMapper,
            @Value("${dwp.notification.domain-events.producer-sources:"
                    + "urn:dwp:messaging=dwp-messaging-server,"
                    + "urn:dwp:platform:mail=dwp-platform-server}") String producerSources,
            @Value("${dwp.notification.domain-events.exact-source-event-onboardings:}")
                    String exactSourceEventOnboardings) {
        this.objectMapper = objectMapper;
        Map<String, String> legacyProducerSources = parseProducerSources(producerSources);
        Map<String, ExactSourceEventOnboarding> onboardings =
                parseExactSourceEventOnboardings(exactSourceEventOnboardings);
        validateExactSourceEventOnboardings(legacyProducerSources, onboardings);
        this.producerSources = effectiveProducerSources(legacyProducerSources, onboardings);
        this.exactSourceEventTypes = exactSourceEventTypes(onboardings);
    }

    public NotificationDomainEventTranslator(
            ObjectMapper objectMapper,
            String producerSources) {
        this(objectMapper, producerSources, "");
    }

    public List<Translation> translate(String value) {
        return translateBatch(value).notifications();
    }

    public TranslationBatch translateBatch(String value) {
        DomainEventEnvelope event = envelope(value);
        validateExactNotificationPayload(event);
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
        validateExactSourceEventPair(event);

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
                        notificationLocale(event, typeKey, intent),
                        defaultText(intent, "reasonCode", "DIRECT", 200),
                        text(intent, "actorReference", 300, false),
                        text(intent, "subjectReference", 300, false),
                        text(intent, "targetReference", 300, false),
                        event.time(),
                        instant(intent, "dueAt"),
                        booleanValue(intent, "actionRequired"),
                        contexts(intent.get("contexts")),
                        notificationVariables(event, typeKey, intent.get("variables")));
                validateExactNotificationProjection(event, request);
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

    private Map<String, Object> notificationVariables(
            DomainEventEnvelope event,
            String typeKey,
            JsonNode node) {
        boolean exactHrisProjection = HRIS_EXPERIENCE_SOURCE.equals(event.source())
                && HRIS_EMPLOYEE_CHANGE_APPLIED_EVENT.equals(event.type())
                && HRIS_EMPLOYEE_CHANGE_TYPE_KEY.equals(typeKey);
        Map<String, Object> variables = exactHrisProjection
                && (node == null || node.isNull()) ? Map.of() : variables(node);
        if (!exactHrisProjection || variables.containsKey("requestPublicId")) {
            return variables;
        }
        Map<String, Object> derived = new LinkedHashMap<>(variables);
        derived.put("requestPublicId", event.aggregateId());
        return Collections.unmodifiableMap(derived);
    }

    private List<MaterializationContext> contexts(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray() || node.size() > NotificationStructuredContexts.MAXIMUM_CONTEXTS) {
            throw contract("Notification contexts must be an array with at most 20 entries.");
        }
        List<MaterializationContext> contexts = new ArrayList<>();
        node.forEach(value -> {
            if (!value.isObject()
                    || value.properties().stream()
                            .anyMatch(field -> !CONTEXT_FIELDS.contains(field.getKey()))) {
                throw contract("Each notification context must contain only contract fields.");
            }
            String kindValue = exactText(value, "kind", 30, true);
            MaterializationContextKind kind;
            try {
                kind = MaterializationContextKind.valueOf(kindValue);
            } catch (IllegalArgumentException exception) {
                throw contract("A notification context kind is invalid.");
            }
            JsonNode matchableNode = value.get("matchable");
            if (matchableNode == null || !matchableNode.isBoolean()) {
                throw contract("Notification context matchable must be boolean.");
            }
            try {
                contexts.add(NotificationStructuredContexts.canonical(
                        new MaterializationContext(
                                kind,
                                exactText(value, "key", 300, true),
                                exactText(value, "displayHint", 160, false),
                                matchableNode.booleanValue())));
            } catch (IllegalArgumentException exception) {
                throw contract(exception.getMessage());
            }
        });
        try {
            return NotificationStructuredContexts.explicit(contexts);
        } catch (IllegalArgumentException exception) {
            throw contract(exception.getMessage());
        }
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

    private String exactText(
            JsonNode parent,
            String field,
            int maximumLength,
            boolean required) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            if (required) throw contract("Notification context is missing " + field + '.');
            return null;
        }
        if (!value.isTextual() || !safe(value.textValue(), maximumLength)
                || !value.textValue().equals(value.textValue().trim())
                || (required && value.textValue().isBlank())) {
            throw contract("Notification context " + field + " is invalid.");
        }
        return value.textValue().isBlank() ? null : value.textValue();
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

    private Map<String, ExactSourceEventOnboarding> parseExactSourceEventOnboardings(
            String value) {
        Map<String, ExactSourceEventOnboarding> onboardings = new LinkedHashMap<>();
        Arrays.stream(value == null ? new String[0] : value.split(",", -1))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .forEach(item -> {
                    if (item.indexOf('*') >= 0) {
                        throw new IllegalArgumentException(
                                "Exact notification source-event onboardings must not contain wildcards.");
                    }
                    int sourceSeparator = item.indexOf('=');
                    if (sourceSeparator < 1
                            || sourceSeparator != item.lastIndexOf('=')
                            || sourceSeparator == item.length() - 1) {
                        throw new IllegalArgumentException(
                                "Exact notification source-event onboardings must use "
                                        + "source=service:eventType entries.");
                    }
                    String source = item.substring(0, sourceSeparator).trim();
                    String serviceAndEvents = item.substring(sourceSeparator + 1).trim();
                    int serviceSeparator = serviceAndEvents.indexOf(':');
                    if (serviceSeparator < 1
                            || serviceSeparator != serviceAndEvents.lastIndexOf(':')
                            || serviceSeparator == serviceAndEvents.length() - 1) {
                        throw new IllegalArgumentException(
                                "Exact notification source-event onboardings must use "
                                        + "source=service:eventType entries.");
                    }
                    String service = serviceAndEvents.substring(0, serviceSeparator).trim();
                    String eventTypes = serviceAndEvents.substring(serviceSeparator + 1).trim();
                    if (!source.startsWith("urn:dwp:")) {
                        throw new IllegalArgumentException(
                                "An exact notification source-event source is invalid.");
                    }
                    if (!service.matches("dwp-[a-z0-9-]+-server")) {
                        throw new IllegalArgumentException(
                                "An exact notification source-event producer service is invalid.");
                    }
                    Set<String> exactEvents = new LinkedHashSet<>();
                    Arrays.stream(eventTypes.split("\\|", -1))
                            .map(String::trim)
                            .forEach(eventType -> {
                                if (!EVENT_TYPE.matcher(eventType).matches()) {
                                    throw new IllegalArgumentException(
                                            "An exact notification event type is invalid.");
                                }
                                if (!exactEvents.add(eventType)) {
                                    throw new IllegalArgumentException(
                                            "Exact notification event types must be unique per source.");
                                }
                            });
                    if (onboardings.putIfAbsent(
                            source,
                            new ExactSourceEventOnboarding(
                                    service, Set.copyOf(exactEvents))) != null) {
                        throw new IllegalArgumentException(
                                "Exact notification source-event onboarding sources must be unique.");
                    }
                });
        return Map.copyOf(onboardings);
    }

    private void validateExactSourceEventOnboardings(
            Map<String, String> legacyProducerSources,
            Map<String, ExactSourceEventOnboarding> onboardings) {
        RESERVED_EXACT_SOURCE_EVENT_ONBOARDINGS.keySet().forEach(source -> {
            if (legacyProducerSources.containsKey(source)) {
                throw new IllegalArgumentException(
                        "A reserved exact notification source must be configured atomically.");
            }
        });
        onboardings.forEach((source, onboarding) -> {
            if (legacyProducerSources.containsKey(source)) {
                throw new IllegalArgumentException(
                        "Notification producer sources and exact onboardings must not overlap.");
            }
            ExactSourceEventOnboarding required =
                    RESERVED_EXACT_SOURCE_EVENT_ONBOARDINGS.get(source);
            if (required == null) {
                throw new IllegalArgumentException(
                        "An exact notification source-event onboarding is not reserved.");
            }
            if (!required.equals(onboarding)) {
                if (HRIS_EXPERIENCE_SOURCE.equals(source)) {
                    throw new IllegalArgumentException(
                            "The reserved HRIS notification source requires its exact atomic onboarding.");
                }
                throw new IllegalArgumentException(
                        "The reserved Calendar notification source requires its exact atomic onboarding.");
            }
        });
    }

    private Map<String, String> effectiveProducerSources(
            Map<String, String> legacyProducerSources,
            Map<String, ExactSourceEventOnboarding> onboardings) {
        Map<String, String> effective = new LinkedHashMap<>(legacyProducerSources);
        onboardings.forEach((source, onboarding) ->
                effective.put(source, onboarding.producerService()));
        return Map.copyOf(effective);
    }

    private Map<String, Set<String>> exactSourceEventTypes(
            Map<String, ExactSourceEventOnboarding> onboardings) {
        Map<String, Set<String>> eventTypes = new LinkedHashMap<>();
        onboardings.forEach((source, onboarding) ->
                eventTypes.put(source, onboarding.eventTypes()));
        return Map.copyOf(eventTypes);
    }

    private void validateExactSourceEventPair(DomainEventEnvelope event) {
        Set<String> configuredEvents = exactSourceEventTypes.get(event.source());
        if (configuredEvents != null && !configuredEvents.contains(event.type())) {
            if (HRIS_EXPERIENCE_SOURCE.equals(event.source())) {
                throw contract(
                        "The reserved HRIS notification source-event pair is not onboarded.");
            }
            if (CALENDAR_SOURCE.equals(event.source())) {
                throw contract(
                        "The reserved Calendar notification source-event pair is not onboarded.");
            }
            throw contract(
                    "The domain-event source-event pair is not notification-onboarded.");
        }
    }

    private void validateExactNotificationProjection(
            DomainEventEnvelope event,
            DirectMaterializationRequest request) {
        if (!HRIS_EXPERIENCE_SOURCE.equals(event.source())) return;
        String requestId = event.aggregateId();
        if (event.schemaVersion() != 1
                || !"EMPLOYEE_CHANGE_REQUEST".equals(event.aggregateType())
                || !canonicalUuid(requestId)
                || !HRIS_EMPLOYEE_CHANGE_TYPE_KEY.equals(request.typeKey())
                || !Objects.equals(
                        "hris:employee-change:" + requestId, request.threadKey())
                || !("ko-KR".equals(request.locale()) || "en-US".equals(request.locale()))
                || !"EMPLOYEE_CHANGE_APPLIED".equals(request.reasonCode())
                || request.actorReference() != null
                || request.subjectReference() == null
                || !canonicalReference(request.subjectReference(), "person:")
                || !Objects.equals(
                        "employee-change:" + requestId, request.targetReference())
                || request.dueAt() != null
                || request.actionRequired()
                || !request.contexts().isEmpty()
                || !HRIS_EMPLOYEE_CHANGE_VARIABLES.containsAll(
                        request.variables().keySet())) {
            throw contract("The HRIS employee-change notification projection is not exact.");
        }
        Object effectiveDate = request.variables().get("effectiveDate");
        if (effectiveDate != null && !canonicalDate(effectiveDate)) {
            throw contract("The HRIS employee-change effectiveDate variable is invalid.");
        }
        Object requestReference = request.variables().get("requestReference");
        if (requestReference != null && !requestId.equals(requestReference)) {
            throw contract("The HRIS employee-change requestReference variable is invalid.");
        }
        Object requestPublicId = request.variables().get("requestPublicId");
        if (!(requestPublicId instanceof String requestPublicIdValue)
                || !requestId.equals(requestPublicIdValue)
                || !canonicalUuid(requestPublicIdValue)) {
            throw contract("The HRIS employee-change requestPublicId variable is invalid.");
        }
        Object targetType = request.variables().get("targetType");
        if (targetType != null && (!(targetType instanceof String targetTypeValue)
                || !targetTypeValue.matches("[A-Z0-9][A-Z0-9._-]{0,79}"))) {
            throw contract("The HRIS employee-change targetType variable is invalid.");
        }
    }

    private void validateExactNotificationPayload(DomainEventEnvelope event) {
        if (!HRIS_EXPERIENCE_SOURCE.equals(event.source())
                || !HRIS_EMPLOYEE_CHANGE_APPLIED_EVENT.equals(event.type())) {
            return;
        }
        JsonNode data = event.data();
        if (data == null
                || !data.isObject()
                || data.properties().stream()
                        .anyMatch(field -> !HRIS_EMPLOYEE_CHANGE_DATA_FIELDS.contains(
                                field.getKey()))) {
            throw contract(
                    "The HRIS employee-change event data must contain only notificationIntents.");
        }
        JsonNode intents = data.get("notificationIntents");
        if (intents == null
                || !intents.isArray()
                || intents.size() != 1
                || !intents.get(0).isObject()) {
            throw contract(
                    "The HRIS employee-change event must contain exactly one notification intent.");
        }
        if (intents.get(0).properties().stream()
                .anyMatch(field -> !HRIS_EMPLOYEE_CHANGE_INTENT_FIELDS.contains(
                        field.getKey()))) {
            throw contract(
                    "The HRIS employee-change notification intent contains an unsupported field.");
        }
        JsonNode locale = intents.get(0).get("locale");
        if (locale != null && !locale.isNull()
                && (!locale.isTextual()
                || !locale.textValue().equals(locale.textValue().trim())
                || !validHrisLocale(locale.textValue()))) {
            throw contract("The HRIS employee-change notification locale is invalid.");
        }
    }

    private String notificationLocale(
            DomainEventEnvelope event,
            String typeKey,
            JsonNode intent) {
        if (!HRIS_EXPERIENCE_SOURCE.equals(event.source())
                || !HRIS_EMPLOYEE_CHANGE_APPLIED_EVENT.equals(event.type())
                || !HRIS_EMPLOYEE_CHANGE_TYPE_KEY.equals(typeKey)) {
            return defaultText(intent, "locale", "ko-KR", 35);
        }
        JsonNode locale = intent.get("locale");
        if (locale == null || locale.isNull()) return "ko-KR";
        String value = locale.textValue();
        String language = value.substring(0, 2).toLowerCase(java.util.Locale.ROOT);
        return switch (language) {
            case "ko" -> "ko-KR";
            case "en" -> "en-US";
            default -> "en-US";
        };
    }

    private boolean validHrisLocale(String value) {
        return value != null && HRIS_LOCALE.matcher(value).matches();
    }

    private boolean canonicalDate(Object value) {
        if (!(value instanceof String text)) return false;
        try {
            return LocalDate.parse(text).toString().equals(text);
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private boolean canonicalReference(String value, String prefix) {
        return value.startsWith(prefix)
                && canonicalUuid(value.substring(prefix.length()));
    }

    private boolean canonicalUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
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

    private record ExactSourceEventOnboarding(
            String producerService,
            Set<String> eventTypes) {
    }
}
