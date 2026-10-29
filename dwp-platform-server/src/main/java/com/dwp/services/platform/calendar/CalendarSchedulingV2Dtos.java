package com.dwp.services.platform.calendar;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** V2 scheduling-assistant contract. V1 DTOs intentionally remain unchanged. */
public final class CalendarSchedulingV2Dtos {

    private CalendarSchedulingV2Dtos() {
    }

    public enum ParticipantRole { ORGANIZER, REQUIRED, OPTIONAL }

    public enum Coverage { COMPLETE, PARTIAL, UNKNOWN }

    public enum IntervalState { BUSY, TENTATIVE, OUT_OF_OFFICE, UNKNOWN }

    public enum SourceStatus { HEALTHY, PARTIAL, STALE, UNKNOWN, UNAVAILABLE }

    public enum ApprovalPreference {
        ANY, PREFER_NO_APPROVAL, REQUIRE_NO_APPROVAL, REQUIRE_APPROVAL
    }

    public enum HardRecheckStatus { PASS, BLOCKED, INCOMPLETE }

    public enum RoomEligibilityReason {
        ELIGIBLE,
        NO_CLEAR_CANDIDATE,
        RESOURCE_UNAVAILABLE,
        RESOURCE_CONFLICT,
        SITE_MISMATCH,
        FLOOR_MISMATCH,
        CAPACITY_TOO_SMALL,
        REQUIRED_FEATURE_MISSING,
        APPROVAL_PREFERENCE_MISMATCH,
        RESOURCE_VERSION_CHANGED,
        POLICY_VERSION_CHANGED,
        ROOM_NOT_VISIBLE
    }

    public record ParticipantRequest(
            @NotBlank @Size(max = 80)
            @Pattern(regexp = "[A-Za-z0-9_-]{1,80}") String clientRef,
            UUID personPublicId,
            @NotNull ParticipantRole role) {
    }

    public record RoomCriteria(
            @Size(max = 160) String site,
            @Size(max = 80) String floor,
            @Min(1) @Max(10000) Integer minimumCapacity,
            @Size(max = 20) List<@NotBlank @Size(max = 80) String> requiredFeatures,
            @Size(max = 20) List<@NotBlank @Size(max = 80) String> preferredFeatures,
            ApprovalPreference approvalPreference) {
    }

    public record EvaluationRequest(
            @NotNull @Size(max = 19) List<@Valid @NotNull ParticipantRequest> participants,
            @Valid @NotNull SearchWindow searchWindow,
            @Valid @NotNull CandidateSlot candidateSlot,
            @NotNull @Min(1) @Max(1440) Integer durationMinutes,
            @NotBlank @Size(max = 80) String searchTimeZone,
            @Valid RoomCriteria roomCriteria) {
    }

    public record SearchWindow(
            @NotNull OffsetDateTime from,
            @NotNull OffsetDateTime to) {
    }

    public record CandidateSlot(
            @NotNull OffsetDateTime startsAt,
            @NotNull OffsetDateTime endsAt) {
    }

    public record WorkingWindow(
            LocalTime startsAt,
            LocalTime endsAt) {
    }

    public record WorkingDay(
            DayOfWeek dayOfWeek,
            List<WorkingWindow> windows) {
    }

    public record WorkingSchedule(
            String timeZone,
            List<WorkingDay> days,
            String source,
            long version,
            OffsetDateTime observedAt) {
    }

    public record AvailabilityInterval(
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            IntervalState state,
            String sourceType) {
    }

    public record ParticipantAvailability(
            String clientRef,
            UUID personPublicId,
            ParticipantRole role,
            Coverage coverage,
            WorkingSchedule workingSchedule,
            List<AvailabilityInterval> intervals) {
    }

    public record SchedulingCandidate(
            UUID candidateId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            Coverage completeness,
            int score,
            List<String> reasonCodes,
            List<String> requiredConflictRefs,
            List<String> optionalConflictRefs,
            boolean hardRecheckRequired) {
    }

    public record SourceEvidence(
            String sourceType,
            SourceStatus status,
            OffsetDateTime coverageFrom,
            OffsetDateTime coverageTo,
            OffsetDateTime lastSuccessfulSyncAt,
            OffsetDateTime observedAt,
            String reasonCode,
            List<String> affectedParticipantRefs) {
    }

    public record RoomRecommendation(
            CalendarDtos.ResourceSummary room,
            int rank,
            int score,
            boolean eligible,
            RoomEligibilityReason eligibilityReason,
            List<String> reasonCodes,
            long resourceVersion,
            long policyVersion,
            boolean hardRecheckRequired) {
    }

    public record EvaluationResponse(
            UUID evaluationId,
            String criteriaHash,
            String challengeToken,
            Coverage completeness,
            List<ParticipantAvailability> participants,
            List<SchedulingCandidate> suggestions,
            List<SourceEvidence> sources,
            List<RoomRecommendation> rooms,
            OffsetDateTime generatedAt,
            OffsetDateTime validUntil) {
    }

    public record HardRecheckRequest(
            @NotBlank @Size(max = 256) String challengeToken,
            @NotBlank @Size(min = 64, max = 64) String criteriaHash,
            @Valid @NotNull CandidateSlot selection,
            UUID roomId) {
    }

    public record HardConflict(
            String clientRef,
            IntervalState state,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            boolean blocking) {
    }

    public record HardRecheckResponse(
            UUID evaluationId,
            String criteriaHash,
            HardRecheckStatus status,
            String hardRecheckToken,
            String selectionHash,
            List<HardConflict> conflicts,
            RoomRecommendation room,
            OffsetDateTime checkedAt,
            OffsetDateTime validUntil) {
    }
}
