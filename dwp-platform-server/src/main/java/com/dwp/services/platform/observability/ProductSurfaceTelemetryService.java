package com.dwp.services.platform.observability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ProductSurfaceTelemetryService {

    private static final Set<String> COHORTS = Set.of(
            "baseline", "internal", "design-partner", "eligible-10",
            "eligible-25", "eligible-50", "eligible-90", "holdout", "full");

    private static final Map<String, EventRule> RULES = Map.ofEntries(
            rule("surface.exposed", required("surfaceKey", "deviceClass", "attemptId")),
            rule("surface.switch.started", required("fromSurfaceKey", "toSurfaceKey", "attemptId")),
            rule("surface.switch.completed", required(
                    "fromSurfaceKey", "toSurfaceKey", "attemptId", "elapsedBucket")),
            rule("surface.switch.failed", required(
                    "targetSurfaceKey", "attemptId", "reasonCode")),
            rule("surface.returned", required(
                    "fromSurfaceKey", "toSurfaceKey", "attemptId", "elapsedBucket")),
            rule("surface.route.denied", required("surfaceKey", "routeId", "reasonCode")),
            rule("surface.scope.switch.started", required(
                    "surfaceKey", "scopeKind", "attemptId")),
            rule("surface.scope.switch.completed", required(
                    "surfaceKey", "scopeKind", "attemptId", "elapsedBucket")),
            rule("surface.scope.switch.failed", required(
                    "surfaceKey", "scopeKind", "attemptId", "reasonCode")),
            rule("surface.scope.invalid", required("surfaceKey", "scopeKind", "reasonCode")),
            rule("surface.assignment.expired", required("surfaceKey", "readOnly")),
            rule("surface.policy.lock.viewed", required("surfaceKey", "policyKind")),
            rule("surface.task.started", required("surfaceKey", "taskKind", "attemptId")),
            rule("surface.task.completed", required(
                    "surfaceKey", "taskKind", "attemptId", "elapsedBucket")),
            rule("surface.task.failed", required(
                    "surfaceKey", "taskKind", "attemptId", "reasonCode")),
            rule("surface.task.abandoned", required(
                    "surfaceKey", "taskKind", "attemptId", "elapsedBucket")));

    private final ProductSurfaceTelemetryRepository repository;
    private final ProductSurfaceTelemetryDimensionRegistry dimensions;
    private final Clock clock;
    private final boolean collectionEnabled;

    @Autowired
    public ProductSurfaceTelemetryService(
            ProductSurfaceTelemetryRepository repository,
            ProductSurfaceTelemetryDimensionRegistry dimensions,
            @Value("${dwp.platform.product-surface-telemetry.collection-enabled:false}")
                    boolean collectionEnabled) {
        this(repository, dimensions, Clock.systemUTC(), collectionEnabled);
    }

    ProductSurfaceTelemetryService(
            ProductSurfaceTelemetryRepository repository,
            ProductSurfaceTelemetryDimensionRegistry dimensions,
            Clock clock,
            boolean collectionEnabled) {
        this.repository = repository;
        this.dimensions = dimensions;
        this.clock = clock;
        this.collectionEnabled = collectionEnabled;
    }

    public ProductSurfaceTelemetryDtos.AcceptedEvent ingest(
            Long tenantId,
            String cohort,
            ProductSurfaceTelemetryDtos.EventRequest request) {
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalArgumentException("A trusted tenant is required");
        }
        if (!COHORTS.contains(cohort)) {
            throw new IllegalArgumentException("Unknown server-evaluated cohort");
        }
        validateEvent(request);
        dimensions.validate(request);
        UUID eventId = UUID.randomUUID();
        OffsetDateTime acceptedAt = OffsetDateTime.now(clock);
        if (collectionEnabled) {
            repository.insert(new ProductSurfaceTelemetryRepository.EventRow(
                    eventId,
                    tenantId,
                    cohort,
                    request,
                    acceptedAt));
        }
        return new ProductSurfaceTelemetryDtos.AcceptedEvent(
                eventId, collectionEnabled, acceptedAt);
    }

    static void validateEvent(ProductSurfaceTelemetryDtos.EventRequest request) {
        if (request == null || !Integer.valueOf(1).equals(request.schemaVersion())) {
            throw new IllegalArgumentException("Unsupported telemetry schema version");
        }
        EventRule rule = RULES.get(request.eventName());
        if (rule == null) {
            throw new IllegalArgumentException("Unsupported telemetry event");
        }
        Map<String, Object> values = optionalValues(request);
        Set<String> present = values.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!present.containsAll(rule.required())) {
            Set<String> missing = new java.util.HashSet<>(rule.required());
            missing.removeAll(present);
            throw new IllegalArgumentException("Missing event fields: " + missing);
        }
        if (!rule.allowed().containsAll(present)) {
            Set<String> unexpected = new java.util.HashSet<>(present);
            unexpected.removeAll(rule.allowed());
            throw new IllegalArgumentException("Fields are not valid for event: " + unexpected);
        }
    }

    private static Map<String, Object> optionalValues(
            ProductSurfaceTelemetryDtos.EventRequest request) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("surfaceKey", request.surfaceKey());
        values.put("fromSurfaceKey", request.fromSurfaceKey());
        values.put("toSurfaceKey", request.toSurfaceKey());
        values.put("targetSurfaceKey", request.targetSurfaceKey());
        values.put("routeId", request.routeId());
        values.put("scopeKind", request.scopeKind());
        values.put("deviceClass", request.deviceClass());
        values.put("elapsedBucket", request.elapsedBucket());
        values.put("reasonCode", request.reasonCode());
        values.put("taskKind", request.taskKind());
        values.put("policyKind", request.policyKind());
        values.put("readOnly", request.readOnly());
        values.put("attemptId", request.attemptId());
        return values;
    }

    private static Map.Entry<String, EventRule> rule(String eventName, Set<String> fields) {
        return Map.entry(eventName, new EventRule(fields, fields));
    }

    private static Set<String> required(String... fields) {
        return Set.of(fields);
    }

    private record EventRule(Set<String> required, Set<String> allowed) {
    }
}
