package com.dwp.services.people.hr.assignment;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.people.security.HcmStepUpHeaders;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/workforce")
public class AssignmentProposalController {

    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final AssignmentProposalService service;

    public AssignmentProposalController(AssignmentProposalService service) {
        this.service = service;
    }

    @GetMapping("/assignments/{assignmentId}")
    public ApiResponse<AssignmentProposalDtos.AssignmentDetail> assignment(
            @PathVariable UUID assignmentId) {
        return ApiResponse.success(service.assignment(assignmentId));
    }

    @GetMapping("/assignments/{assignmentId}/timeline")
    public ApiResponse<List<AssignmentProposalDtos.TimelineEntry>> timeline(
            @PathVariable UUID assignmentId) {
        return ApiResponse.success(service.timeline(assignmentId));
    }

    @GetMapping("/assignment-proposals/{proposalId}")
    public ApiResponse<AssignmentProposalDtos.Proposal> proposal(
            @PathVariable UUID proposalId) {
        return ApiResponse.success(service.proposal(proposalId));
    }

    @PostMapping("/assignment-proposals")
    public ApiResponse<AssignmentProposalDtos.CommandResult> create(
            @RequestHeader(HcmStepUpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody AssignmentProposalDtos.CreateRequest request) {
        return ApiResponse.success(service.create(request, idempotencyKey, correlationId));
    }

    @PostMapping("/assignment-proposals/{proposalId}/validate")
    public ApiResponse<AssignmentProposalDtos.CommandResult> validate(
            @PathVariable UUID proposalId,
            @RequestHeader(HcmStepUpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody AssignmentProposalDtos.VersionCommand request) {
        return ApiResponse.success(service.validate(
                proposalId, request, idempotencyKey, correlationId));
    }

    @PostMapping("/assignment-proposals/{proposalId}/submit")
    public ApiResponse<AssignmentProposalDtos.CommandResult> submit(
            @PathVariable UUID proposalId,
            @RequestHeader(HcmStepUpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestHeader(value = HcmStepUpHeaders.CHALLENGE, required = false)
            String challenge,
            @RequestHeader(value = HcmStepUpHeaders.DECISION_REVISION, required = false)
            String decisionRevision,
            @RequestHeader(value = HcmStepUpHeaders.EXPECTED_OBJECT_VERSION, required = false)
            Long expectedObjectVersion,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody AssignmentProposalDtos.VersionCommand request) {
        return ApiResponse.success(service.submit(
                proposalId, request, idempotencyKey, correlationId,
                new HcmStepUpHeaders(
                        challenge, idempotencyKey, decisionRevision, expectedObjectVersion)));
    }

    @PostMapping("/assignment-proposals/{proposalId}/cancel")
    public ApiResponse<AssignmentProposalDtos.CommandResult> cancel(
            @PathVariable UUID proposalId,
            @RequestHeader(HcmStepUpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody AssignmentProposalDtos.CancelCommand request) {
        return ApiResponse.success(service.cancel(
                proposalId, request, idempotencyKey, correlationId));
    }
}
