package com.dwp.services.approval.policyautomation;

import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalRequestContext;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class PolicyAutomationModels {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private PolicyAutomationModels() {
    }

    public enum Lifecycle {
        DRAFT, ACTIVE, RETIRED
    }

    public enum ChannelType {
        IN_APP, EMAIL, PUSH, TEAMS, SLACK, WEBHOOK
    }

    public enum Readiness {
        NOT_CONFIGURED, READY, DEGRADED, UNAVAILABLE, STALE, UNKNOWN
    }

    public enum DelegationEffectiveState {
        SCHEDULED, IN_EFFECT, EXPIRED, REVOKED
    }

    public enum DelegationTruth {
        VERIFIED, VIOLATED, NOT_VERIFIED
    }

    public enum DelegationReviewDisposition {
        ACKNOWLEDGED_FINDINGS, REMEDIATION_REQUESTED
    }

    public enum DelegationComplianceState {
        BLOCKED, EVIDENCE_REQUIRED
    }

    public record Context(
            long tenantId,
            String resourceSetKey,
            long actorUserId,
            UUID actorPersonPublicId,
            String idempotencyKey) {
        public Context {
            if (tenantId < 1 || actorUserId < 1 || actorPersonPublicId == null
                    || resourceSetKey == null
                    || !resourceSetKey.matches("RS_[A-Z0-9_]{1,76}")
                    || idempotencyKey == null
                    || !idempotencyKey.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")) {
                throw PolicyAutomationRejected.invalid(
                        "Current policy-automation command context is invalid.");
            }
        }

        public static Context current(String idempotencyKey) {
            ApprovalRequestContext.Actor actor = ApprovalRequestContext.require();
            if (actor.tenantId() == null || actor.userId() == null) {
                throw PolicyAutomationRejected.forbidden(
                        "Current policy-automation authority is incomplete.");
            }
            return new Context(actor.tenantId(),
                    ApprovalManagementScopeContext.requireResourceSetKey(),
                    actor.userId(), actor.personPublicId(), idempotencyKey);
        }
    }

    public record WorkHours(LocalTime opensAt, LocalTime closesAt) {
    }

    public record Holiday(LocalDate date, String label) {
    }

    public record CalendarException(
            LocalDate date,
            boolean closed,
            LocalTime opensAt,
            LocalTime closesAt,
            String reason) {
    }

    public record CalendarDraft(
            UUID calendarId,
            String calendarKey,
            String displayName,
            String timeZone,
            Map<String, WorkHours> workWeek,
            List<Holiday> holidays,
            List<CalendarException> exceptions,
            Lifecycle lifecycle,
            long expectedVersion) {
    }

    public record CalendarView(
            UUID calendarId,
            String calendarKey,
            String displayName,
            String timeZone,
            Map<String, WorkHours> workWeek,
            List<Holiday> holidays,
            List<CalendarException> exceptions,
            Lifecycle lifecycle,
            long version) {
    }

    public record ChannelDraft(
            UUID channelId,
            String channelKey,
            ChannelType channelType,
            Lifecycle lifecycle,
            long expectedVersion) {
    }

    public record ChannelObservation(
            UUID observationId,
            Readiness readiness,
            String sourceRevision,
            String evidenceSha256,
            Instant observedAt,
            Instant validUntil,
            long expectedVersion,
            String evidencePayloadBase64Url,
            String evidenceSignatureBase64Url) {
    }

    public record ChannelView(
            UUID channelId,
            String channelKey,
            ChannelType channelType,
            Lifecycle lifecycle,
            Readiness readiness,
            String sourceRevision,
            String evidenceSha256,
            Instant observedAt,
            Instant validUntil,
            long version) {
    }

    public record Reminder(
            String reminderKey,
            int businessMinutesBefore,
            UUID channelId,
            String templateKey,
            int maximumDeliveries) {
    }

    public record Escalation(
            String escalationKey,
            int businessMinutesAfter,
            UUID resolverId,
            UUID channelId,
            String action,
            int maximumExecutions) {
    }

    public record PolicyDraft(
            UUID policyId,
            String policyKey,
            String displayName,
            UUID calendarId,
            List<Reminder> reminders,
            List<Escalation> escalations,
            Instant effectiveFrom,
            Instant effectiveTo,
            long expectedVersion) {
    }

    public record PolicyView(
            UUID policyId,
            String policyKey,
            String displayName,
            Lifecycle lifecycle,
            long version,
            UUID draftRevisionId,
            UUID publishedRevisionId,
            UUID calendarId,
            List<Reminder> reminders,
            List<Escalation> escalations,
            Instant effectiveFrom,
            Instant effectiveTo,
            String definitionSha256,
            long makerUserId,
            UUID makerPersonPublicId,
            long editorUserId,
            UUID editorPersonPublicId) {
    }

    public record PublishCommand(
            UUID revisionId,
            long expectedVersion,
            String reviewEvidenceSha256) {
    }

    public record BusinessDeadline(
            Instant start,
            long businessMinutes,
            Instant dueAt) {
    }

    public record DelegationReviewSummary(
            UUID reviewId,
            long delegationVersion,
            DelegationReviewDisposition disposition,
            DelegationComplianceState complianceState,
            Instant reviewedAt) {
    }

    public record DelegationView(
            UUID delegationId,
            long delegatorUserId,
            long delegateUserId,
            UUID delegatePersonPublicId,
            String delegateDisplayName,
            String delegateEmail,
            List<String> delegatedRoleCodes,
            String scopeType,
            UUID workflowId,
            String workflowKey,
            Instant startsAt,
            Instant endsAt,
            String lifecycleState,
            String reason,
            long version,
            DelegationEffectiveState effectiveState,
            DelegationTruth scopeBindingTruth,
            DelegationTruth timeWindowTruth,
            DelegationTruth noSubDelegationTruth,
            DelegationTruth identitySeparationTruth,
            DelegationTruth roleSnapshotTruth,
            DelegationTruth roleSeparationOfDutiesTruth,
            List<String> findings,
            DelegationReviewSummary latestReview) {
    }

    public record DelegationReviewCommand(
            UUID reviewId,
            DelegationReviewDisposition disposition,
            String reviewEvidenceSha256,
            long expectedDelegationVersion) {
    }

    public record DelegationReviewView(
            UUID reviewId,
            UUID delegationId,
            long delegationVersion,
            DelegationReviewDisposition disposition,
            DelegationComplianceState complianceState,
            DelegationTruth scopeBindingTruth,
            DelegationTruth timeWindowTruth,
            DelegationTruth noSubDelegationTruth,
            DelegationTruth identitySeparationTruth,
            DelegationTruth roleSnapshotTruth,
            DelegationTruth roleSeparationOfDutiesTruth,
            List<String> findings,
            String reviewEvidenceSha256,
            long reviewedBy,
            Instant reviewedAt) {
    }

    public record DelegationDraft(
            @NotNull UUID delegationId,
            @NotNull @Min(1) Long delegatorUserId,
            @NotNull @Min(1) Long delegateUserId,
            @NotNull @Size(min = 1, max = 50)
            List<@Pattern(regexp = "[A-Z][A-Z0-9_]{1,79}") String> delegatedRoleCodes,
            @NotBlank @Pattern(regexp = "ALL|WORKFLOW") String scopeType,
            UUID workflowId,
            @Size(max = 100) String workflowKey,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @NotBlank @Size(min = 10, max = 1000) String reason,
            @NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long expectedVersion) {
        public DelegationDraft {
            delegatedRoleCodes = delegatedRoleCodes == null ? List.of() : List.copyOf(delegatedRoleCodes);
        }
    }

    public record DelegationStateCommand(
            @NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long expectedVersion,
            @NotBlank @Size(min = 10, max = 1000) String reason) { }

    public record DelegationKillSwitchCommand(
            @NotNull UUID killSwitchId,
            @NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long expectedControlVersion,
            @NotBlank @Size(min = 10, max = 1000) String reason) { }

    public record DelegationKillSwitchView(UUID killSwitchId, String resourceSetKey,
            long controlVersion, long revokedDelegations, String reason,
            long invokedBy, Instant invokedAt) { }

    public record DelegationIdentity(long userId, UUID personPublicId, String displayName,
            String email, List<String> roleCodes) {
        public DelegationIdentity { roleCodes = List.copyOf(roleCodes); }
    }

    public record DelegationAuditEvent(UUID eventId, UUID delegationId, String action,
            long expectedVersion, long resultingVersion, String idempotencyKey,
            String commandSha256, Map<String, Object> beforeState,
            Map<String, Object> afterState, String reason, long actorUserId,
            Instant occurredAt) {
        public DelegationAuditEvent {
            beforeState = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(beforeState));
            afterState = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(afterState));
        }
    }
}
