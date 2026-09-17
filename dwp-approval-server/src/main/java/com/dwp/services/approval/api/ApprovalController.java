package com.dwp.services.approval.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.domain.ApprovalDelegationUpdateRequest;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalDraftService;
import com.dwp.services.approval.domain.ApprovalResubmitDraftDtos;
import com.dwp.services.approval.domain.ApprovalService;
import com.dwp.services.approval.domain.ApprovalWorkflowQuorumInformationPending;
import com.dwp.services.approval.security.ApprovalWorkflowQuorumCommandMetadata;
import com.dwp.services.approval.security.ApprovalWorkflowQuorumCommandProof.Purpose;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class ApprovalController {

    public enum TaskView {
        INBOX,
        DELEGATED,
        COMPLETED
    }

    private final ApprovalService service;
    private final ApprovalDraftService drafts;
    private final ApprovalWorkflowQuorumCommandMetadata workflowMetadata;

    public ApprovalController(ApprovalService service, ApprovalDraftService drafts) {
        this(service, drafts, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ApprovalController(ApprovalService service, ApprovalDraftService drafts, ApprovalWorkflowQuorumCommandMetadata workflowMetadata) {
        this.service = service;
        this.drafts = drafts;
        this.workflowMetadata = workflowMetadata;
    }

    @GetMapping("/home")
    public ApiResponse<ApprovalDtos.HomeResponse> home() {
        return ApiResponse.success(service.home());
    }

    @GetMapping("/tasks")
    public ApiResponse<List<ApprovalDtos.TaskSummary>> tasks(
            @RequestParam(defaultValue = "INBOX") TaskView view,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(service.tasks(view.name(), limit));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<ApprovalDtos.TaskDetail> task(@PathVariable UUID taskId) {
        return ApiResponse.success(service.task(taskId));
    }

    @PostMapping("/tasks/{taskId}/claim")
    public ApiResponse<ApprovalDtos.TaskDetail> claim(
            @PathVariable UUID taskId,
            @Valid @RequestBody ApprovalDtos.VersionedActionRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.claim(taskId, request.expectedVersion(), correlationId));
    }

    @PostMapping("/tasks/{taskId}/decisions")
    public ApiResponse<ApprovalDtos.TaskDetail> decide(
            @PathVariable UUID taskId,
            @Valid @RequestBody ApprovalDtos.DecisionRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        try {
            if ("REQUEST_INFO".equalsIgnoreCase(request.decision()) && request.quorum() != null) {
                if (request.quorum().expectedRequestVersion() == null || request.quorum().expectedRequestVersion() < 0)
                    throw new com.dwp.core.exception.BaseException(com.dwp.core.common.ErrorCode.RESOURCE_CONFLICT);
                prepareWorkflowMetadata(Purpose.TASK_INFORMATION, taskId);
            }
            return ApiResponse.success(service.decide(taskId, request, correlationId,
                    com.dwp.services.approval.domain.ApprovalWorkflowQuorumBindings.expected(request.quorum())));
        } catch (ApprovalWorkflowQuorumInformationPending pending) {
            throw informationUnavailable();
        } finally { if (workflowMetadata != null) workflowMetadata.clear(); }
    }

    @GetMapping("/requests")
    public ApiResponse<List<ApprovalDtos.RequestSummary>> requests(
            @RequestParam(defaultValue = "SUBMITTED") String view,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(service.requests(view, limit));
    }

    @GetMapping("/requests/{requestId}")
    public ApiResponse<ApprovalDtos.RequestSummary> request(@PathVariable UUID requestId) {
        return ApiResponse.success(service.request(requestId));
    }

    @GetMapping("/requests/{requestId}/detail")
    public ApiResponse<ApprovalDtos.RequestDetail> requestDetail(@PathVariable UUID requestId) {
        return ApiResponse.success(service.requestDetail(requestId));
    }

    @PostMapping("/requests")
    public ApiResponse<ApprovalDtos.RequestSummary> create(
            @Valid @RequestBody ApprovalDtos.CreateRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(drafts.create(request, idempotencyKey, correlationId));
    }

    @PostMapping("/requests/{requestId}/resubmit-draft")
    @Operation(
            summary = "Create an owned resubmission draft from a terminal request",
            parameters = @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER,
                    required = true, schema = @Schema(type = "string", maxLength = 120,
                    pattern = "[A-Za-z0-9._:-]{1,120}")))
    public ResponseEntity<ApiResponse<ApprovalResubmitDraftDtos.Response>> resubmitDraft(
            @PathVariable UUID requestId,
            @Valid @RequestBody ApprovalResubmitDraftDtos.Request request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    drafts.resubmit(requestId, request, idempotencyKey, correlationId)));
        } catch (ApprovalDraftService.IncompatibleSourcePayload incompatible) {
            return ResponseEntity.unprocessableEntity().body(ApiResponse.error(
                    com.dwp.core.common.ErrorCode.INVALID_INPUT_VALUE,
                    incompatible.getMessage(),
                    correlationId));
        }
    }

    @PutMapping("/requests/{requestId}/draft")
    public ApiResponse<ApprovalDtos.RequestDetail> updateDraft(
            @PathVariable UUID requestId,
            @Valid @RequestBody ApprovalDtos.UpdateDraftRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(drafts.update(requestId, request, idempotencyKey, correlationId));
    }

    @PostMapping("/requests/{requestId}/submit")
    @Operation(parameters = @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER,
            description = "Original command identity is required for visible USER references in a published typed form. Never replace it on retry.",
            schema = @Schema(type = "string", pattern = "[A-Za-z0-9._:-]{1,120}", maxLength = 120)))
    public ApiResponse<ApprovalDtos.RequestSummary> submit(
            @PathVariable UUID requestId,
            @Valid @RequestBody ApprovalDtos.VersionedActionRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.submit(requestId, request.expectedVersion(), correlationId));
    }

    @PostMapping("/requests/{requestId}/preflight")
    @Operation(summary = "Evaluate the exact owned draft and current approver authority without submitting")
    public ApiResponse<ApprovalDtos.RequestPreflight> preflight(
            @PathVariable UUID requestId,
            @Valid @RequestBody ApprovalDtos.VersionedActionRequest request) {
        return ApiResponse.success(service.preflight(requestId, request.expectedVersion()));
    }

    @PostMapping("/requests/{requestId}/information-response")
    @Operation(parameters = @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER,
            description = "Original command identity is required for visible USER references in the immutable published typed form. Never replace it on retry.",
            schema = @Schema(type = "string", pattern = "[A-Za-z0-9._:-]{1,120}", maxLength = 120)))
    public ApiResponse<ApprovalDtos.RequestSummary> respondToInformationRequest(
            @PathVariable UUID requestId,
            @Valid @RequestBody ApprovalDtos.InformationResponseRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        try {
            if (request.sourceGeneration() != null) prepareWorkflowMetadata(Purpose.REQUEST_REPLY, requestId);
            return ApiResponse.success(service.respondToInformationRequest(requestId, request, correlationId));
        } catch (ApprovalWorkflowQuorumInformationPending pending) {
            throw informationUnavailable();
        } finally { if (workflowMetadata != null) workflowMetadata.clear(); }
    }

    private void prepareWorkflowMetadata(Purpose purpose, UUID target) {
        if (workflowMetadata == null) throw informationUnavailable();
        workflowMetadata.prepare(purpose, target);
    }
    private com.dwp.core.exception.BaseException informationUnavailable() {
        return new com.dwp.core.exception.BaseException(com.dwp.core.common.ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "Current information command authority is unavailable; retry with the original command identity.");
    }

    @PostMapping("/requests/{requestId}/withdraw")
    public ApiResponse<ApprovalDtos.RequestSummary> withdraw(
            @PathVariable UUID requestId,
            @Valid @RequestBody ApprovalDtos.VersionedActionRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.withdraw(requestId, request.expectedVersion(), correlationId));
    }

    @GetMapping("/workflows/published")
    public ApiResponse<List<ApprovalDtos.WorkflowSummary>> workflows() {
        return ApiResponse.success(service.publishedWorkflows());
    }

    @GetMapping("/workflows/published/{workflowId}/template")
    public ApiResponse<ApprovalDtos.RequestTemplate> workflowTemplate(
            @PathVariable UUID workflowId) {
        return ApiResponse.success(service.publishedTemplate(workflowId));
    }

    @GetMapping("/catalog/forms")
    public ApiResponse<List<ApprovalDtos.FormSummary>> formCatalog() {
        return ApiResponse.success(service.publishedForms());
    }

    @GetMapping("/catalog/forms/{formId}/template")
    public ApiResponse<ApprovalDtos.RequestTemplate> formTemplate(@PathVariable UUID formId) {
        return ApiResponse.success(service.publishedTemplateByForm(formId));
    }

    @GetMapping("/delegations")
    public ApiResponse<List<ApprovalDtos.DelegationSummary>> delegations() {
        return ApiResponse.success(service.delegations());
    }

    @GetMapping("/delegations/candidates")
    public ApiResponse<List<ApprovalDtos.DelegationCandidate>> delegationCandidates(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.success(service.delegationCandidates(query, limit));
    }

    @PostMapping("/delegations")
    public ApiResponse<List<ApprovalDtos.DelegationSummary>> createDelegation(
            @Valid @RequestBody ApprovalDtos.CreateDelegationRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.createDelegation(request, correlationId));
    }

    @Operation(parameters = @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER,
            required = true, schema = @Schema(type = "string", maxLength = 120,
            pattern = "[A-Za-z0-9._:-]{1,120}")))
    @PutMapping("/delegations/{delegationId}")
    public ApiResponse<List<ApprovalDtos.DelegationSummary>> updateDelegation(
            @PathVariable UUID delegationId,
            @Valid @RequestBody ApprovalDelegationUpdateRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.updateDelegation(
                delegationId, request, idempotencyKey, correlationId));
    }

    @PostMapping("/delegations/{delegationId}/revoke")
    public ApiResponse<List<ApprovalDtos.DelegationSummary>> revokeDelegation(
            @PathVariable UUID delegationId,
            @Valid @RequestBody ApprovalDtos.VersionedActionRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.revokeDelegation(
                delegationId, request.expectedVersion(), correlationId));
    }
}
