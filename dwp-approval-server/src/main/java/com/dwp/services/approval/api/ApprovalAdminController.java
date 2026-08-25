package com.dwp.services.approval.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalResponseProjection;
import com.dwp.services.approval.domain.ApprovalService;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
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

    private static final String STEP_UP = "X-DWP-Step-Up-Challenge";
    private static final String IDEMPOTENCY = "Idempotency-Key";
    private static final String DECISION_REVISION = "X-DWP-Expected-Decision-Revision";
    private static final String OBJECT_VERSION = "X-DWP-Expected-Object-Version";
    private static final String CONDITIONAL_HIGH_HEADER =
            "Required and fail-closed for product-authorization rollout states 110/111; "
                    + "optional for backward-compatible baseline/shadow states 000/100.";

    private final ApprovalService service;
    private final ApprovalResponseProjection projection;

    public ApprovalAdminController(
            ApprovalService service,
            ApprovalResponseProjection projection) {
        this.service = service;
        this.projection = projection;
    }

    @GetMapping("/overview")
    @Operation(responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Server-selected full or oversight projection",
            content = @Content(schema = @Schema(oneOf = {
                    ApprovalProjectionOpenApi.FullOverviewResponse.class,
                    ApprovalProjectionOpenApi.OversightOverviewResponse.class}))))
    public ApiResponse<Object> overview() {
        return ApiResponse.success(projection.overview(service.adminOverview()));
    }

    @GetMapping("/workflows")
    @Operation(responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Server-selected full or oversight projection",
            content = @Content(schema = @Schema(oneOf = {
                    ApprovalProjectionOpenApi.FullWorkflowListResponse.class,
                    ApprovalProjectionOpenApi.OversightWorkflowListResponse.class}))))
    public ApiResponse<Object> workflows() {
        return ApiResponse.success(projection.workflows(service.workflows()));
    }

    @GetMapping("/workflows/{workflowId}")
    @Operation(responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Server-selected full or oversight projection",
            content = @Content(schema = @Schema(oneOf = {
                    ApprovalProjectionOpenApi.FullWorkflowDetailResponse.class,
                    ApprovalProjectionOpenApi.OversightWorkflowDetailResponse.class}))))
    public ApiResponse<Object> workflow(@PathVariable UUID workflowId) {
        return ApiResponse.success(projection.workflow(service.workflow(workflowId)));
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
    @Operation(responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Workflow published",
                    content = @Content(schema = @Schema(
                            implementation = ApprovalProjectionOpenApi.WorkflowPublishResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "Step-up or SoD denied",
                    content = @Content(schema = @Schema(
                            implementation = ApprovalProjectionOpenApi.GovernedForbiddenError.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "Challenge, revision, replay, or object conflict",
                    content = @Content(schema = @Schema(
                            implementation = ApprovalProjectionOpenApi.GovernedConflictError.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422", description = "Command validation failed",
                    content = @Content(schema = @Schema(
                            implementation = ApprovalProjectionOpenApi.GovernedValidationError.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503", description = "Authority evidence unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApprovalProjectionOpenApi.AuthorityUnavailableError.class)))})
    public ApiResponse<List<ApprovalDtos.WorkflowSummary>> publishWorkflow(
            @PathVariable UUID workflowId,
            @Valid @RequestBody ApprovalDtos.PublishWorkflowRequest request,
            @Parameter(description = CONDITIONAL_HIGH_HEADER, extensions = @Extension(
                    name = "x-dwp-conditional-required", properties = {
                            @ExtensionProperty(name = "rolloutStates", value = "[\"110\",\"111\"]", parseValue = true),
                            @ExtensionProperty(name = "enforcement", value = "FAIL_CLOSED")}))
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @Parameter(description = CONDITIONAL_HIGH_HEADER, extensions = @Extension(
                    name = "x-dwp-conditional-required", properties = {
                            @ExtensionProperty(name = "rolloutStates", value = "[\"110\",\"111\"]", parseValue = true),
                            @ExtensionProperty(name = "enforcement", value = "FAIL_CLOSED")}))
            @RequestHeader(value = IDEMPOTENCY, required = false) String idempotencyKey,
            @Parameter(description = CONDITIONAL_HIGH_HEADER, extensions = @Extension(
                    name = "x-dwp-conditional-required", properties = {
                            @ExtensionProperty(name = "rolloutStates", value = "[\"110\",\"111\"]", parseValue = true),
                            @ExtensionProperty(name = "enforcement", value = "FAIL_CLOSED")}))
            @RequestHeader(value = DECISION_REVISION, required = false) String decisionRevision,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(
                service.publishWorkflow(
                        workflowId, request.expectedVersion(), correlationId,
                        ApprovalStepUpHeaders.of(
                                stepUp, idempotencyKey, decisionRevision, request.expectedVersion())));
    }

    @GetMapping("/forms")
    @Operation(responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Server-selected full or oversight projection",
            content = @Content(schema = @Schema(oneOf = {
                    ApprovalProjectionOpenApi.FullFormListResponse.class,
                    ApprovalProjectionOpenApi.OversightFormListResponse.class}))))
    public ApiResponse<Object> forms() {
        return ApiResponse.success(projection.forms(service.forms()));
    }

    @GetMapping("/form-categories")
    @Operation(responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Server-selected full or oversight projection",
            content = @Content(schema = @Schema(oneOf = {
                    ApprovalProjectionOpenApi.FullFormCategoryListResponse.class,
                    ApprovalProjectionOpenApi.OversightFormCategoryListResponse.class}))))
    public ApiResponse<Object> formCategories() {
        return ApiResponse.success(projection.formCategories(service.formCategories()));
    }

    @PostMapping("/form-categories")
    public ApiResponse<List<ApprovalDtos.FormCategorySummary>> createFormCategory(
            @Valid @RequestBody ApprovalDtos.CreateFormCategoryRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.createFormCategory(request, correlationId));
    }

    @PutMapping("/form-categories/{categoryId}")
    public ApiResponse<List<ApprovalDtos.FormCategorySummary>> updateFormCategory(
            @PathVariable UUID categoryId,
            @Valid @RequestBody ApprovalDtos.UpdateFormCategoryRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.updateFormCategory(categoryId, request, correlationId));
    }

    @PostMapping("/forms")
    public ApiResponse<ApprovalDtos.FormDetail> createFormDraft(
            @Valid @RequestBody ApprovalDtos.CreateFormDraftRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.createFormDraft(request, correlationId));
    }

    @GetMapping("/forms/{formId}")
    @Operation(responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Server-selected full or oversight projection",
            content = @Content(schema = @Schema(oneOf = {
                    ApprovalProjectionOpenApi.FullFormDetailResponse.class,
                    ApprovalProjectionOpenApi.OversightFormDetailResponse.class}))))
    public ApiResponse<Object> form(@PathVariable UUID formId) {
        return ApiResponse.success(projection.form(service.form(formId)));
    }

    @PutMapping("/forms/{formId}/draft")
    public ApiResponse<ApprovalDtos.FormDetail> updateFormDraft(
            @PathVariable UUID formId,
            @Valid @RequestBody ApprovalDtos.UpdateFormDraftRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.updateFormDraft(formId, request, correlationId));
    }

    @PostMapping("/forms/{formId}/publish")
    @Operation(responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Form published",
                    content = @Content(schema = @Schema(
                            implementation = ApprovalProjectionOpenApi.FormPublishResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "Step-up or SoD denied",
                    content = @Content(schema = @Schema(
                            implementation = ApprovalProjectionOpenApi.GovernedForbiddenError.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "Challenge, revision, replay, or object conflict",
                    content = @Content(schema = @Schema(
                            implementation = ApprovalProjectionOpenApi.GovernedConflictError.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422", description = "Command validation failed",
                    content = @Content(schema = @Schema(
                            implementation = ApprovalProjectionOpenApi.GovernedValidationError.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503", description = "Authority evidence unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApprovalProjectionOpenApi.AuthorityUnavailableError.class)))})
    public ApiResponse<ApprovalDtos.FormDetail> publishForm(
            @PathVariable UUID formId,
            @Valid @RequestBody ApprovalDtos.PublishFormRequest request,
            @Parameter(description = CONDITIONAL_HIGH_HEADER, extensions = @Extension(
                    name = "x-dwp-conditional-required", properties = {
                            @ExtensionProperty(name = "rolloutStates", value = "[\"110\",\"111\"]", parseValue = true),
                            @ExtensionProperty(name = "enforcement", value = "FAIL_CLOSED")}))
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @Parameter(description = CONDITIONAL_HIGH_HEADER, extensions = @Extension(
                    name = "x-dwp-conditional-required", properties = {
                            @ExtensionProperty(name = "rolloutStates", value = "[\"110\",\"111\"]", parseValue = true),
                            @ExtensionProperty(name = "enforcement", value = "FAIL_CLOSED")}))
            @RequestHeader(value = IDEMPOTENCY, required = false) String idempotencyKey,
            @Parameter(description = CONDITIONAL_HIGH_HEADER, extensions = @Extension(
                    name = "x-dwp-conditional-required", properties = {
                            @ExtensionProperty(name = "rolloutStates", value = "[\"110\",\"111\"]", parseValue = true),
                            @ExtensionProperty(name = "enforcement", value = "FAIL_CLOSED")}))
            @RequestHeader(value = DECISION_REVISION, required = false) String decisionRevision,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.publishForm(
                formId, request.expectedVersion(), correlationId,
                ApprovalStepUpHeaders.of(
                        stepUp, idempotencyKey, decisionRevision, request.expectedVersion())));
    }

    @GetMapping("/policies")
    @Operation(responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Server-selected full or oversight projection",
            content = @Content(schema = @Schema(oneOf = {
                    ApprovalProjectionOpenApi.FullPolicyListResponse.class,
                    ApprovalProjectionOpenApi.OversightPolicyListResponse.class}))))
    public ApiResponse<Object> policies() {
        return ApiResponse.success(projection.policies(service.policies()));
    }

    @PutMapping("/policies/{policyId}")
    public ApiResponse<List<ApprovalDtos.PolicySummary>> updatePolicy(
            @PathVariable UUID policyId,
            @Valid @RequestBody ApprovalDtos.UpdatePolicyRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.updatePolicy(policyId, request, correlationId));
    }

    @PostMapping("/policies/{policyId}/publish")
    @Operation(responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Policy published",
                    content = @Content(schema = @Schema(
                            implementation = ApprovalProjectionOpenApi.PolicyPublishResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "Step-up or SoD denied",
                    content = @Content(schema = @Schema(
                            implementation = ApprovalProjectionOpenApi.GovernedForbiddenError.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "Challenge, revision, replay, or object conflict",
                    content = @Content(schema = @Schema(
                            implementation = ApprovalProjectionOpenApi.GovernedConflictError.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422", description = "Command validation failed",
                    content = @Content(schema = @Schema(
                            implementation = ApprovalProjectionOpenApi.GovernedValidationError.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503", description = "Authority evidence unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApprovalProjectionOpenApi.AuthorityUnavailableError.class)))})
    public ApiResponse<List<ApprovalDtos.PolicySummary>> publishPolicy(
            @PathVariable UUID policyId,
            @Valid @RequestBody ApprovalDtos.PublishPolicyRequest request,
            @Parameter(description = CONDITIONAL_HIGH_HEADER, extensions = @Extension(
                    name = "x-dwp-conditional-required", properties = {
                            @ExtensionProperty(name = "rolloutStates", value = "[\"110\",\"111\"]", parseValue = true),
                            @ExtensionProperty(name = "enforcement", value = "FAIL_CLOSED")}))
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @Parameter(description = CONDITIONAL_HIGH_HEADER, extensions = @Extension(
                    name = "x-dwp-conditional-required", properties = {
                            @ExtensionProperty(name = "rolloutStates", value = "[\"110\",\"111\"]", parseValue = true),
                            @ExtensionProperty(name = "enforcement", value = "FAIL_CLOSED")}))
            @RequestHeader(value = IDEMPOTENCY, required = false) String idempotencyKey,
            @Parameter(description = CONDITIONAL_HIGH_HEADER, extensions = @Extension(
                    name = "x-dwp-conditional-required", properties = {
                            @ExtensionProperty(name = "rolloutStates", value = "[\"110\",\"111\"]", parseValue = true),
                            @ExtensionProperty(name = "enforcement", value = "FAIL_CLOSED")}))
            @RequestHeader(value = DECISION_REVISION, required = false) String decisionRevision,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.publishPolicy(
                policyId, request, correlationId,
                ApprovalStepUpHeaders.of(
                        stepUp, idempotencyKey, decisionRevision, request.expectedVersion())));
    }

    @GetMapping("/policies/{policyId}/versions")
    @Operation(responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Server-selected full or oversight projection",
            content = @Content(schema = @Schema(oneOf = {
                    ApprovalProjectionOpenApi.FullPolicyVersionListResponse.class,
                    ApprovalProjectionOpenApi.OversightPolicyVersionListResponse.class}))))
    public ApiResponse<Object> policyVersions(
            @PathVariable UUID policyId) {
        return ApiResponse.success(projection.policyVersions(service.policyVersions(policyId)));
    }

    @GetMapping("/operations")
    @Operation(responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Server-selected full, auditor, or oversight projection",
            content = @Content(schema = @Schema(oneOf = {
                    ApprovalProjectionOpenApi.FullOperationsResponse.class,
                    ApprovalProjectionOpenApi.AuditorOperationsResponse.class,
                    ApprovalProjectionOpenApi.OversightOperationsResponse.class}))))
    public ApiResponse<Object> operations() {
        return ApiResponse.success(projection.operations(service.operations()));
    }

    @PostMapping("/operations/events/{outboxId}/retry")
    @Operation(responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Recovery command executed",
                    content = @Content(schema = @Schema(
                            implementation = ApprovalProjectionOpenApi.RecoveryExecuteResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "Step-up or SoD denied",
                    content = @Content(schema = @Schema(
                            implementation = ApprovalProjectionOpenApi.GovernedForbiddenError.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "Challenge, revision, replay, or object conflict",
                    content = @Content(schema = @Schema(
                            implementation = ApprovalProjectionOpenApi.GovernedConflictError.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422", description = "Command validation failed",
                    content = @Content(schema = @Schema(
                            implementation = ApprovalProjectionOpenApi.GovernedValidationError.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503", description = "Authority evidence unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApprovalProjectionOpenApi.AuthorityUnavailableError.class)))})
    public ApiResponse<ApprovalDtos.OperationsResponse> retryIntegrationDelivery(
            @PathVariable UUID outboxId,
            @Parameter(description = CONDITIONAL_HIGH_HEADER, extensions = @Extension(
                    name = "x-dwp-conditional-required", properties = {
                            @ExtensionProperty(name = "rolloutStates", value = "[\"110\",\"111\"]", parseValue = true),
                            @ExtensionProperty(name = "enforcement", value = "FAIL_CLOSED")}))
            @RequestHeader(value = OBJECT_VERSION, required = false) Long expectedVersion,
            @Parameter(description = CONDITIONAL_HIGH_HEADER, extensions = @Extension(
                    name = "x-dwp-conditional-required", properties = {
                            @ExtensionProperty(name = "rolloutStates", value = "[\"110\",\"111\"]", parseValue = true),
                            @ExtensionProperty(name = "enforcement", value = "FAIL_CLOSED")}))
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @Parameter(description = CONDITIONAL_HIGH_HEADER, extensions = @Extension(
                    name = "x-dwp-conditional-required", properties = {
                            @ExtensionProperty(name = "rolloutStates", value = "[\"110\",\"111\"]", parseValue = true),
                            @ExtensionProperty(name = "enforcement", value = "FAIL_CLOSED")}))
            @RequestHeader(value = IDEMPOTENCY, required = false) String idempotencyKey,
            @Parameter(description = CONDITIONAL_HIGH_HEADER, extensions = @Extension(
                    name = "x-dwp-conditional-required", properties = {
                            @ExtensionProperty(name = "rolloutStates", value = "[\"110\",\"111\"]", parseValue = true),
                            @ExtensionProperty(name = "enforcement", value = "FAIL_CLOSED")}))
            @RequestHeader(value = DECISION_REVISION, required = false) String decisionRevision,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.retryIntegrationDelivery(
                outboxId, expectedVersion, correlationId,
                ApprovalStepUpHeaders.of(
                        stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @GetMapping("/signatures")
    @Operation(responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Credential-safe full or oversight projection",
            content = @Content(schema = @Schema(oneOf = {
                    ApprovalProjectionOpenApi.FullSignatureListResponse.class,
                    ApprovalProjectionOpenApi.OversightSignatureListResponse.class}))))
    public ApiResponse<Object> signatures() {
        return ApiResponse.success(projection.signatures(service.signatures()));
    }
}
