package com.dwp.services.approval.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalProjectionDtos;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/** OpenAPI-only response envelopes for server-selected Approval projections. */
public final class ApprovalProjectionOpenApi {

    private ApprovalProjectionOpenApi() {
    }

    @Schema(name = "ApprovalFullOverviewResponse")
    public static final class FullOverviewResponse
            extends ApiResponse<ApprovalDtos.AdminPulse> {
    }

    @Schema(name = "ApprovalOversightOverviewResponse")
    public static final class OversightOverviewResponse
            extends ApiResponse<ApprovalProjectionDtos.OversightAdminPulseV1> {
    }

    @Schema(name = "ApprovalFullWorkflowListResponse")
    public static final class FullWorkflowListResponse
            extends ApiResponse<List<ApprovalDtos.WorkflowSummary>> {
    }

    @Schema(name = "ApprovalOversightWorkflowListResponse")
    public static final class OversightWorkflowListResponse
            extends ApiResponse<List<ApprovalProjectionDtos.OversightWorkflowV1>> {
    }

    @Schema(name = "ApprovalFullWorkflowDetailResponse")
    public static final class FullWorkflowDetailResponse
            extends ApiResponse<ApprovalDtos.WorkflowDetail> {
    }

    @Schema(name = "ApprovalOversightWorkflowDetailResponse")
    public static final class OversightWorkflowDetailResponse
            extends ApiResponse<ApprovalProjectionDtos.OversightWorkflowV1> {
    }

    @Schema(name = "ApprovalFullFormListResponse")
    public static final class FullFormListResponse
            extends ApiResponse<List<ApprovalDtos.FormSummary>> {
    }

    @Schema(name = "ApprovalOversightFormListResponse")
    public static final class OversightFormListResponse
            extends ApiResponse<List<ApprovalProjectionDtos.OversightFormV1>> {
    }

    @Schema(name = "ApprovalFullFormDetailResponse")
    public static final class FullFormDetailResponse
            extends ApiResponse<ApprovalDtos.FormDetail> {
    }

    @Schema(name = "ApprovalOversightFormDetailResponse")
    public static final class OversightFormDetailResponse
            extends ApiResponse<ApprovalProjectionDtos.OversightFormV1> {
    }

    @Schema(name = "ApprovalFullFormCategoryListResponse")
    public static final class FullFormCategoryListResponse
            extends ApiResponse<List<ApprovalDtos.FormCategorySummary>> {
    }

    @Schema(name = "ApprovalOversightFormCategoryListResponse")
    public static final class OversightFormCategoryListResponse
            extends ApiResponse<List<ApprovalProjectionDtos.OversightFormCategoryV1>> {
    }

    @Schema(name = "ApprovalFullPolicyListResponse")
    public static final class FullPolicyListResponse
            extends ApiResponse<List<ApprovalDtos.PolicySummary>> {
    }

    @Schema(name = "ApprovalOversightPolicyListResponse")
    public static final class OversightPolicyListResponse
            extends ApiResponse<List<ApprovalProjectionDtos.OversightPolicyV1>> {
    }

    @Schema(name = "ApprovalFullPolicyVersionListResponse")
    public static final class FullPolicyVersionListResponse
            extends ApiResponse<List<ApprovalDtos.PolicyVersionSummary>> {
    }

    @Schema(name = "ApprovalOversightPolicyVersionListResponse")
    public static final class OversightPolicyVersionListResponse
            extends ApiResponse<List<ApprovalProjectionDtos.OversightPolicyVersionV1>> {
    }

    @Schema(name = "ApprovalFullOperationsResponse")
    public static final class FullOperationsResponse
            extends ApiResponse<ApprovalDtos.OperationsResponse> {
    }

    @Schema(name = "ApprovalOversightOperationsResponse")
    public static final class OversightOperationsResponse
            extends ApiResponse<ApprovalProjectionDtos.OversightOperationsV1> {
    }

    @Schema(name = "ApprovalAuditorOperationsResponse")
    public static final class AuditorOperationsResponse
            extends ApiResponse<ApprovalProjectionDtos.AuditorOperationsV1> {
    }

    @Schema(name = "ApprovalFullSignatureListResponse")
    public static final class FullSignatureListResponse
            extends ApiResponse<List<ApprovalProjectionDtos.FullManagementSignatureV1>> {
    }

    @Schema(name = "ApprovalOversightSignatureListResponse")
    public static final class OversightSignatureListResponse
            extends ApiResponse<List<ApprovalProjectionDtos.OversightSignatureV1>> {
    }

    @Schema(name = "ApprovalWorkflowPublishResponse")
    public static final class WorkflowPublishResponse
            extends ApiResponse<List<ApprovalDtos.WorkflowSummary>> {
    }

    @Schema(name = "ApprovalFormPublishResponse")
    public static final class FormPublishResponse
            extends ApiResponse<ApprovalDtos.FormDetail> {
    }

    @Schema(name = "ApprovalPolicyPublishResponse")
    public static final class PolicyPublishResponse
            extends ApiResponse<List<ApprovalDtos.PolicySummary>> {
    }

    @Schema(name = "ApprovalRecoveryExecuteResponse")
    public static final class RecoveryExecuteResponse
            extends ApiResponse<ApprovalDtos.OperationsResponse> {
    }

    @Schema(name = "ApprovalGovernedForbiddenError",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record GovernedForbiddenError(
            String status,
            String message,
            @Schema(allowableValues = {"STEP_UP_REQUIRED", "SOD_CONFLICT", "E2001"})
            String errorCode,
            LocalDateTime timestamp,
            Boolean success,
            String correlationId) {
    }

    @Schema(name = "ApprovalGovernedConflictError",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record GovernedConflictError(
            String status,
            String message,
            @Schema(allowableValues = {
                    "STEP_UP_CHALLENGE_MISMATCH", "STEP_UP_CHALLENGE_REPLAY",
                    "DECISION_REVISION_CONFLICT", "OBJECT_VERSION_CONFLICT", "E1009"})
            String errorCode,
            LocalDateTime timestamp,
            Boolean success,
            String correlationId) {
    }

    @Schema(name = "ApprovalGovernedValidationError",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record GovernedValidationError(
            String status,
            String message,
            @Schema(allowableValues = {"E1001", "E4000", "E4002"}) String errorCode,
            LocalDateTime timestamp,
            Boolean success,
            String correlationId) {
    }

    @Schema(name = "ApprovalAuthorityUnavailableError",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record AuthorityUnavailableError(
            String status,
            String message,
            @Schema(allowableValues = {"AUTHORITY_RESOLUTION_UNAVAILABLE"}) String errorCode,
            LocalDateTime timestamp,
            Boolean success,
            String correlationId) {
    }
}
