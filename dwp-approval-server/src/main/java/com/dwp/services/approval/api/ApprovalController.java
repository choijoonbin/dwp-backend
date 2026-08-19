package com.dwp.services.approval.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class ApprovalController {

    private final ApprovalService service;

    public ApprovalController(ApprovalService service) {
        this.service = service;
    }

    @GetMapping("/home")
    public ApiResponse<ApprovalDtos.HomeResponse> home() {
        return ApiResponse.success(service.home());
    }

    @GetMapping("/tasks")
    public ApiResponse<List<ApprovalDtos.TaskSummary>> tasks(
            @RequestParam(defaultValue = "INBOX") String view,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(service.tasks(view, limit));
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
        return ApiResponse.success(service.decide(taskId, request, correlationId));
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
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.create(request, correlationId));
    }

    @PutMapping("/requests/{requestId}/draft")
    public ApiResponse<ApprovalDtos.RequestDetail> updateDraft(
            @PathVariable UUID requestId,
            @Valid @RequestBody ApprovalDtos.UpdateDraftRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.updateDraft(requestId, request, correlationId));
    }

    @PostMapping("/requests/{requestId}/submit")
    public ApiResponse<ApprovalDtos.RequestSummary> submit(
            @PathVariable UUID requestId,
            @Valid @RequestBody ApprovalDtos.VersionedActionRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.submit(requestId, request.expectedVersion(), correlationId));
    }

    @PostMapping("/requests/{requestId}/information-response")
    public ApiResponse<ApprovalDtos.RequestSummary> respondToInformationRequest(
            @PathVariable UUID requestId,
            @Valid @RequestBody ApprovalDtos.InformationResponseRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.respondToInformationRequest(requestId, request, correlationId));
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

    @PostMapping("/delegations/{delegationId}/revoke")
    public ApiResponse<List<ApprovalDtos.DelegationSummary>> revokeDelegation(
            @PathVariable UUID delegationId,
            @Valid @RequestBody ApprovalDtos.VersionedActionRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.revokeDelegation(
                delegationId, request.expectedVersion(), correlationId));
    }
}
