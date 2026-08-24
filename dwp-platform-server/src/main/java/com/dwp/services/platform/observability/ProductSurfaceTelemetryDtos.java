package com.dwp.services.platform.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class ProductSurfaceTelemetryDtos {

    private static final String SURFACE_KEY_PATTERN =
            "[a-z][a-z0-9-]{0,47}(\\.[a-z][a-z0-9-]{0,47})+";

    private static final Set<String> REQUEST_FIELDS = Set.of(
            "schemaVersion", "eventName", "productKey", "surfaceKey",
            "fromSurfaceKey", "toSurfaceKey", "targetSurfaceKey", "routeId",
            "scopeKind", "deviceClass", "elapsedBucket", "reasonCode",
            "taskKind", "policyKind", "readOnly", "attemptId");

    private ProductSurfaceTelemetryDtos() {
    }

    @Schema(
            name = "ProductSurfaceTelemetryEventRequest",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record EventRequest(
            @NotNull Integer schemaVersion,
            @NotBlank @Size(max = 48) String eventName,
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{0,47}") String productKey,
            @Size(max = 160) @Pattern(regexp = SURFACE_KEY_PATTERN) String surfaceKey,
            @Size(max = 160) @Pattern(regexp = SURFACE_KEY_PATTERN) String fromSurfaceKey,
            @Size(max = 160) @Pattern(regexp = SURFACE_KEY_PATTERN) String toSurfaceKey,
            @Size(max = 160) @Pattern(regexp = SURFACE_KEY_PATTERN) String targetSurfaceKey,
            @Pattern(regexp = "[a-z][a-z0-9._-]{0,127}") String routeId,
            ScopeKind scopeKind,
            DeviceClass deviceClass,
            ElapsedBucket elapsedBucket,
            ReasonCode reasonCode,
            TaskKind taskKind,
            PolicyKind policyKind,
            Boolean readOnly,
            UUID attemptId) {
    }

    public record AcceptedEvent(
            UUID eventId,
            boolean collected,
            OffsetDateTime acceptedAt) {
    }

    public enum ScopeKind {
        SELF,
        ORG_UNIT,
        LEGAL_ENTITY,
        DOMAIN,
        RESOURCE,
        RESOURCE_SET,
        TARGET_POPULATION,
        SUPPORT_SESSION
    }

    public enum DeviceClass {
        DESKTOP,
        TABLET,
        MOBILE
    }

    public enum ElapsedBucket {
        LT_1S,
        S1_TO_5,
        S5_TO_15,
        S15_TO_30,
        S30_TO_60,
        M1_TO_5,
        GTE_5M
    }

    public enum ReasonCode {
        APP_DENIED,
        SURFACE_DENIED,
        ROUTE_DENIED,
        SCOPE_SELECTION_REQUIRED,
        SCOPE_INVALID,
        EXPIRED,
        ACTIVATION_REQUIRED,
        STEP_UP_REQUIRED,
        SOD_CONFLICT,
        SUPPORT_SCOPE_DENIED,
        AUTHORITY_UNAVAILABLE,
        NETWORK_ERROR,
        CANCELLED,
        VALIDATION_ERROR
    }

    public enum TaskKind {
        WORK,
        OPERATIONS,
        CONFIGURATION,
        ADMINISTRATION,
        GOVERNANCE,
        DESIGN,
        INTEGRATION,
        REPORTING,
        REVIEW
    }

    public enum PolicyKind {
        READ_ONLY,
        UPSTREAM_LOCK,
        SEGREGATION_OF_DUTIES,
        STEP_UP,
        SUPPORT,
        EXPIRY
    }

    public static EventRequest parseStrict(
            JsonNode payload,
            ObjectMapper objectMapper,
            Validator validator) {
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("Telemetry payload must be a JSON object");
        }
        Set<String> unexpected = payload.propertyStream()
                .map(entry -> entry.getKey())
                .filter(field -> !REQUEST_FIELDS.contains(field))
                .collect(Collectors.toUnmodifiableSet());
        if (!unexpected.isEmpty()) {
            throw new IllegalArgumentException("Unsupported telemetry fields: " + unexpected);
        }
        EventRequest request;
        try {
            request = objectMapper.treeToValue(payload, EventRequest.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid telemetry payload", exception);
        }
        Set<ConstraintViolation<EventRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining(", ")));
        }
        return request;
    }
}
