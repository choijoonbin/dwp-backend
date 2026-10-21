package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationOwnerAuthorityFilter.Authority;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.*;

/** Closed, default-disabled owner adapter for exactly sixteen SYS-006 operations. */
@RestController
@RequestMapping("/v1/admin/hris")
@ConditionalOnProperty(
        name = "dwp.hris.automation-control-plane.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class AutomationController {

    private static final Set<String> CATALOG_QUERY = Set.of(
            "asOf", "fields", "freshness", "limit", "cursor", "filter",
            "resourceTypes");
    private static final Set<String> RUN_DETAIL_QUERY = Set.of(
            "asOf", "freshness", "limit", "attemptCursor", "itemCursor",
            "checkpointCursor");

    private final AutomationService service;

    AutomationController(AutomationService service) {
        this.service = service;
    }

    @GetMapping("/automations")
    ResponseEntity<ApiResponse<CatalogResponse>> catalog(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime asOf,
            @RequestParam List<String> fields,
            @RequestParam String freshness,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) List<String> resourceTypes,
            HttpServletRequest request) {
        requireQueryKeys(request, CATALOG_QUERY);
        return ok(service.catalog(
                authority(request), asOf, fields, freshness, limit, cursor, filter,
                resourceTypes));
    }

    @PostMapping("/automations")
    ResponseEntity<ApiResponse<CommandReceipt>> createDefinition(
            @Valid @RequestBody CreateDefinitionCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.createDefinition(authority(request), command));
    }

    @PostMapping("/automations/{automationId}/runs")
    ResponseEntity<ApiResponse<CommandReceipt>> queueRun(
            @PathVariable UUID automationId,
            @Valid @RequestBody QueueRunCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.queueRun(authority(request), automationId, command));
    }

    @GetMapping("/automation-runs/{runId}")
    ResponseEntity<ApiResponse<RunDetailResponse>> runDetail(
            @PathVariable UUID runId,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime asOf,
            @RequestParam String freshness,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String attemptCursor,
            @RequestParam(required = false) String itemCursor,
            @RequestParam(required = false) String checkpointCursor,
            HttpServletRequest request) {
        requireQueryKeys(request, RUN_DETAIL_QUERY);
        return ok(service.runDetail(authority(request), runId, asOf, freshness,
                limit, attemptCursor, itemCursor, checkpointCursor));
    }

    @PostMapping("/automation-runs/{runId}/retry")
    ResponseEntity<ApiResponse<CommandReceipt>> retryRun(
            @PathVariable UUID runId,
            @Valid @RequestBody RetryRunCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.retryRun(authority(request), runId, command));
    }

    @PostMapping("/automations/{automationId}/versions")
    ResponseEntity<ApiResponse<CommandReceipt>> createVersion(
            @PathVariable UUID automationId,
            @Valid @RequestBody CreateVersionCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.createVersion(authority(request), automationId, command));
    }

    @PostMapping("/automations/{automationId}/versions/{version}/validate")
    ResponseEntity<ApiResponse<CommandReceipt>> validateVersion(
            @PathVariable UUID automationId,
            @PathVariable long version,
            @Valid @RequestBody LifecycleCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.transitionVersion(
                authority(request), automationId, version, command, "VALIDATE"));
    }

    @PostMapping("/automations/{automationId}/versions/{version}/submit")
    ResponseEntity<ApiResponse<CommandReceipt>> submitVersion(
            @PathVariable UUID automationId,
            @PathVariable long version,
            @Valid @RequestBody LifecycleCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.transitionVersion(
                authority(request), automationId, version, command, "SUBMIT"));
    }

    @PostMapping("/automations/{automationId}/versions/{version}/approve")
    ResponseEntity<ApiResponse<CommandReceipt>> approveVersion(
            @PathVariable UUID automationId,
            @PathVariable long version,
            @Valid @RequestBody LifecycleCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.transitionVersion(
                authority(request), automationId, version, command, "APPROVE"));
    }

    @PostMapping("/automations/{automationId}/versions/{version}/activate")
    ResponseEntity<ApiResponse<CommandReceipt>> activateVersion(
            @PathVariable UUID automationId,
            @PathVariable long version,
            @Valid @RequestBody LifecycleCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.transitionVersion(
                authority(request), automationId, version, command, "ACTIVATE"));
    }

    @PostMapping("/automations/{automationId}/schedules")
    ResponseEntity<ApiResponse<CommandReceipt>> createSchedule(
            @PathVariable UUID automationId,
            @Valid @RequestBody CreateScheduleCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.createSchedule(authority(request), automationId, command));
    }

    @PostMapping("/automation-schedules/{scheduleId}/submit")
    ResponseEntity<ApiResponse<CommandReceipt>> submitSchedule(
            @PathVariable UUID scheduleId,
            @Valid @RequestBody LifecycleCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.transitionSchedule(
                authority(request), scheduleId, command, "SUBMIT"));
    }

    @PostMapping("/automation-schedules/{scheduleId}/approve")
    ResponseEntity<ApiResponse<CommandReceipt>> approveSchedule(
            @PathVariable UUID scheduleId,
            @Valid @RequestBody LifecycleCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.transitionSchedule(
                authority(request), scheduleId, command, "APPROVE"));
    }

    @PostMapping("/automation-schedules/{scheduleId}/activate")
    ResponseEntity<ApiResponse<CommandReceipt>> activateSchedule(
            @PathVariable UUID scheduleId,
            @Valid @RequestBody LifecycleCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.transitionSchedule(
                authority(request), scheduleId, command, "ACTIVATE"));
    }

    @PostMapping("/automation-schedules/{scheduleId}/suspend")
    ResponseEntity<ApiResponse<CommandReceipt>> suspendSchedule(
            @PathVariable UUID scheduleId,
            @Valid @RequestBody LifecycleCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.transitionSchedule(
                authority(request), scheduleId, command, "SUSPEND"));
    }

    @PostMapping("/automation-runs/{runId}/cancel")
    ResponseEntity<ApiResponse<CommandReceipt>> cancelRun(
            @PathVariable UUID runId,
            @Valid @RequestBody CancelRunCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.cancelRun(authority(request), runId, command));
    }

    private static Authority authority(HttpServletRequest request) {
        Object value = request.getAttribute(
                AutomationOwnerAuthorityFilter.AUTHORITY_ATTRIBUTE);
        if (!(value instanceof Authority authority)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN, "AUTOMATION_OWNER_AUTHORITY_REQUIRED");
        }
        return authority;
    }

    private static void requireNoQuery(HttpServletRequest request) {
        requireQueryKeys(request, Set.of());
    }

    private static void requireQueryKeys(
            HttpServletRequest request, Set<String> allowed) {
        List<String> names = Collections.list(request.getParameterNames());
        if (!allowed.containsAll(names)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "AUTOMATION_UNKNOWN_QUERY_PARAMETER");
        }
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T value) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(value));
    }

    private static <T> ResponseEntity<ApiResponse<T>> accepted(T value) {
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(value));
    }
}
