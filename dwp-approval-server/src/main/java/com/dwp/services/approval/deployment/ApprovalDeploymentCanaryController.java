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
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.dwp.services.approval.deployment.ApprovalDeploymentCanaryApiDtos.*;
import static com.dwp.services.approval.deployment.ApprovalDeploymentCanaryModels.*;

@RestController
@RequestMapping("/v1/admin/operations/deployments/promotions/{promotionId}")
@Tag(name = "Approval deployment canary governance")
public class ApprovalDeploymentCanaryController {
    private static final String IDEMPOTENCY = "Idempotency-Key";
    private static final String STEP_UP = "X-DWP-Step-Up-Challenge";
    private static final String DECISION_REVISION = "X-DWP-Expected-Decision-Revision";
    private static final String OBJECT_VERSION = "X-DWP-Expected-Object-Version";

    private final ApprovalDeploymentCanaryService service;
    private final ApprovalDeploymentCanaryCommandFacade commands;
    private final ApprovalDeploymentHttpAuthority authority;

    ApprovalDeploymentCanaryController(
            ApprovalDeploymentCanaryService service,
            ApprovalDeploymentCanaryCommandFacade commands,
            ApprovalDeploymentHttpAuthority authority) {
        this.service = service;
        this.commands = commands;
        this.authority = authority;
    }

    @GetMapping("/canary")
    @Operation(summary = "Read canary control and externally verified telemetry")
    public ApiResponse<CanaryView> canary(@PathVariable UUID promotionId) {
        return ApiResponse.success(service.canary(authority.readScope(), promotionId));
    }

    @GetMapping("/ledger")
    @Operation(summary = "Verify the append-only deployment journal as a SHA-256 hash chain")
    public ApiResponse<LedgerView> ledger(@PathVariable UUID promotionId) {
        return ApiResponse.success(service.ledger(authority.readScope(), promotionId));
    }

    @PostMapping("/canary/control")
    @Operation(summary = "Pause or resume a version-fenced canary")
    public ApiResponse<CanaryView> control(
            @PathVariable UUID promotionId,
            @RequestHeader(OBJECT_VERSION) long expectedVersion,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION_REVISION) String decisionRevision,
            @RequestHeader(STEP_UP) String stepUp,
            @Valid @RequestBody ControlInput input) {
        return ApiResponse.success(commands.control(
                promotionId, input.command(), expectedVersion, decisionRevision,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/canary/telemetry")
    @Operation(summary = "Record signed canary telemetry and auto-pause non-healthy observations")
    public ApiResponse<CanaryView> telemetry(
            @PathVariable UUID promotionId,
            @RequestHeader(OBJECT_VERSION) long expectedVersion,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION_REVISION) String decisionRevision,
            @RequestHeader(STEP_UP) String stepUp,
            @Valid @RequestBody TelemetryInput input) {
        return ApiResponse.success(commands.telemetry(
                promotionId, input.command(), expectedVersion, decisionRevision,
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
