package com.dwp.services.meeting.videomeeting.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class MeetingScheduleDraftDtos {

    private MeetingScheduleDraftDtos() {
    }

    public record DraftRecurrence(
            @NotBlank @Pattern(regexp = "NONE|WEEKLY|MONTHLY") String frequency,
            @Min(1) @Max(12) int interval,
            @Min(2) @Max(52) int occurrenceCount) {
    }

    public record DraftAgendaItem(
            UUID itemId,
            @Size(max = 240) String title,
            @Size(max = 2000) String objective,
            @Positive Long ownerUserId,
            @Min(1) @Max(1440) Integer plannedMinutes) {
    }

    /** Partial input is intentional: only commit applies the strict meeting contract. */
    public record SaveScheduleDraftRequest(
            @PositiveOrZero Long expectedVersion,
            @Size(max = 240) String title,
            @Size(max = 8000) String agenda,
            OffsetDateTime startsAt,
            @Min(5) @Max(1440) Integer durationMinutes,
            @Size(max = 80) String timeZone,
            @Pattern(regexp = "INTERNAL|INVITED") String accessScope,
            Boolean waitingRoomEnabled,
            Boolean allowJoinBeforeHost,
            @Size(max = 200) List<@Positive Long> participantUserIds,
            @Size(max = 50) List<@NotNull @Valid DraftAgendaItem> agendaItems,
            @Valid DraftRecurrence recurrence,
            UUID sourceTemplateId,
            @PositiveOrZero Long sourceTemplateVersion,
            @Pattern(regexp = "DETAILS|SCHEDULE|RECURRENCE|REVIEW") String lastStep) {
    }

    public record DraftAgendaItemResponse(
            UUID itemId,
            int position,
            String title,
            String objective,
            Long ownerUserId,
            Integer plannedMinutes) {
    }

    public record ScheduleDraftResponse(
            UUID draftId,
            String title,
            String agenda,
            OffsetDateTime startsAt,
            Integer durationMinutes,
            String timeZone,
            String accessScope,
            Boolean waitingRoomEnabled,
            Boolean allowJoinBeforeHost,
            List<VideoMeetingDtos.MeetingPersonResponse> participants,
            List<DraftAgendaItemResponse> agendaItems,
            DraftRecurrence recurrence,
            UUID sourceTemplateId,
            Long sourceTemplateVersion,
            String lastStep,
            long version,
            OffsetDateTime retentionUntil,
            OffsetDateTime updatedAt) {
    }

    /** Revoked template sources expose only the opaque slot needed for blind discard. */
    public record ScheduleDraftSlotResponse(
            ScheduleDraftResponse draft,
            boolean discardOnly,
            UUID draftId,
            Long version,
            OffsetDateTime retentionUntil,
            OffsetDateTime observedAt) {
    }

    public record DraftVersionRequest(
            @NotNull @PositiveOrZero Long expectedVersion) {
    }

    public record CommitScheduleDraftRequest(
            @NotNull @PositiveOrZero Long expectedVersion,
            @Pattern(regexp = "[0-9a-f]{64}") String previewFingerprint) {
    }

    public record DiscardScheduleDraftResponse(
            UUID draftId,
            long version,
            boolean discarded) {
    }
}
