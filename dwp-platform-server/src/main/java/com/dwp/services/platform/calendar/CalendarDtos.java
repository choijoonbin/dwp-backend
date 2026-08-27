package com.dwp.services.platform.calendar;

import io.swagger.v3.oas.annotations.media.Schema;
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
            UUID ownerPersonPublicId,
            String ownerDisplayName,
            CalendarSourceKind sourceKind,
            CalendarAccessLevel accessLevel,
            CalendarSubscriptionPolicy subscriptionPolicy,
            boolean required,
            boolean selected,
            boolean favorite,
            int displayOrder,
            long version,
            long subscriptionVersion,
            CalendarCapabilities capabilities) {

        public CalendarSummary(
                UUID calendarId,
                String calendarKey,
                String name,
                String color,
                CalendarType type,
                String visibility,
                boolean selected) {
            this(calendarId, calendarKey, name, color, type, visibility, null, null,
                    CalendarSourceKind.OWNED, CalendarAccessLevel.OWNER,
                    CalendarSubscriptionPolicy.OPTIONAL, false, selected, false, 0,
                    0, 0, CalendarCapabilities.owner());
        }
    }

    public record CalendarCapabilities(
            boolean canViewDetails,
            boolean canCreateEvents,
            boolean canEditCalendar,
            boolean canManageSharing,
            boolean canDeleteCalendar,
            boolean canUnsubscribe) {

        static CalendarCapabilities owner() {
            return new CalendarCapabilities(true, true, true, true, true, true);
        }
    }

    public record EventCapabilities(
            boolean canViewDetails,
            boolean canEdit,
            boolean canDelete,
            boolean canRestore,
            boolean canRespond,
            boolean canStar) {
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
            EventImportance importance,
            EventDetailLevel detailLevel,
            boolean redacted,
            boolean starred,
            long preferenceVersion,
            EventCapabilities capabilities,
            String restrictionReason,
            long version) {

        public EventSummary(
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
            this(eventId, calendarId, calendarName, calendarColor, organizerUserId,
                    organizerPersonPublicId, organizerName, organizerEmail, title, description,
                    type, startsAt, endsAt, timeZone, allDay, location, conferenceUrl, status,
                    visibility, recurrence, recurrenceInterval, recurrenceUntil,
                    responseRequired, myResponse, attendees, resource, conflict,
                    EventImportance.NORMAL, EventDetailLevel.FULL, false, false,
                    0, new EventCapabilities(true, false, false, false,
                            myResponse != null, true), null, version);
        }
    }

    public record CalendarShare(
            UUID grantId,
            String principalType,
            UUID principalPersonPublicId,
            UUID principalGroupRef,
            String principalDisplayName,
            CalendarAccessLevel accessLevel,
            boolean canViewPrivate,
            OffsetDateTime validUntil,
            String lifecycleState,
            long version) {
    }

    public record CalendarShareRequest(
            @NotNull UUID principalPersonPublicId,
            @NotBlank @Size(max = 160) String principalDisplayName,
            @NotNull CalendarAccessLevel accessLevel,
            boolean canViewPrivate,
            OffsetDateTime validUntil,
            @NotNull @Min(0) Long version) {
    }

    public record CalendarSubscriptionRequest(
            boolean selected,
            boolean favorite,
            @Min(0) @Max(10000) int displayOrder,
            @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String colorOverride,
            @NotNull @Min(0) Long version) {
    }

    public record CalendarSubscriptionResponse(
            boolean selected,
            boolean favorite,
            int displayOrder,
            String colorOverride,
            long version) {
    }

    public record EventPreferenceRequest(
            boolean starred,
            boolean hidden,
            @NotNull @Min(0) Long version) {
    }

    public record EventPreferenceResponse(
            boolean starred,
            boolean hidden,
            long version) {
    }

    public record TrashEventRequest(
            @NotNull @Min(0) Long version,
            @Size(max = 500) String reason) {
    }

    public record TrashedEventSummary(
            UUID eventId,
            UUID calendarId,
            String calendarName,
            String calendarColor,
            String title,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime deletedAt,
            OffsetDateTime purgeAfter,
            boolean legalHold,
            String deletionReason,
            EventImportance importance,
            long version,
            EventCapabilities capabilities) {
    }

    public record CompanyCalendarRequest(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{2,79}") String key,
            @NotBlank @Size(max = 160) String nameKo,
            @NotBlank @Size(max = 160) String nameEn,
            @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color,
            @NotNull @Min(0) Long version) {
    }

    public record CompanyCalendarSummary(
            UUID calendarId,
            String key,
            String name,
            String nameKo,
            String nameEn,
            String color,
            int upcomingEventCount,
            int trashedEventCount,
            long version) {
    }

    public record CompanyEventSummary(
            UUID eventId,
            UUID calendarId,
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
            List<Attendee> attendees,
            EventImportance importance,
            OffsetDateTime deletedAt,
            OffsetDateTime purgeAfter,
            boolean legalHold,
            EventCapabilities capabilities,
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
            String reasonCode,
            String reason) {
    }

    public record AvailabilityResponse(
            List<AvailabilityParticipant> participants,
            List<AvailabilitySlot> suggestions,
            OffsetDateTime generatedAt) {
    }

    public record SchedulingEvaluationRequest(
            @NotNull @Size(max = 19) List<@NotNull UUID> personIds,
            @NotNull OffsetDateTime from,
            @NotNull OffsetDateTime to,
            @NotNull OffsetDateTime roomStartsAt,
            @NotNull OffsetDateTime roomEndsAt,
            @Min(5) @Max(1440) int durationMinutes,
            @NotBlank @Size(max = 80) String timeZone) {
    }

    public record SchedulingEvaluationSource(
            String sourceType,
            String status,
            OffsetDateTime lastSuccessfulSyncAt) {
    }

    public record SchedulingEvaluationResponse(
            UUID evaluationId,
            String criteriaHash,
            String completeness,
            List<SchedulingEvaluationSource> sources,
            AvailabilityResponse availability,
            List<ResourceSummary> rooms,
            OffsetDateTime generatedAt,
            OffsetDateTime validUntil) {
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
            @NotNull UUID idempotencyKey,
            UUID calendarId,
            EventImportance importance) {

        public CreateEventRequest(
                String title,
                String description,
                EventType type,
                OffsetDateTime startsAt,
                OffsetDateTime endsAt,
                String timeZone,
                boolean allDay,
                String location,
                String conferenceUrl,
                EventVisibility visibility,
                RecurrencePattern recurrence,
                int recurrenceInterval,
                LocalDate recurrenceUntil,
                boolean responseRequired,
                List<AttendeeInput> attendees,
                UUID resourceId,
                UUID idempotencyKey) {
            this(title, description, type, startsAt, endsAt, timeZone, allDay, location,
                    conferenceUrl, visibility, recurrence, recurrenceInterval,
                    recurrenceUntil, responseRequired, attendees, resourceId, idempotencyKey,
                    null, EventImportance.NORMAL);
        }
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
            @NotNull @Min(0) Long version,
            EventImportance importance) {

        public UpdateEventRequest(
                String title,
                String description,
                EventType type,
                OffsetDateTime startsAt,
                OffsetDateTime endsAt,
                String timeZone,
                boolean allDay,
                String location,
                String conferenceUrl,
                EventVisibility visibility,
                RecurrencePattern recurrence,
                int recurrenceInterval,
                LocalDate recurrenceUntil,
                boolean responseRequired,
                List<AttendeeInput> attendees,
                UUID resourceId,
                Long version) {
            this(title, description, type, startsAt, endsAt, timeZone, allDay, location,
                    conferenceUrl, visibility, recurrence, recurrenceInterval,
                    recurrenceUntil, responseRequired, attendees, resourceId, version,
                    EventImportance.NORMAL);
        }
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

    public record ResourceOccupancy(
            UUID resourceId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String bookingStatus) {
    }

    public record RoomAvailabilityResponse(
            List<ResourceSummary> rooms,
            List<ResourceOccupancy> occupancy,
            OffsetDateTime generatedAt) {
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

    @Schema(name = "CalendarAdminOverview")
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
