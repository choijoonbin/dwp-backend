package com.dwp.services.meeting.videomeeting.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class VideoMeetingScheduleDtos {
    private VideoMeetingScheduleDtos() { }

    public record RecurrenceRequest(
            @NotBlank @Pattern(regexp = "WEEKLY|MONTHLY") String frequency,
            @Min(1) @Max(12) int interval,
            @Min(2) @Max(52) int occurrenceCount) { }

    public record CreateSeriesRequest(
            @NotNull @Valid VideoMeetingDtos.ScheduleMeetingRequest meeting,
            @NotNull @Valid RecurrenceRequest recurrence,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String previewFingerprint) { }

    public record SeriesPreviewRequest(
            @NotNull @Valid VideoMeetingDtos.ScheduleMeetingRequest meeting,
            @NotNull @Valid RecurrenceRequest recurrence) { }

    public record OccurrencePreview(
            int occurrenceIndex,
            OffsetDateTime startsAt,
            String localStart,
            String utcOffset,
            String adjustment) { }

    public record SeriesPreviewResponse(
            String previewFingerprint,
            boolean hasCalendarAdjustments,
            java.util.List<OccurrencePreview> occurrences) { }

    public record RescheduleRequest(
            @NotNull @Future
            @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
            OffsetDateTime startsAt,
            @Min(5) @Max(1440) int durationMinutes,
            @NotBlank @Size(max = 80) String timeZone,
            @NotBlank @Pattern(regexp = "THIS_ONLY|THIS_AND_FUTURE") String scope,
            @PositiveOrZero Long expectedSeriesVersion,
            @NotNull @PositiveOrZero Long expectedVersion,
            @Pattern(regexp = "[0-9a-f]{64}") String calendarFingerprint) { }

    public record CancelRequest(
            @NotBlank @Pattern(regexp = "THIS_ONLY|THIS_AND_FUTURE") String scope,
            @PositiveOrZero Long expectedSeriesVersion,
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String impactFingerprint) { }

    public record CancelPreviewRequest(
            @NotBlank @Pattern(regexp = "THIS_ONLY|THIS_AND_FUTURE") String scope,
            @PositiveOrZero Long expectedSeriesVersion,
            @NotNull @PositiveOrZero Long expectedVersion) { }

    public record CancellationPreviewResponse(
            String impactFingerprint,
            String scope,
            int affectedOccurrenceCount,
            int skippedImmutableOccurrenceCount,
            long invitationRevision,
            Long seriesVersion) { }

    public record ScheduleStateResponse(
            UUID meetingId,
            String lifecycleState,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String timeZone,
            long meetingVersion,
            UUID seriesId,
            Integer occurrenceIndex,
            Integer occurrenceCount,
            String frequency,
            Integer recurrenceInterval,
            Long seriesVersion,
            String exceptionState,
            long invitationRevision,
            String deliveryState) { }
}
