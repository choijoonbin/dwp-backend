package com.dwp.services.people.hr;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/v1/hr")
public class HrController {

    private final HrService service;

    public HrController(HrService service) {
        this.service = service;
    }

    @GetMapping("/home")
    public ApiResponse<HrDtos.HomeOverview> home() {
        return ApiResponse.success(service.home());
    }

    @GetMapping("/time")
    public ApiResponse<HrDtos.TimeWorkspace> time() {
        return ApiResponse.success(service.time());
    }

    @GetMapping("/team")
    public ApiResponse<HrDtos.TeamWorkspace> team() {
        return ApiResponse.success(service.team());
    }

    @GetMapping("/team/time")
    public ApiResponse<HrDtos.TeamTimeWorkspace> teamTime() {
        return ApiResponse.success(service.teamTime());
    }

    @PostMapping("/team/time/{cardId}/decision")
    public ApiResponse<HrDtos.ApprovalItem> decideTeamTimeCard(
            @PathVariable UUID cardId,
            @Valid @RequestBody HrDtos.DecisionRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(
                service.decideTeamTimeCard(cardId, request, correlationId));
    }

    @PutMapping("/time/{cardId}/entries/{workDate}")
    public ApiResponse<HrDtos.TimeWorkspace> upsertTimeEntry(
            @PathVariable UUID cardId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate workDate,
            @Valid @RequestBody HrDtos.UpsertTimeEntryRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.upsertTimeEntry(cardId, workDate, request, correlationId));
    }

    @PostMapping("/time/{cardId}/submit")
    public ApiResponse<HrDtos.TimeWorkspace> submitTimeCard(
            @PathVariable UUID cardId,
            @RequestParam @Min(0) long version,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.submitTimeCard(cardId, version, correlationId));
    }

    @PostMapping("/time/{cardId}/decision")
    public ApiResponse<HrDtos.ApprovalItem> decideTimeCard(
            @PathVariable UUID cardId,
            @Valid @RequestBody HrDtos.DecisionRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.decideTimeCard(cardId, request, correlationId));
    }

    @GetMapping("/absence")
    public ApiResponse<HrDtos.AbsenceWorkspace> absence() {
        return ApiResponse.success(service.absence());
    }

    @GetMapping("/team/absence")
    public ApiResponse<HrDtos.TeamAbsenceWorkspace> teamAbsence() {
        return ApiResponse.success(service.teamAbsence());
    }

    @PostMapping("/team/absence/{requestId}/decision")
    public ApiResponse<HrDtos.ApprovalItem> decideTeamLeaveRequest(
            @PathVariable UUID requestId,
            @Valid @RequestBody HrDtos.DecisionRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(
                service.decideTeamLeaveRequest(requestId, request, correlationId));
    }

    @PostMapping("/absence/requests")
    public ApiResponse<HrDtos.LeaveRequest> createLeaveRequest(
            @Valid @RequestBody HrDtos.CreateLeaveRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.createLeaveRequest(request, correlationId));
    }

    @PostMapping("/absence/requests/{requestId}/withdraw")
    public ApiResponse<HrDtos.AbsenceWorkspace> withdrawLeaveRequest(
            @PathVariable UUID requestId,
            @Valid @RequestBody HrDtos.WithdrawLeaveRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.withdrawLeaveRequest(requestId, request, correlationId));
    }

    @PostMapping("/absence/requests/{requestId}/decision")
    public ApiResponse<HrDtos.ApprovalItem> decideLeaveRequest(
            @PathVariable UUID requestId,
            @Valid @RequestBody HrDtos.DecisionRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.decideLeaveRequest(requestId, request, correlationId));
    }

    @GetMapping("/benefits")
    public ApiResponse<HrDtos.BenefitsWorkspace> benefits() {
        return ApiResponse.success(service.benefits());
    }

    @GetMapping("/pay")
    public ApiResponse<HrDtos.PayWorkspace> pay() {
        return ApiResponse.success(service.pay());
    }

    @GetMapping("/talent")
    public ApiResponse<HrDtos.TalentWorkspace> talent() {
        return ApiResponse.success(service.talent());
    }

    @PutMapping("/talent/goals/{goalId}")
    public ApiResponse<HrDtos.TalentWorkspace> updateGoal(
            @PathVariable UUID goalId,
            @Valid @RequestBody HrDtos.UpdateGoalRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.updateGoal(goalId, request, correlationId));
    }

    @GetMapping("/operations/{domain}")
    public ApiResponse<HrDtos.DomainOperations> operations(@PathVariable String domain) {
        return ApiResponse.success(service.operations(domain));
    }
}
