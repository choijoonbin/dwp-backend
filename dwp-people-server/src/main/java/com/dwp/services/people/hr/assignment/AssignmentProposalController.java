package com.dwp.services.people.hr.assignment;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.people.security.HcmStepUpHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
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
@RequestMapping(value = "/v1/workforce", produces = MediaType.APPLICATION_JSON_VALUE)
public class AssignmentProposalController {

    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final AssignmentProposalService service;

    public AssignmentProposalController(AssignmentProposalService service) {
        this.service = service;
    }

    @Operation(operationId = "getAssignment")
    @GetMapping("/assignments/{assignmentId}")
    public ApiResponse<AssignmentProposalDtos.AssignmentDetail> assignment(
            @PathVariable UUID assignmentId) {
        return ApiResponse.success(service.assignment(assignmentId));
    }

    @Operation(operationId = "getAssignmentTimeline")
    @GetMapping("/assignments/{assignmentId}/timeline")
    public ApiResponse<List<AssignmentProposalDtos.TimelineEntry>> timeline(
            @PathVariable UUID assignmentId) {
        return ApiResponse.success(service.timeline(assignmentId));
    }

    @Operation(operationId = "getAssignmentProposal")
    @GetMapping("/assignment-proposals/{proposalId}")
    public ApiResponse<AssignmentProposalDtos.Proposal> proposal(
            @PathVariable UUID proposalId) {
        return ApiResponse.success(service.proposal(proposalId));
    }

    @Operation(operationId = "createAssignmentProposal")
    @PostMapping("/assignment-proposals")
    public ApiResponse<AssignmentProposalDtos.CommandResult> create(
            @Parameter(
                    required = true,
                    schema = @Schema(type = "string", minLength = 1, maxLength = 200))
            @RequestHeader(HcmStepUpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody AssignmentProposalDtos.CreateRequest request) {
        return ApiResponse.success(service.create(request, idempotencyKey, correlationId));
    }

    @Operation(operationId = "validateAssignmentProposal")
    @PostMapping("/assignment-proposals/{proposalId}/validate")
    public ApiResponse<AssignmentProposalDtos.CommandResult> validate(
            @PathVariable UUID proposalId,
            @Parameter(
                    required = true,
                    schema = @Schema(type = "string", minLength = 1, maxLength = 200))
            @RequestHeader(HcmStepUpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody AssignmentProposalDtos.VersionCommand request) {
        return ApiResponse.success(service.validate(
                proposalId, request, idempotencyKey, correlationId));
    }

    @Operation(operationId = "submitAssignmentProposal")
    @PostMapping("/assignment-proposals/{proposalId}/submit")
    public ApiResponse<AssignmentProposalDtos.CommandResult> submit(
            @PathVariable UUID proposalId,
            @Parameter(
                    required = true,
                    schema = @Schema(type = "string", minLength = 1, maxLength = 200))
            @RequestHeader(HcmStepUpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @Parameter(
                    required = true,
                    schema = @Schema(type = "string", minLength = 1))
            @RequestHeader(value = HcmStepUpHeaders.CHALLENGE, required = false)
            String challenge,
            @Parameter(
                    required = true,
                    schema = @Schema(type = "string", minLength = 1, maxLength = 200))
            @RequestHeader(value = HcmStepUpHeaders.DECISION_REVISION, required = false)
            String decisionRevision,
            @Parameter(
                    required = true,
                    schema = @Schema(type = "integer", format = "int64", minimum = "0"))
            @RequestHeader(value = HcmStepUpHeaders.EXPECTED_OBJECT_VERSION, required = false)
            Long expectedObjectVersion,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody AssignmentProposalDtos.VersionCommand request) {
        return ApiResponse.success(service.submit(
                proposalId, request, idempotencyKey, correlationId,
                new HcmStepUpHeaders(
                        challenge, idempotencyKey, decisionRevision, expectedObjectVersion)));
    }

    @Operation(operationId = "cancelAssignmentProposal")
    @PostMapping("/assignment-proposals/{proposalId}/cancel")
    public ApiResponse<AssignmentProposalDtos.CommandResult> cancel(
            @PathVariable UUID proposalId,
            @Parameter(
                    required = true,
                    schema = @Schema(type = "string", minLength = 1, maxLength = 200))
            @RequestHeader(HcmStepUpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody AssignmentProposalDtos.CancelCommand request) {
        return ApiResponse.success(service.cancel(
                proposalId, request, idempotencyKey, correlationId));
    }
}
