package com.dwp.services.platform.workplace.workplaceplanning;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.dwp.services.platform.workplace.workplaceplanning.WorkplacePlanningDtos.*;

@RestController
@RequestMapping("/v1/admin/workplace/space-planning")
public class WorkplacePlanningController {
    static final String TENANT = "X-DWP-Tenant-ID";
    static final String USER = "X-DWP-User-ID";
    static final String PERMISSIONS = "X-DWP-Permissions";
    static final String ACCESS_MODE = "X-DWP-Active-Access-Mode";
    static final String IDEMPOTENCY = "Idempotency-Key";
    static final String CORRELATION = "X-Correlation-ID";
    static final String VIEW = "ADMIN.WORKPLACE:VIEW";
    static final String MANAGE = "ADMIN.WORKPLACE:MANAGE";
    static final String APPROVE = "ADMIN.WORKPLACE:APPROVE";

    private final WorkplacePlanningService service;

    public WorkplacePlanningController(WorkplacePlanningService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiResponse<PlanningOverview> overview(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam UUID siteId,
            @RequestParam(required = false) UUID floorId,
            @RequestParam(required = false) String neighborhood,
            @RequestParam(required = false) String resourceType,
            @RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.overview(tenantId,
                scope(siteId, floorId, neighborhood, resourceType, from, to)));
    }

    @GetMapping("/sources")
    public ApiResponse<List<PlanningSourceStatus>> sources(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam UUID siteId,
            @RequestParam(required = false) UUID floorId,
            @RequestParam(required = false) String neighborhood,
            @RequestParam(required = false) String resourceType,
            @RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.sources(tenantId,
                scope(siteId, floorId, neighborhood, resourceType, from, to)));
    }

    @PostMapping("/scenarios")
    public ResponseEntity<ApiResponse<ScenarioCommandResult>> createScenario(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody CreateScenarioRequest request) {
        requirePermission(permissions, MANAGE);
        ScenarioCommandResult result = service.create(
                tenantId, actorId, idempotencyKey, correlationId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .body(ApiResponse.success(result));
    }

    @GetMapping("/scenarios")
    public ApiResponse<List<ScenarioView>> scenarios(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) ScenarioState state,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.scenarios(tenantId, siteId, state));
    }

    @GetMapping("/scenarios/{scenarioId}")
    public ApiResponse<ScenarioView> scenario(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID scenarioId,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.scenario(tenantId, scenarioId));
    }

    @PutMapping("/scenarios/{scenarioId}")
    public ApiResponse<ScenarioCommandResult> updateScenario(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID scenarioId,
            @Valid @RequestBody UpdateScenarioRequest request,
            HttpServletResponse response) {
        requirePermission(permissions, MANAGE);
        noStore(response);
        return ApiResponse.success(service.update(
                tenantId, actorId, scenarioId, idempotencyKey, correlationId, request));
    }

    @PostMapping("/scenarios/{scenarioId}:preview")
    public ApiResponse<ScenarioCommandResult> previewScenario(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID scenarioId,
            @Valid @RequestBody PreviewScenarioRequest request,
            HttpServletResponse response) {
        requirePermission(permissions, MANAGE);
        noStore(response);
        return ApiResponse.success(service.preview(
                tenantId, actorId, scenarioId, idempotencyKey, correlationId, request));
    }

    @PostMapping("/scenarios/{scenarioId}:submit")
    public ApiResponse<ScenarioCommandResult> submitScenario(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID scenarioId,
            @Valid @RequestBody ScenarioTransitionRequest request,
            HttpServletResponse response) {
        authorizeElevated(permissions, accessMode, MANAGE);
        noStore(response);
        return ApiResponse.success(service.submit(
                tenantId, actorId, scenarioId, idempotencyKey, correlationId, request));
    }

    @PostMapping("/scenarios/{scenarioId}:approve")
    public ApiResponse<ScenarioCommandResult> approveScenario(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID scenarioId,
            @Valid @RequestBody ScenarioApprovalRequest request,
            HttpServletResponse response) {
        authorizeElevated(permissions, accessMode, MANAGE, APPROVE);
        noStore(response);
        return ApiResponse.success(service.approve(
                tenantId, actorId, scenarioId, idempotencyKey, correlationId, request));
    }

    @PostMapping("/scenarios/{scenarioId}:publish")
    public ApiResponse<ScenarioCommandResult> publishScenario(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID scenarioId,
            @Valid @RequestBody ScenarioTransitionRequest request,
            HttpServletResponse response) {
        authorizeElevated(permissions, accessMode, MANAGE, APPROVE);
        noStore(response);
        return ApiResponse.success(service.publish(
                tenantId, actorId, scenarioId, idempotencyKey, correlationId, request));
    }

    @PostMapping("/scenarios/{scenarioId}/booking-impact:preview")
    public ApiResponse<BookingImpactCommandResult> previewBookingImpact(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID scenarioId,
            @Valid @RequestBody BookingImpactPreviewRequest request,
            HttpServletResponse response) {
        requirePermission(permissions, MANAGE);
        noStore(response);
        return ApiResponse.success(service.previewBookingImpact(
                tenantId, actorId, scenarioId, idempotencyKey, correlationId, request));
    }

    private static PlanningScope scope(
            UUID siteId,
            UUID floorId,
            String neighborhood,
            String resourceType,
            OffsetDateTime from,
            OffsetDateTime to) {
        return new PlanningScope(siteId, floorId, neighborhood, resourceType, from, to);
    }

    static void authorizeElevated(String values, String accessMode, String... expected) {
        for (String permission : expected) requirePermission(values, permission);
        if (!"ELEVATED".equalsIgnoreCase(accessMode)) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED,
                    "Fresh elevated access is required for this space-planning transition.");
        }
    }

    static void requirePermission(String values, String expected) {
        if (!permissionSet(values).contains(expected)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The required Workplace space-planning permission is missing.");
        }
    }

    static Set<String> permissionSet(String values) {
        if (values == null || values.isBlank()) return Set.of();
        return Arrays.stream(values.split(","))
                .map(String::trim).filter(value -> !value.isBlank())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
    }
}
