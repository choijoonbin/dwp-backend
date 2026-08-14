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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin")
public class ApprovalAdminController {

    private final ApprovalService service;

    public ApprovalAdminController(ApprovalService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiResponse<ApprovalDtos.AdminPulse> overview() {
        return ApiResponse.success(service.adminOverview());
    }

    @GetMapping("/workflows")
    public ApiResponse<List<ApprovalDtos.WorkflowSummary>> workflows() {
        return ApiResponse.success(service.workflows());
    }

    @GetMapping("/workflows/{workflowId}")
    public ApiResponse<ApprovalDtos.WorkflowDetail> workflow(@PathVariable UUID workflowId) {
        return ApiResponse.success(service.workflow(workflowId));
    }

    @PostMapping("/workflows")
    public ApiResponse<ApprovalDtos.WorkflowDetail> createWorkflowDraft(
            @Valid @RequestBody ApprovalDtos.CreateWorkflowDraftRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.createWorkflowDraft(request, correlationId));
    }

    @PutMapping("/workflows/{workflowId}/draft")
    public ApiResponse<ApprovalDtos.WorkflowDetail> updateWorkflowDraft(
            @PathVariable UUID workflowId,
            @Valid @RequestBody ApprovalDtos.UpdateWorkflowDraftRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.updateWorkflowDraft(workflowId, request, correlationId));
    }

    @PostMapping("/workflows/{workflowId}/publish")
    public ApiResponse<List<ApprovalDtos.WorkflowSummary>> publishWorkflow(
            @PathVariable UUID workflowId,
            @Valid @RequestBody ApprovalDtos.PublishWorkflowRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(
                service.publishWorkflow(workflowId, request.expectedVersion(), correlationId));
    }

    @GetMapping("/forms")
    public ApiResponse<List<ApprovalDtos.FormSummary>> forms() {
        return ApiResponse.success(service.forms());
    }

    @GetMapping("/forms/{formId}")
    public ApiResponse<ApprovalDtos.FormDetail> form(@PathVariable UUID formId) {
        return ApiResponse.success(service.form(formId));
    }

    @PutMapping("/forms/{formId}/draft")
    public ApiResponse<ApprovalDtos.FormDetail> updateFormDraft(
            @PathVariable UUID formId,
            @Valid @RequestBody ApprovalDtos.UpdateFormDraftRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.updateFormDraft(formId, request, correlationId));
    }

    @GetMapping("/policies")
    public ApiResponse<List<ApprovalDtos.PolicySummary>> policies() {
        return ApiResponse.success(service.policies());
    }

    @PutMapping("/policies/{policyId}")
    public ApiResponse<List<ApprovalDtos.PolicySummary>> updatePolicy(
            @PathVariable UUID policyId,
            @Valid @RequestBody ApprovalDtos.UpdatePolicyRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.updatePolicy(policyId, request, correlationId));
    }

    @GetMapping("/operations")
    public ApiResponse<ApprovalDtos.OperationsResponse> operations() {
        return ApiResponse.success(service.operations());
    }

    @GetMapping("/signatures")
    public ApiResponse<List<ApprovalDtos.SignatureProviderSummary>> signatures() {
        return ApiResponse.success(service.signatures());
    }
}
