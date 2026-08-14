package com.dwp.services.platform.calendar;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.*;

public final class CalendarDtos {

    private CalendarDtos() {
    }

    public record CalendarSummary(
            UUID calendarId,
            String calendarKey,
            String name,
            String color,
            CalendarType type,
            String visibility,
            boolean selected) {
    }

    public record Attendee(
            Long userId,
            UUID personPublicId,
            String email,
            String name,
            AttendeeType type,
            ResponseStatus response) {
    }

    public record EventSummary(
            UUID eventId,
            UUID calendarId,
            String calendarName,
            String calendarColor,
            Long organizerUserId,
            UUID organizerPersonPublicId,
            String organizerName,
            String organizerEmail,
            String title,
            String description,
            EventType type,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String timeZone,
            boolean allDay,
            String location,
            String conferenceUrl,
            EventStatus status,
            EventVisibility visibility,
            RecurrencePattern recurrence,
            int recurrenceInterval,
            LocalDate recurrenceUntil,
            boolean responseRequired,
            ResponseStatus myResponse,
            List<Attendee> attendees,
            ResourceSummary resource,
            boolean conflict,
            long version) {
    }

    public record HomeMetrics(
            int eventCount,
            int meetingMinutes,
            int focusMinutes,
            int focusTargetMinutes,
            int conflictCount,
            int awaitingResponseCount,
            int availableRoomCount) {
    }

    public record DayLoad(
            LocalDate date,
            int meetingMinutes,
            int focusMinutes,
            int eventCount,
            int conflictCount,
            int loadPercent) {
    }

    public record AttentionItem(
            String key,
            String severity,
            String title,
            String description,
            UUID eventId,
            String actionPath) {
    }

    public record HomeResponse(
            LocalDate date,
            String timeZone,
            EventSummary nextEvent,
            List<EventSummary> today,
            HomeMetrics metrics,
            List<DayLoad> weekLoad,
            List<AttentionItem> attention,
            OffsetDateTime generatedAt) {
    }

    public record AvailabilityParticipant(
            UUID personPublicId,
            int busyMinutes,
            int availableSlotCount) {
    }

    public record AvailabilitySlot(
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            int score,
            String reason) {
    }

    public record AvailabilityResponse(
            List<AvailabilityParticipant> participants,
            List<AvailabilitySlot> suggestions,
            OffsetDateTime generatedAt) {
    }

    public record AttendeeInput(
            Long userId,
            UUID personPublicId,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 160) String name,
            @NotNull AttendeeType type) {
    }

    public record CreateEventRequest(
            @NotBlank @Size(max = 240) String title,
            @Size(max = 4000) String description,
            @NotNull EventType type,
            @NotNull OffsetDateTime startsAt,
            @NotNull OffsetDateTime endsAt,
            @NotBlank @Size(max = 80) String timeZone,
            boolean allDay,
            @Size(max = 240) String location,
            @Size(max = 1000) String conferenceUrl,
            @NotNull EventVisibility visibility,
            @NotNull RecurrencePattern recurrence,
            @Min(1) @Max(52) int recurrenceInterval,
            LocalDate recurrenceUntil,
            boolean responseRequired,
            @NotNull @Size(max = 100) List<@Valid AttendeeInput> attendees,
            UUID resourceId,
            @NotNull UUID idempotencyKey) {
    }

    public record UpdateEventRequest(
            @NotBlank @Size(max = 240) String title,
            @Size(max = 4000) String description,
            @NotNull EventType type,
            @NotNull OffsetDateTime startsAt,
            @NotNull OffsetDateTime endsAt,
            @NotBlank @Size(max = 80) String timeZone,
            boolean allDay,
            @Size(max = 240) String location,
            @Size(max = 1000) String conferenceUrl,
            @NotNull EventVisibility visibility,
            @NotNull RecurrencePattern recurrence,
            @Min(1) @Max(52) int recurrenceInterval,
            LocalDate recurrenceUntil,
            boolean responseRequired,
            @NotNull @Size(max = 100) List<@Valid AttendeeInput> attendees,
            UUID resourceId,
            @NotNull @Min(0) Long version) {
    }

    public record RespondRequest(@NotNull ResponseStatus response) {
    }

    public record VersionRequest(@NotNull @Min(0) Long version) {
    }

    public record ResourceSummary(
            UUID resourceId,
            String code,
            String name,
            String nameKo,
            String nameEn,
            ResourceType type,
            String site,
            String floor,
            int capacity,
            List<String> features,
            String timeZone,
            boolean approvalRequired,
            ResourceState state,
            boolean available,
            long version) {
    }

    public record BookingSummary(
            UUID bookingId,
            UUID eventId,
            UUID resourceId,
            String resourceName,
            String eventTitle,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String organizerName,
            String organizerEmail,
            String status,
            Long requestedBy,
            String decisionNote,
            OffsetDateTime decidedAt,
            Long decidedBy,
            long version) {
    }

    public record BookingDecisionRequest(
            @NotNull @Pattern(regexp = "APPROVE|DECLINE") String decision,
            @Size(max = 1000) String note,
            @NotNull @Min(0) Long version) {
    }

    public record Policy(
            int weekStart,
            LocalTime workingDayStart,
            LocalTime workingDayEnd,
            int defaultEventMinutes,
            int minimumEventMinutes,
            int maximumEventMinutes,
            int maximumAdvanceDays,
            int defaultBufferMinutes,
            int weeklyFocusTargetMinutes,
            int dailyMeetingLimitMinutes,
            boolean enforceMeetingAgenda,
            boolean allowExternalAttendees,
            long version) {
    }

    public record PolicyRequest(
            @Min(1) @Max(7) int weekStart,
            @NotNull LocalTime workingDayStart,
            @NotNull LocalTime workingDayEnd,
            @Min(5) @Max(1440) int defaultEventMinutes,
            @Min(5) @Max(1440) int minimumEventMinutes,
            @Min(5) @Max(1440) int maximumEventMinutes,
            @Min(1) @Max(1095) int maximumAdvanceDays,
            @Min(0) @Max(120) int defaultBufferMinutes,
            @Min(0) @Max(6000) int weeklyFocusTargetMinutes,
            @Min(30) @Max(1440) int dailyMeetingLimitMinutes,
            boolean enforceMeetingAgenda,
            boolean allowExternalAttendees,
            @NotNull @Min(0) Long version) {
    }

    public record ResourceRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{2,79}") String code,
            @NotBlank @Size(max = 160) String nameKo,
            @NotBlank @Size(max = 160) String nameEn,
            @NotNull ResourceType type,
            @NotBlank @Size(max = 160) String site,
            @Size(max = 80) String floor,
            @Min(1) @Max(10000) int capacity,
            @NotNull @Size(max = 50) List<@NotBlank @Size(max = 60) String> features,
            @NotBlank @Size(max = 80) String timeZone,
            boolean approvalRequired,
            @NotNull ResourceState state,
            Long version) {
    }

    public record AdminOverview(
            long activeResources,
            long resourcesInMaintenance,
            long bookingsThisWeek,
            long pendingBookings,
            long eventsThisWeek,
            long conflictedUsers,
            Policy policy,
            List<ResourceSummary> resources,
            OffsetDateTime generatedAt) {
    }
}
