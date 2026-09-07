package com.dwp.services.meeting.videomeeting.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class MeetingWorkspaceDtos {
    private MeetingWorkspaceDtos() { }

    public enum TemplateScope { PERSONAL, ORGANIZATION }
    public enum TemplateFilter { ALL, PERSONAL, ORGANIZATION }

    public record AgendaItem(
            @NotBlank @Size(max = 240) String title,
            @NotNull @Size(max = 2000) String description,
            @NotNull @Size(max = 80) String role,
            @Min(1) @Max(1440) int durationMinutes) { }

    public record TemplateInput(
            @NotBlank @Size(max = 160) String name,
            @NotNull @Size(max = 2000) String purpose,
            @NotBlank @Size(max = 40) String category,
            @Min(5) @Max(1440) int durationMinutes,
            @NotNull @Size(max = 50) List<@Valid AgendaItem> agendaItems) { }

    public record TemplateUpdate(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull @Valid TemplateInput template) { }

    public record TemplateResponse(
            UUID templateId, TemplateScope scope, String name, String purpose,
            String category, int durationMinutes, List<AgendaItem> agendaItems,
            boolean favorite, boolean canEdit, long version, OffsetDateTime updatedAt) { }

    public record TemplatePage(List<TemplateResponse> items, long total, int page, int pageSize) { }
    public record VersionCommand(@NotNull @PositiveOrZero Long expectedVersion) { }
    public record FavoriteCommand(boolean favorite) { }
    public record CloneCommand(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank @Size(max = 160) String name) { }
    public record DeleteResponse(UUID resourceId, long version, boolean deleted) { }

    /** This is an editable draft, never a persisted meeting or a consent grant. */
    public record ScheduleDraft(
            UUID sourceTemplateId, long sourceTemplateVersion, String title,
            String purpose, int durationMinutes, List<AgendaItem> agendaItems,
            String accessScope, boolean waitingRoomEnabled,
            boolean defaultMicrophoneEnabled, boolean defaultCameraEnabled,
            boolean requiresPolicyRevalidation) { }

    public record RoomCreate(@NotBlank @Size(max = 160) String name) { }
    public record RoomUpdate(
            @NotBlank @Size(max = 160) String name,
            @NotNull @PositiveOrZero Long expectedVersion) { }
    public record PersonalRoomResponse(
            UUID roomId, String name, String opaqueAlias, long invitationRevision,
            long version, OffsetDateTime updatedAt, UUID currentMeetingId) { }
    public record RoomSessionCommand(
            @NotNull @PositiveOrZero Long expectedVersion,
            @Min(1) long invitationRevision) { }
    public record RoomSessionResponse(
            UUID meetingId, String title, String lifecycleState,
            long invitationRevision, OffsetDateTime createdAt, OffsetDateTime endedAt) { }
    public record RoomSessionPage(List<RoomSessionResponse> items, long total, int page, int pageSize) { }
    public record InvitationResponse(String name, UUID meetingId, boolean sessionAvailable) { }

    public record PreferencesInput(
            @NotNull @Size(max = 100) String displayName,
            boolean microphoneOff, boolean cameraOff, boolean prejoinEnabled,
            boolean reminderEnabled, @Min(0) @Max(60) int reminderMinutes,
            boolean recapNotifications, @NotNull @PositiveOrZero Long expectedVersion) { }
    public record PreferencesResponse(
            String displayName, boolean microphoneOff, boolean cameraOff,
            boolean prejoinEnabled, boolean reminderEnabled, int reminderMinutes,
            boolean recapNotifications, long version, OffsetDateTime updatedAt) { }
}
