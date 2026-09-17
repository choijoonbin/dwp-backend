package com.dwp.services.approval.routingdirectory;

import static com.dwp.services.approval.routingdirectory.WorkflowStudioModels.*;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/workflows/{workflowId}/studio-v3")
@Tag(name = "Approval Workflow Studio", description = "Append-only typed stage-canvas governance")
final class WorkflowStudioController {
    private static final String STEP_UP = "X-DWP-Step-Up-Challenge";
    private static final String IDEMPOTENCY = "Idempotency-Key";
    private static final String DECISION = "X-DWP-Expected-Decision-Revision";
    private static final String VERSION = "X-DWP-Expected-Object-Version";
    private final WorkflowStudioEndpointService endpoints;

    WorkflowStudioController(WorkflowStudioEndpointService endpoints) { this.endpoints = endpoints; }

    @GetMapping
    @Operation(summary = "Read the current typed workflow canvas and canonical command links")
    ApiResponse<WorkflowStudio> studio(@PathVariable UUID workflowId) {
        return ApiResponse.success(endpoints.studio(workflowId));
    }

    @GetMapping("/versions/diff")
    @Operation(summary = "Diff two immutable workflow canvas versions")
    ApiResponse<WorkflowDiff> diff(@PathVariable UUID workflowId,
            @RequestParam int fromVersion, @RequestParam int toVersion) {
        return ApiResponse.success(endpoints.diff(workflowId, fromVersion, toVersion));
    }

    @PutMapping("/canvas")
    @Operation(summary = "Append a version-fenced typed workflow canvas version")
    ApiResponse<WorkflowStudio> save(@PathVariable UUID workflowId,
            @Valid @RequestBody CanvasSave input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null ? -1 : input.expectedVersion(), expectedVersion);
        return ApiResponse.success(endpoints.save(workflowId, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/dry-run")
    @Operation(summary = "Persist a structural dry-run over the current canonical stage graph",
            description = "Dynamic form and policy evaluation remains on the canonical version simulation endpoint.")
    ApiResponse<DryRunResult> dryRun(@PathVariable UUID workflowId,
            @Valid @RequestBody CanvasDryRun input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null ? -1 : input.expectedVersion(), expectedVersion);
        return ApiResponse.success(endpoints.dryRun(workflowId, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/retire")
    @Operation(summary = "Retire a workflow after exact zero-reference acknowledgement")
    ApiResponse<Retirement> retire(@PathVariable UUID workflowId,
            @Valid @RequestBody RetireWorkflow input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(STEP_UP) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null ? -1 : input.expectedVersion(), expectedVersion);
        return ApiResponse.success(endpoints.retire(workflowId, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    private ApprovalStepUpHeaders headers(String stepUp, String key, String revision, long version) {
        return ApprovalStepUpHeaders.of(stepUp, key, revision, version);
    }

    private void requireVersion(long body, long header) {
        if (body < 0 || body != header) {
            throw RoutingDirectoryRejected.conflict("Payload and expected-object versions must match.");
        }
    }
}
