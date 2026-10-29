package com.dwp.services.notification.integration;

import com.dwp.core.event.DomainEventEnvelope;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class NotificationDomainEventOnboarding {

    static final String HRIS_EXPERIENCE_SOURCE =
            "urn:dwp:people:hrm:dwp-experience";
    static final String HRIS_EMPLOYEE_CHANGE_APPLIED_EVENT =
            "EmployeeChangeAppliedNotificationRequested.v1";

    private static final String HRIS_EXPERIENCE_PRODUCER =
            "dwp-people-server";
    private static final String CALENDAR_SOURCE =
            "urn:dwp:platform:calendar";
    private static final String CALENDAR_PRODUCER =
            "dwp-platform-server";
    private static final String CALENDAR_REMINDER_DUE_EVENT =
            "calendar.event-reminder.due.v1";
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
    private static final Pattern EVENT_TYPE =
            Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,199}");

    private final Map<String, String> producerSources;
    private final Map<String, Set<String>> exactSourceEventTypes;

    private NotificationDomainEventOnboarding(
            Map<String, String> producerSources,
            Map<String, Set<String>> exactSourceEventTypes) {
        this.producerSources = producerSources;
        this.exactSourceEventTypes = exactSourceEventTypes;
    }

    static NotificationDomainEventOnboarding from(
            String producerSources,
            String exactSourceEventOnboardings) {
        Map<String, String> legacyProducerSources = parseProducerSources(producerSources);
        Map<String, ExactSourceEventOnboarding> onboardings =
                parseExactSourceEventOnboardings(exactSourceEventOnboardings);
        validateExactSourceEventOnboardings(legacyProducerSources, onboardings);
        return new NotificationDomainEventOnboarding(
                effectiveProducerSources(legacyProducerSources, onboardings),
                exactSourceEventTypes(onboardings));
    }

    String producerFor(String source) {
        return producerSources.get(source);
    }

    void validateExactSourceEventPair(DomainEventEnvelope event) {
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

    private static Map<String, String> parseProducerSources(String value) {
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

    private static Map<String, ExactSourceEventOnboarding> parseExactSourceEventOnboardings(
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

    private static void validateExactSourceEventOnboardings(
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

    private static Map<String, String> effectiveProducerSources(
            Map<String, String> legacyProducerSources,
            Map<String, ExactSourceEventOnboarding> onboardings) {
        Map<String, String> effective = new LinkedHashMap<>(legacyProducerSources);
        onboardings.forEach((source, onboarding) ->
                effective.put(source, onboarding.producerService()));
        return Map.copyOf(effective);
    }

    private static Map<String, Set<String>> exactSourceEventTypes(
            Map<String, ExactSourceEventOnboarding> onboardings) {
        Map<String, Set<String>> eventTypes = new LinkedHashMap<>();
        onboardings.forEach((source, onboarding) ->
                eventTypes.put(source, onboarding.eventTypes()));
        return Map.copyOf(eventTypes);
    }

    private static NotificationDomainEventException contract(String message) {
        return new NotificationDomainEventException(message);
    }

    private record ExactSourceEventOnboarding(
            String producerService,
            Set<String> eventTypes) {
    }
}
