package com.dwp.services.approval.routingdirectory;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.approval.routingdirectory.RoutingDirectoryModels.*;

@RestController
@RequestMapping("/v1/admin/workflows/routing-directory")
@Tag(name = "Approval routing directory")
public class RoutingDirectoryController {
    private static final String STEP_UP = "X-DWP-Step-Up-Challenge";
    private static final String IDEMPOTENCY = "Idempotency-Key";
    private static final String DECISION = "X-DWP-Expected-Decision-Revision";
    private static final String VERSION = "X-DWP-Expected-Object-Version";

    private final RoutingDirectoryEndpointService endpoints;

    public RoutingDirectoryController(RoutingDirectoryEndpointService endpoints) {
        this.endpoints = endpoints;
    }

    @GetMapping("/groups")
    @Operation(summary = "List approver groups in the selected management scope")
    public ApiResponse<List<GroupView>> groups() {
        return ApiResponse.success(endpoints.groups());
    }

    @GetMapping("/groups/{groupId}")
    @Operation(summary = "Read an approver group")
    public ApiResponse<GroupView> group(@PathVariable UUID groupId) {
        return ApiResponse.success(endpoints.group(groupId));
    }

    @GetMapping("/groups/{groupId}/resolution")
    @Operation(summary = "Resolve current approver candidates without fabricating fallbacks")
    public ApiResponse<Resolution> resolution(
            @PathVariable UUID groupId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant effectiveAt) {
        return ApiResponse.success(endpoints.resolve(groupId, effectiveAt));
    }

    @GetMapping("/groups/{groupId}/retirement-impact")
    @Operation(summary = "Read exact active usage and parent-group retirement impact")
    public ApiResponse<RetireImpact> impact(@PathVariable UUID groupId) {
        return ApiResponse.success(endpoints.impact(groupId));
    }

    @GetMapping("/resolvers")
    @Operation(summary = "List approver resolver definitions and source health")
    public ApiResponse<List<ResolverView>> resolvers() {
        return ApiResponse.success(endpoints.resolvers());
    }

    @GetMapping("/resolvers/{resolverId}")
    @Operation(summary = "Read an approver resolver definition")
    public ApiResponse<ResolverView> resolver(@PathVariable UUID resolverId) {
        return ApiResponse.success(endpoints.resolver(resolverId));
    }

    @PutMapping("/resolvers/{resolverId}")
    @Operation(summary = "Create or update a version-fenced approver resolver")
    public ApiResponse<ResolverView> saveResolver(
            @PathVariable UUID resolverId,
            @RequestBody @Valid ResolverDraft input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireTarget(resolverId, input == null ? null : input.resolverId(),
                input == null ? -1 : input.expectedVersion(), expectedVersion);
        return ApiResponse.success(endpoints.saveResolver(input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/resolvers/{resolverId}/observations")
    @Operation(summary = "Record expiring authoritative resolver-source health")
    public ApiResponse<ResolverView> observeResolver(
            @PathVariable UUID resolverId,
            @RequestBody @Valid SourceObservation input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null ? -1 : input.expectedVersion(), expectedVersion);
        return ApiResponse.success(endpoints.observeResolver(resolverId, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PutMapping("/groups/{groupId}")
    @Operation(summary = "Create or update an acyclic nested approver group")
    public ApiResponse<GroupView> saveGroup(
            @PathVariable UUID groupId,
            @RequestBody @Valid GroupDraft input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireTarget(groupId, input == null ? null : input.groupId(),
                input == null ? -1 : input.expectedVersion(), expectedVersion);
        return ApiResponse.success(endpoints.saveGroup(input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/groups/{groupId}/publish")
    @Operation(summary = "Activate a reviewed approver group")
    public ApiResponse<GroupView> activate(
            @PathVariable UUID groupId,
            @RequestBody @Valid ActivationCommand input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(STEP_UP) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null ? -1 : input.expectedVersion(), expectedVersion);
        return ApiResponse.success(endpoints.activate(groupId, expectedVersion,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/groups/{groupId}/usages")
    @Operation(summary = "Record a version-fenced approver-group usage binding")
    public ApiResponse<Usage> recordUsage(
            @PathVariable UUID groupId,
            @RequestBody @Valid Usage input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireTarget(groupId, input == null ? null : input.groupId(),
                input == null ? -1 : input.expectedGroupVersion(), expectedVersion);
        return ApiResponse.success(endpoints.recordUsage(input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/groups/{groupId}/retire")
    @Operation(summary = "Retire an unused group after exact impact acknowledgement")
    public ApiResponse<GroupView> retire(
            @PathVariable UUID groupId,
            @RequestBody @Valid RetirementCommand input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(STEP_UP) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null ? -1 : input.expectedVersion(), expectedVersion);
        if (input.acknowledgedImpact() < 0) {
            throw RoutingDirectoryRejected.invalid("Acknowledged impact cannot be negative.");
        }
        return ApiResponse.success(endpoints.retire(groupId, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    private ApprovalStepUpHeaders headers(
            String stepUp, String idempotencyKey, String decisionRevision, long version) {
        return ApprovalStepUpHeaders.of(stepUp, idempotencyKey, decisionRevision, version);
    }

    private void requireTarget(UUID pathId, UUID bodyId, long bodyVersion, long headerVersion) {
        if (bodyId == null || !pathId.equals(bodyId)) {
            throw RoutingDirectoryRejected.invalid("Path and payload targets must match.");
        }
        requireVersion(bodyVersion, headerVersion);
    }

    private void requireVersion(long bodyVersion, long headerVersion) {
        if (bodyVersion < 0 || bodyVersion != headerVersion) {
            throw RoutingDirectoryRejected.conflict(
                    "Payload and expected-object versions must match.");
        }
    }
}
