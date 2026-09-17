package com.dwp.services.platform.workhub.personal;

import io.swagger.v3.oas.annotations.Parameter;
import com.dwp.core.common.ApiResponse;
import com.dwp.services.platform.mail.MailProposalHandoffBinding;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.LocalDate;
import java.util.UUID;

import static com.dwp.services.platform.workhub.personal.PersonalWorkDtos.*;

@RestController
@RequestMapping("/v1/workspace/work-hub")
public class PersonalWorkController {
    private final PersonalWorkService service;

    public PersonalWorkController(PersonalWorkService service) { this.service = service; }

    @ModelAttribute("personalWorkContext")
    public AccessContext context(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false) UUID personPublicId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader(value = "Accept-Language", required = false) String locale) {
        return new AccessContext(tenantId, userId, permissions, personPublicId, groupRefs, locale);
    }

    @GetMapping("/personal-tasks")
    public ApiResponse<TaskPage> list(@Parameter(hidden = true) @ModelAttribute(value = "personalWorkContext", binding = false) AccessContext context,
            @RequestParam(required = false) Status status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.list(context, status, page, size));
    }

    @GetMapping("/personal-tasks/{taskId}")
    public ApiResponse<Task> get(@Parameter(hidden = true) @ModelAttribute(value = "personalWorkContext", binding = false) AccessContext context,
                                 @PathVariable UUID taskId) {
        return ApiResponse.success(service.get(context, taskId));
    }

    @PostMapping("/personal-tasks/source-preflight")
    public ApiResponse<SourceLink> preflightSource(
            @Parameter(hidden = true)
            @ModelAttribute(value = "personalWorkContext", binding = false) AccessContext context,
            @Valid @RequestBody SourceReference reference) {
        return ApiResponse.success(service.preflightSource(context, reference));
    }

    @PostMapping("/personal-tasks")
    public ApiResponse<Task> create(@Parameter(hidden = true) @ModelAttribute(value = "personalWorkContext", binding = false) AccessContext context,
            @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-DWP-Mail-Proposal-ID", required = false) UUID mailProposalId,
            @RequestHeader(value = "X-DWP-Mail-Command-ID", required = false) UUID mailCommandId,
            @RequestHeader(value = "X-DWP-Mail-Proposal-Version", required = false) Long mailProposalVersion,
            @Valid @RequestBody CreateTaskRequest request) {
        return ApiResponse.success(service.create(
                context, commandId, correlationId, request,
                MailProposalHandoffBinding.optional(
                        mailProposalId, mailCommandId, mailProposalVersion)));
    }

    @PutMapping("/personal-tasks/{taskId}")
    public ApiResponse<Task> update(@Parameter(hidden = true) @ModelAttribute(value = "personalWorkContext", binding = false) AccessContext context,
            @PathVariable UUID taskId, @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody UpdateTaskRequest request) {
        return ApiResponse.success(service.update(context, taskId, commandId, correlationId, request));
    }

    @PostMapping("/personal-tasks/{taskId}/status")
    public ApiResponse<Task> transition(@Parameter(hidden = true) @ModelAttribute(value = "personalWorkContext", binding = false) AccessContext context,
            @PathVariable UUID taskId, @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody StatusRequest request) {
        return ApiResponse.success(service.transition(context, taskId, commandId, correlationId, request));
    }

    @PostMapping("/personal-tasks/{taskId}/complete")
    public ApiResponse<Task> complete(@Parameter(hidden = true) @ModelAttribute(value = "personalWorkContext", binding = false) AccessContext context,
            @PathVariable UUID taskId, @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody VersionRequest request) {
        return ApiResponse.success(service.transition(context, taskId, commandId, correlationId,
                new StatusRequest(Status.COMPLETED, request.version())));
    }

    @PostMapping("/personal-tasks/{taskId}/reopen")
    public ApiResponse<Task> reopen(@Parameter(hidden = true) @ModelAttribute(value = "personalWorkContext", binding = false) AccessContext context,
            @PathVariable UUID taskId, @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody VersionRequest request) {
        return ApiResponse.success(service.transition(context, taskId, commandId, correlationId,
                new StatusRequest(Status.OPEN, request.version())));
    }

    @PostMapping("/personal-tasks/{taskId}/archive")
    public ApiResponse<Task> archive(@Parameter(hidden = true) @ModelAttribute(value = "personalWorkContext", binding = false) AccessContext context,
            @PathVariable UUID taskId, @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody VersionRequest request) {
        return ApiResponse.success(service.transition(context, taskId, commandId, correlationId,
                new StatusRequest(Status.ARCHIVED, request.version())));
    }

    @PostMapping("/personal-tasks/{taskId}/delete")
    public ApiResponse<DeleteResult> delete(@Parameter(hidden = true) @ModelAttribute(value = "personalWorkContext", binding = false) AccessContext context,
            @PathVariable UUID taskId, @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody VersionRequest request) {
        return ApiResponse.success(service.delete(context, taskId, commandId, correlationId, request));
    }

    @GetMapping("/personal-tasks/{taskId}/timeline")
    public ApiResponse<TimelinePage> timeline(@Parameter(hidden = true) @ModelAttribute(value = "personalWorkContext", binding = false) AccessContext context,
            @PathVariable UUID taskId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.timeline(context, taskId, page, size));
    }

    @GetMapping("/day-plans/{date}")
    public ApiResponse<DayPlan> dayPlan(@Parameter(hidden = true) @ModelAttribute(value = "personalWorkContext", binding = false) AccessContext context,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(service.dayPlan(context, date));
    }

    @PutMapping("/day-plans/{date}")
    public ApiResponse<DayPlan> replaceDayPlan(@Parameter(hidden = true) @ModelAttribute(value = "personalWorkContext", binding = false) AccessContext context,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody ReplaceDayPlanRequest request) {
        return ApiResponse.success(service.replaceDayPlan(context, date, commandId, correlationId, request));
    }
}
