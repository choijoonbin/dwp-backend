package com.dwp.services.platform.workhub.assignment;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.platform.workhub.personal.PersonalWorkDtos.AccessContext;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import static com.dwp.services.platform.workhub.assignment.WorkAssignmentDtos.*;

@RestController
@RequestMapping("/v1/workspace/work-hub/assignments")
public class WorkAssignmentController {
    private final WorkAssignmentService service;
    public WorkAssignmentController(WorkAssignmentService service) { this.service = service; }

    @ModelAttribute("workAssignmentContext")
    public AccessContext context(@RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false) UUID personPublicId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader(value = "Accept-Language", required = false) String locale) {
        return new AccessContext(tenantId, userId, permissions, personPublicId, groupRefs, locale);
    }

    @GetMapping
    public ApiResponse<TaskPage> list(
            @Parameter(hidden = true) @ModelAttribute(value = "workAssignmentContext", binding = false) AccessContext actor,
            @RequestParam(defaultValue = "ASSIGNED_TO_ME") Scope scope,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.list(actor, scope, page, size));
    }

    @GetMapping("/by-source")
    public ApiResponse<Task> bySource(
            @Parameter(hidden = true) @ModelAttribute(value = "workAssignmentContext", binding = false) AccessContext actor,
            @RequestParam UUID meetingId, @RequestParam UUID reportId, @RequestParam UUID candidateId) {
        return ApiResponse.success(service.findBySource(actor,
                new SourceIdentity(SourceSystem.MEETING_FOLLOWUP, meetingId, reportId, candidateId)));
    }

    @GetMapping("/commands/{commandId}")
    public ApiResponse<MutationResult> receipt(
            @Parameter(hidden = true) @ModelAttribute(value = "workAssignmentContext", binding = false) AccessContext actor,
            @PathVariable UUID commandId) {
        return ApiResponse.success(service.receipt(actor, commandId));
    }

    @GetMapping("/{assignmentId}")
    public ApiResponse<Task> get(
            @Parameter(hidden = true) @ModelAttribute(value = "workAssignmentContext", binding = false) AccessContext actor,
            @PathVariable UUID assignmentId) {
        return ApiResponse.success(service.get(actor, assignmentId));
    }

    @GetMapping("/{assignmentId}/events")
    public ApiResponse<EventPage> events(
            @Parameter(hidden = true) @ModelAttribute(value = "workAssignmentContext", binding = false) AccessContext actor,
            @PathVariable UUID assignmentId, @RequestParam(defaultValue = "-1") long afterVersion,
            @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.success(service.events(actor, assignmentId, afterVersion, size));
    }

    @PostMapping
    public ApiResponse<MutationResult> create(
            @Parameter(hidden = true) @ModelAttribute(value = "workAssignmentContext", binding = false) AccessContext actor,
            @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody CreateRequest request) {
        return ApiResponse.success(service.create(actor, commandId, correlationId, request));
    }

    @PostMapping("/{assignmentId}/accept")
    public ApiResponse<MutationResult> accept(
            @Parameter(hidden = true) @ModelAttribute(value = "workAssignmentContext", binding = false) AccessContext actor,
            @PathVariable UUID assignmentId, @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody VersionCommand request) {
        return ApiResponse.success(service.command(actor, assignmentId, commandId, correlationId, Action.ACCEPT, request));
    }

    @PostMapping("/{assignmentId}/decline")
    public ApiResponse<MutationResult> decline(
            @Parameter(hidden = true) @ModelAttribute(value = "workAssignmentContext", binding = false) AccessContext actor,
            @PathVariable UUID assignmentId, @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody VersionCommand request) {
        return ApiResponse.success(service.command(actor, assignmentId, commandId, correlationId, Action.DECLINE, request));
    }

    @PostMapping("/{assignmentId}/start")
    public ApiResponse<MutationResult> start(
            @Parameter(hidden = true) @ModelAttribute(value = "workAssignmentContext", binding = false) AccessContext actor,
            @PathVariable UUID assignmentId, @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody VersionCommand request) {
        return ApiResponse.success(service.command(actor, assignmentId, commandId, correlationId, Action.START, request));
    }

    @PostMapping("/{assignmentId}/wait")
    public ApiResponse<MutationResult> waitForResponse(
            @Parameter(hidden = true) @ModelAttribute(value = "workAssignmentContext", binding = false) AccessContext actor,
            @PathVariable UUID assignmentId, @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody VersionCommand request) {
        return ApiResponse.success(service.command(actor, assignmentId, commandId, correlationId, Action.WAIT, request));
    }

    @PostMapping("/{assignmentId}/complete")
    public ApiResponse<MutationResult> complete(
            @Parameter(hidden = true) @ModelAttribute(value = "workAssignmentContext", binding = false) AccessContext actor,
            @PathVariable UUID assignmentId, @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody VersionCommand request) {
        return ApiResponse.success(service.command(actor, assignmentId, commandId, correlationId, Action.COMPLETE, request));
    }

    @PostMapping("/{assignmentId}/cancel")
    public ApiResponse<MutationResult> cancel(
            @Parameter(hidden = true) @ModelAttribute(value = "workAssignmentContext", binding = false) AccessContext actor,
            @PathVariable UUID assignmentId, @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody VersionCommand request) {
        return ApiResponse.success(service.command(actor, assignmentId, commandId, correlationId, Action.CANCEL, request));
    }

    @PostMapping("/{assignmentId}/reassign")
    public ApiResponse<MutationResult> reassign(
            @Parameter(hidden = true) @ModelAttribute(value = "workAssignmentContext", binding = false) AccessContext actor,
            @PathVariable UUID assignmentId, @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody ReassignRequest request) {
        return ApiResponse.success(service.reassign(actor, assignmentId, commandId, correlationId, request));
    }
}
