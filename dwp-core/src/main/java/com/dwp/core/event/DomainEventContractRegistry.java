package com.dwp.core.event;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Strict local schema registry. Unknown event types fail closed until explicitly registered. */
public class DomainEventContractRegistry {

    private static final Pattern EVENT_TYPE =
            Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*){2,15}$");
    private static final Pattern TRACE_PARENT =
            Pattern.compile("^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

    private final Map<String, Contract> contracts = new ConcurrentHashMap<>();

    public void register(String eventType, int minimumVersion, int maximumVersion) {
        validateEventType(eventType);
        if (minimumVersion < 1 || maximumVersion < minimumVersion) {
            throw new IllegalArgumentException("Invalid domain-event schema version range.");
        }
        Contract replacement = new Contract(minimumVersion, maximumVersion);
        Contract existing = contracts.putIfAbsent(eventType, replacement);
        if (existing != null && !existing.equals(replacement)) {
            throw new IllegalStateException("Conflicting domain-event contract: " + eventType);
        }
    }

    public Contract requireCompatible(DomainEventEnvelope event) {
        validateEnvelope(event);
        Contract contract = contracts.get(event.type());
        if (contract == null) {
            throw new UnsupportedEventContractException(
                    "Unregistered domain-event type: " + event.type());
        }
        if (event.schemaVersion() < contract.minimumVersion()
                || event.schemaVersion() > contract.maximumVersion()) {
            throw new UnsupportedEventContractException(
                    "Unsupported schema version " + event.schemaVersion() + " for " + event.type()
                            + "; supported " + contract.minimumVersion() + "-"
                            + contract.maximumVersion());
        }
        return contract;
    }

    public Map<String, Contract> snapshot() {
        return Map.copyOf(contracts);
    }

    private static void validateEnvelope(DomainEventEnvelope event) {
        if (event == null) throw new IllegalArgumentException("Domain event is required.");
        if (!"1.0".equals(event.specVersion())) {
            throw new UnsupportedEventContractException(
                    "Unsupported CloudEvents spec version: " + event.specVersion());
        }
        if (event.id() == null || event.time() == null || event.data() == null) {
            throw new IllegalArgumentException("Domain event id, time, and data are required.");
        }
        required(event.source(), "source", 240);
        validateEventType(event.type());
        required(event.aggregateType(), "aggregateType", 120);
        required(event.aggregateId(), "aggregateId", 240);
        required(event.correlationId(), "correlationId", 160);
        if (event.schemaVersion() < 1 || event.aggregateSequence() < 1) {
            throw new IllegalArgumentException(
                    "Schema version and aggregate sequence must be positive.");
        }
        if (event.traceParent() != null
                && !event.traceParent().isBlank()
                && !TRACE_PARENT.matcher(event.traceParent()).matches()) {
            throw new IllegalArgumentException("Invalid W3C traceparent value.");
        }
    }

    private static void validateEventType(String eventType) {
        required(eventType, "type", 240);
        if (!EVENT_TYPE.matcher(eventType).matches()) {
            throw new IllegalArgumentException(
                    "Domain-event type must use a namespaced lowercase identifier.");
        }
    }

    private static void required(String value, String field, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException("Invalid domain-event " + field + '.');
        }
    }

    public record Contract(int minimumVersion, int maximumVersion) {
    }

    public static class UnsupportedEventContractException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UnsupportedEventContractException(String message) {
            super(message);
        }
    }
}
