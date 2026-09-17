package com.dwp.services.approval.deployment;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.dwp.services.approval.deployment.ApprovalDeploymentApiDtos.*;
import static com.dwp.services.approval.deployment.ApprovalDeploymentModels.*;

@RestController
@RequestMapping("/v1/admin/operations/deployments")
@Tag(name = "Approval asset deployments")
public class ApprovalDeploymentController {
    private static final String IDEMPOTENCY = "Idempotency-Key";
    private static final String STEP_UP = "X-DWP-Step-Up-Challenge";
    private static final String DECISION_REVISION = "X-DWP-Expected-Decision-Revision";
    private static final String OBJECT_VERSION = "X-DWP-Expected-Object-Version";

    private final ApprovalDeploymentService service;
    private final ApprovalDeploymentCommandFacade commands;
    private final ApprovalDeploymentHttpAuthority authority;

    public ApprovalDeploymentController(
            ApprovalDeploymentService service,
            ApprovalDeploymentCommandFacade commands,
            ApprovalDeploymentHttpAuthority authority) {
        this.service = service;
        this.commands = commands;
        this.authority = authority;
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Read deployment environment heads and recent promotions")
    public ApiResponse<DeploymentDashboard> dashboard() {
        return ApiResponse.success(service.dashboard(authority.readScope()));
    }

    @GetMapping("/packages")
    @Operation(summary = "List immutable Approval asset packages")
    public ApiResponse<List<PackageRecord>> packages(
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(service.packages(authority.readScope(), limit));
    }

    @GetMapping("/packages/{packageId}")
    @Operation(summary = "Read an immutable Approval asset package manifest")
    public ApiResponse<PackageRecord> deploymentPackage(@PathVariable UUID packageId) {
        return ApiResponse.success(service.packageById(authority.readScope(), packageId));
    }

    @GetMapping("/package-diff")
    @Operation(summary = "Diff two immutable Approval asset packages")
    public ApiResponse<PackageDiff> diff(
            @RequestParam UUID fromPackageId,
            @RequestParam UUID toPackageId) {
        return ApiResponse.success(service.diff(
                authority.readScope(), fromPackageId, toPackageId));
    }

    @PostMapping("/packages")
    @Operation(summary = "Create an immutable Approval asset package")
    public ApiResponse<PackageRecord> createPackage(
            @RequestHeader(OBJECT_VERSION) long expectedVersion,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION_REVISION) String decisionRevision,
            @RequestHeader(STEP_UP) String stepUp,
            @Valid @RequestBody PackageCreate input) {
        return ApiResponse.success(commands.createPackage(
                input.command(), expectedVersion, decisionRevision,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @GetMapping("/promotions")
    @Operation(summary = "List scoped Approval package promotions")
    public ApiResponse<List<Promotion>> promotions(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(service.promotions(
                authority.readScope(), status, limit));
    }

    @GetMapping("/promotions/{promotionId}")
    @Operation(summary = "Read promotion state, package, heads, evidence, and rollback feasibility")
    public ApiResponse<PromotionDetail> promotion(@PathVariable UUID promotionId) {
        return ApiResponse.success(service.promotionDetail(
                authority.readScope(), promotionId));
    }

    @PostMapping("/promotions")
    @Operation(summary = "Request a Development-to-Test or Test-to-Production promotion")
    public ApiResponse<Promotion> requestPromotion(
            @RequestHeader(OBJECT_VERSION) long expectedVersion,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION_REVISION) String decisionRevision,
            @RequestHeader(STEP_UP) String stepUp,
            @Valid @RequestBody PromotionCreate input) {
        return ApiResponse.success(commands.requestPromotion(
                input.command(), expectedVersion, decisionRevision,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/promotions/{promotionId}/approval")
    @Operation(summary = "Maker-checker approve a promotion with command-bound step-up")
    public ApiResponse<Promotion> approve(
            @PathVariable UUID promotionId,
            @Valid @RequestBody Review input,
            @RequestHeader(OBJECT_VERSION) long expectedVersion,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION_REVISION) String decisionRevision,
            @RequestHeader(STEP_UP) String stepUp) {
        return ApiResponse.success(commands.approve(
                promotionId, expectedVersion, input.reviewComment(), decisionRevision,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/promotions/{promotionId}/schedule")
    @Operation(summary = "Schedule an approved promotion with command-bound step-up")
    public ApiResponse<Promotion> schedule(
            @PathVariable UUID promotionId,
            @Valid @RequestBody Schedule input,
            @RequestHeader(OBJECT_VERSION) long expectedVersion,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION_REVISION) String decisionRevision,
            @RequestHeader(STEP_UP) String stepUp) {
        return ApiResponse.success(commands.schedule(
                promotionId, expectedVersion, input.scheduledFor(), decisionRevision,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/promotions/{promotionId}/activation")
    @Operation(summary = "Begin a due promotion activation with command-bound step-up")
    public ApiResponse<Promotion> activate(
            @PathVariable UUID promotionId,
            @RequestHeader(OBJECT_VERSION) long expectedVersion,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION_REVISION) String decisionRevision,
            @RequestHeader(STEP_UP) String stepUp) {
        return ApiResponse.success(commands.beginActivation(
                promotionId, expectedVersion, decisionRevision,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/promotions/{promotionId}/activation-evidence")
    @Operation(summary = "Record externally verified activation or canary evidence")
    public ApiResponse<Promotion> activationEvidence(
            @PathVariable UUID promotionId,
            @Valid @RequestBody ExternalEvidence input,
            @RequestHeader(OBJECT_VERSION) long expectedVersion,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION_REVISION) String decisionRevision,
            @RequestHeader(STEP_UP) String stepUp) {
        return ApiResponse.success(commands.recordActivationEvidence(
                promotionId, expectedVersion, input.submission(), decisionRevision,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @GetMapping("/promotions/{promotionId}/rollback-feasibility")
    @Operation(summary = "Evaluate package-head rollback feasibility without external-effect claims")
    public ApiResponse<RollbackFeasibility> rollbackFeasibility(
            @PathVariable UUID promotionId) {
        return ApiResponse.success(service.rollbackFeasibility(
                authority.readScope(), promotionId));
    }

    @PostMapping("/promotions/{promotionId}/rollback")
    @Operation(summary = "Request a governed package-head rollback")
    public ApiResponse<Promotion> rollback(
            @PathVariable UUID promotionId,
            @Valid @RequestBody Rollback input,
            @RequestHeader(OBJECT_VERSION) long expectedVersion,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION_REVISION) String decisionRevision,
            @RequestHeader(STEP_UP) String stepUp) {
        return ApiResponse.success(commands.requestRollback(
                promotionId, expectedVersion, input.reason(), decisionRevision,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/promotions/{promotionId}/rollback-evidence")
    @Operation(summary = "Record externally verified package-head rollback evidence")
    public ApiResponse<Promotion> rollbackEvidence(
            @PathVariable UUID promotionId,
            @Valid @RequestBody ExternalEvidence input,
            @RequestHeader(OBJECT_VERSION) long expectedVersion,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION_REVISION) String decisionRevision,
            @RequestHeader(STEP_UP) String stepUp) {
        return ApiResponse.success(commands.recordRollbackEvidence(
                promotionId, expectedVersion, input.submission(), decisionRevision,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    private ApprovalStepUpHeaders headers(
            String stepUp,
            String idempotencyKey,
            String decisionRevision,
            long expectedVersion) {
        return ApprovalStepUpHeaders.of(
                stepUp, idempotencyKey, decisionRevision, expectedVersion);
    }
}
