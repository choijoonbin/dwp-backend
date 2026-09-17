package com.dwp.services.approval.policyautomation;

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

import static com.dwp.services.approval.policyautomation.PolicyGovernanceModels.*;

@RestController
@RequestMapping("/v1/admin/policies/automation/rules/{policyId}")
@Tag(name = "Approval policy governance")
public class PolicyGovernanceController {
    private static final String STEP_UP = "X-DWP-Step-Up-Challenge";
    private static final String IDEMPOTENCY = "Idempotency-Key";
    private static final String DECISION = "X-DWP-Expected-Decision-Revision";
    private static final String VERSION = "X-DWP-Expected-Object-Version";

    private final PolicyGovernanceEndpointService endpoints;

    public PolicyGovernanceController(PolicyGovernanceEndpointService endpoints) {
        this.endpoints = endpoints;
    }

    @GetMapping("/diff")
    @Operation(summary = "Diff two immutable policy revisions")
    public ApiResponse<RevisionDiff> diff(
            @PathVariable UUID policyId,
            @RequestParam UUID fromRevisionId,
            @RequestParam UUID toRevisionId) {
        return ApiResponse.success(endpoints.diff(policyId, fromRevisionId, toRevisionId));
    }

    @GetMapping("/reviews")
    @Operation(summary = "List independent policy reviews")
    public ApiResponse<List<ReviewReceipt>> reviews(@PathVariable UUID policyId) {
        return ApiResponse.success(endpoints.reviews(policyId));
    }

    @GetMapping("/freeze")
    @Operation(summary = "Read the policy freeze state")
    public ApiResponse<FreezeState> freeze(@PathVariable UUID policyId) {
        return ApiResponse.success(endpoints.freeze(policyId));
    }

    @PostMapping("/simulations")
    @Operation(summary = "Run and persist an exact policy simulation")
    public ApiResponse<SimulationReceipt> simulate(
            @PathVariable UUID policyId,
            @RequestBody @Valid SimulationCommand command,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(command.expectedVersion(), expectedVersion);
        return ApiResponse.success(endpoints.simulate(policyId, command,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/reviews")
    @Operation(summary = "Record an independent policy review")
    public ApiResponse<ReviewReceipt> review(
            @PathVariable UUID policyId,
            @RequestBody @Valid ReviewCommand command,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(STEP_UP) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(command.expectedVersion(), expectedVersion);
        return ApiResponse.success(endpoints.review(policyId, command,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/freeze")
    @Operation(summary = "Freeze or unfreeze a policy with exact command evidence")
    public ApiResponse<FreezeState> setFreeze(
            @PathVariable UUID policyId,
            @RequestBody @Valid FreezeCommand command,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(STEP_UP) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(command.expectedVersion(), expectedVersion);
        return ApiResponse.success(endpoints.setFreeze(policyId, command,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/exports")
    @Operation(summary = "Seal an immutable policy evidence export")
    public ApiResponse<PolicyExport> export(
            @PathVariable UUID policyId,
            @RequestBody @Valid ExportCommand command,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(STEP_UP) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(command.expectedVersion(), expectedVersion);
        return ApiResponse.success(endpoints.export(policyId, command,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    private ApprovalStepUpHeaders headers(
            String stepUp, String idempotencyKey, String revision, long version) {
        return ApprovalStepUpHeaders.of(stepUp, idempotencyKey, revision, version);
    }

    private void requireVersion(long body, long header) {
        if (body != header) {
            throw PolicyAutomationRejected.conflict(
                    "Policy command body and version header do not match.");
        }
    }
}
