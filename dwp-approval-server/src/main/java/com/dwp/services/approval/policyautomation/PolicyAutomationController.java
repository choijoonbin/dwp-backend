package com.dwp.services.approval.policyautomation;

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

import static com.dwp.services.approval.policyautomation.PolicyAutomationModels.*;

@RestController
@RequestMapping("/v1/admin/policies/automation")
@Tag(name = "Approval policy automation")
public class PolicyAutomationController {
    private static final String STEP_UP = "X-DWP-Step-Up-Challenge";
    private static final String IDEMPOTENCY = "Idempotency-Key";
    private static final String DECISION = "X-DWP-Expected-Decision-Revision";
    private static final String VERSION = "X-DWP-Expected-Object-Version";

    private final PolicyAutomationEndpointService endpoints;

    public PolicyAutomationController(PolicyAutomationEndpointService endpoints) {
        this.endpoints = endpoints;
    }

    @GetMapping("/calendars")
    @Operation(summary = "List business calendars in the selected management scope")
    public ApiResponse<List<CalendarView>> calendars() {
        return ApiResponse.success(endpoints.calendars());
    }

    @GetMapping("/calendars/{calendarId}")
    @Operation(summary = "Read a business calendar with holidays and exceptions")
    public ApiResponse<CalendarView> calendar(@PathVariable UUID calendarId) {
        return ApiResponse.success(endpoints.calendar(calendarId));
    }

    @GetMapping("/calendars/{calendarId}/deadline")
    @Operation(summary = "Calculate a deadline using the authoritative business calendar")
    public ApiResponse<BusinessDeadline> deadline(
            @PathVariable UUID calendarId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @RequestParam long businessMinutes) {
        if (businessMinutes < 0 || businessMinutes > 5_256_000) {
            throw PolicyAutomationRejected.invalid(
                    "Business minutes must be between 0 and 5256000.");
        }
        return ApiResponse.success(endpoints.deadline(calendarId, start, businessMinutes));
    }

    @GetMapping("/channels")
    @Operation(summary = "List notification channels and current readiness evidence")
    public ApiResponse<List<ChannelView>> channels() {
        return ApiResponse.success(endpoints.channels());
    }

    @GetMapping("/channels/{channelId}")
    @Operation(summary = "Read a notification channel")
    public ApiResponse<ChannelView> channel(@PathVariable UUID channelId) {
        return ApiResponse.success(endpoints.channel(channelId));
    }

    @GetMapping("/rules")
    @Operation(summary = "List reminder and escalation policies")
    public ApiResponse<List<PolicyView>> policies() {
        return ApiResponse.success(endpoints.policies());
    }

    @GetMapping("/rules/{policyId}")
    @Operation(summary = "Read a reminder and escalation policy")
    public ApiResponse<PolicyView> policy(@PathVariable UUID policyId) {
        return ApiResponse.success(endpoints.policy(policyId));
    }

    @GetMapping("/delegations")
    @Operation(summary = "List scoped delegations with current governance truth")
    public ApiResponse<List<DelegationView>> delegations() {
        return ApiResponse.success(endpoints.delegations());
    }

    @GetMapping("/delegations/{delegationId}")
    @Operation(summary = "Read one scoped delegation and its latest review")
    public ApiResponse<DelegationView> delegation(@PathVariable UUID delegationId) {
        return ApiResponse.success(endpoints.delegation(delegationId));
    }

    @GetMapping("/delegations/{delegationId}/reviews")
    @Operation(summary = "List persisted governance reviews for a delegation")
    public ApiResponse<List<DelegationReviewView>> delegationReviews(
            @PathVariable UUID delegationId) {
        return ApiResponse.success(endpoints.delegationReviews(delegationId));
    }

    @GetMapping("/delegations/{delegationId}/audit")
    @Operation(summary = "Read the append-only administrative command ledger for one delegation")
    public ApiResponse<List<DelegationAuditEvent>> delegationAudit(
            @PathVariable UUID delegationId, @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(endpoints.delegationAudit(delegationId, limit));
    }

    @GetMapping("/delegations/audit")
    @Operation(summary = "Read the append-only delegation command ledger in the selected scope")
    public ApiResponse<List<DelegationAuditEvent>> delegationAudit(
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(endpoints.delegationAudit(null, limit));
    }

    @PutMapping("/calendars/{calendarId}")
    @Operation(summary = "Create or update a version-fenced business calendar")
    public ApiResponse<CalendarView> saveCalendar(
            @PathVariable UUID calendarId,
            @RequestBody @Valid CalendarDraft input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireTarget(calendarId, input == null ? null : input.calendarId(),
                input == null ? -1 : input.expectedVersion(), expectedVersion);
        return ApiResponse.success(endpoints.saveCalendar(input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PutMapping("/channels/{channelId}")
    @Operation(summary = "Create or update a notification channel")
    public ApiResponse<ChannelView> saveChannel(
            @PathVariable UUID channelId,
            @RequestBody @Valid ChannelDraft input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireTarget(channelId, input == null ? null : input.channelId(),
                input == null ? -1 : input.expectedVersion(), expectedVersion);
        return ApiResponse.success(endpoints.saveChannel(input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/channels/{channelId}/observations")
    @Operation(summary = "Record expiring channel-readiness evidence")
    public ApiResponse<ChannelView> observeChannel(
            @PathVariable UUID channelId,
            @RequestBody @Valid ChannelObservation input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null ? -1 : input.expectedVersion(), expectedVersion);
        return ApiResponse.success(endpoints.observeChannel(channelId, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PutMapping("/rules/{policyId}/draft")
    @Operation(summary = "Save a maker-owned reminder and escalation policy draft")
    public ApiResponse<PolicyView> savePolicy(
            @PathVariable UUID policyId,
            @RequestBody @Valid PolicyDraft input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireTarget(policyId, input == null ? null : input.policyId(),
                input == null ? -1 : input.expectedVersion(), expectedVersion);
        return ApiResponse.success(endpoints.savePolicy(input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/rules/{policyId}/publish")
    @Operation(summary = "Publish a reviewed policy with maker-checker enforcement")
    public ApiResponse<PolicyView> publish(
            @PathVariable UUID policyId,
            @RequestBody @Valid PublishCommand input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(STEP_UP) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null ? -1 : input.expectedVersion(), expectedVersion);
        return ApiResponse.success(endpoints.publish(policyId, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/delegations/{delegationId}/reviews")
    @Operation(summary = "Record a version-fenced delegation governance disposition")
    public ApiResponse<DelegationReviewView> reviewDelegation(
            @PathVariable UUID delegationId,
            @RequestBody @Valid DelegationReviewCommand input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null ? -1 : input.expectedDelegationVersion(),
                expectedVersion);
        return ApiResponse.success(endpoints.reviewDelegation(delegationId, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/delegations")
    @Operation(summary = "Create a version-fenced governed delegation")
    public ApiResponse<DelegationView> createDelegation(
            @RequestBody @Valid DelegationDraft input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null || input.expectedVersion() == null ? -1 : input.expectedVersion(),
                expectedVersion);
        return ApiResponse.success(endpoints.createDelegation(input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PutMapping("/delegations/{delegationId}")
    @Operation(summary = "Edit a version-fenced governed delegation")
    public ApiResponse<DelegationView> updateDelegation(
            @PathVariable UUID delegationId, @RequestBody @Valid DelegationDraft input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireTarget(delegationId, input == null ? null : input.delegationId(),
                input == null || input.expectedVersion() == null ? -1 : input.expectedVersion(), expectedVersion);
        return ApiResponse.success(endpoints.updateDelegation(delegationId, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/delegations/{delegationId}/revoke")
    @Operation(summary = "Revoke a version-fenced delegation immediately")
    public ApiResponse<DelegationView> revokeDelegation(
            @PathVariable UUID delegationId, @RequestBody @Valid DelegationStateCommand input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null || input.expectedVersion() == null ? -1 : input.expectedVersion(),
                expectedVersion);
        return ApiResponse.success(endpoints.changeDelegationState(delegationId, input, false,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/delegations/{delegationId}/scheduled-cancel")
    @Operation(summary = "Cancel a future scheduled delegation")
    public ApiResponse<DelegationView> cancelScheduledDelegation(
            @PathVariable UUID delegationId, @RequestBody @Valid DelegationStateCommand input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null || input.expectedVersion() == null ? -1 : input.expectedVersion(),
                expectedVersion);
        return ApiResponse.success(endpoints.changeDelegationState(delegationId, input, true,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/delegations/kill-switch")
    @Operation(summary = "Revoke every active delegation in the selected scope with step-up")
    public ApiResponse<DelegationKillSwitchView> killSwitch(
            @RequestBody @Valid DelegationKillSwitchCommand input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(STEP_UP) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null || input.expectedControlVersion() == null
                ? -1 : input.expectedControlVersion(), expectedVersion);
        return ApiResponse.success(endpoints.killSwitch(input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    private ApprovalStepUpHeaders headers(
            String stepUp, String idempotencyKey, String decisionRevision, long version) {
        return ApprovalStepUpHeaders.of(stepUp, idempotencyKey, decisionRevision, version);
    }

    private void requireTarget(UUID pathId, UUID bodyId, long bodyVersion, long headerVersion) {
        if (bodyId == null || !pathId.equals(bodyId)) {
            throw PolicyAutomationRejected.invalid("Path and payload targets must match.");
        }
        requireVersion(bodyVersion, headerVersion);
    }

    private void requireVersion(long bodyVersion, long headerVersion) {
        if (bodyVersion < 0 || bodyVersion != headerVersion) {
            throw PolicyAutomationRejected.conflict(
                    "Payload and expected-object versions must match.");
        }
    }
}
