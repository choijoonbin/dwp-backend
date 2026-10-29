package com.dwp.services.platform.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.*;

/** Stable row-model owner inherited by the CalendarRepository facade. */
abstract class CalendarRepositoryRows {
    record CalendarRow(
            UUID calendarId,
            String calendarKey,
            String name,
            String color,
            CalendarType type,
            String visibility,
            Long ownerUserId,
            UUID ownerPersonPublicId,
            String ownerDisplayName,
            CalendarSourceKind sourceKind,
            CalendarAccessLevel accessLevel,
            CalendarSubscriptionPolicy subscriptionPolicy,
            boolean selected,
            boolean favorite,
            int displayOrder,
            long calendarVersion,
            long subscriptionVersion,
            boolean canViewPrivate) {

        CalendarRow(
                UUID calendarId,
                String calendarKey,
                String name,
                String color,
                CalendarType type,
                String visibility,
                Long ownerUserId) {
            this(calendarId, calendarKey, name, color, type, visibility, ownerUserId,
                    null, null, CalendarSourceKind.OWNED, CalendarAccessLevel.OWNER,
                    CalendarSubscriptionPolicy.OPTIONAL, true, false, 0,
                    0, 0, true);
        }
    }

    record EventRow(
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
            ResourceRow resource,
            EventImportance importance,
            EventDetailLevel detailLevel,
            boolean starred,
            long preferenceVersion,
            CalendarAccessLevel accessLevel,
            long version,
            List<DayOfWeek> recurrenceDays,
            Integer recurrenceCount) {

        EventRow(
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
                ResourceRow resource,
                EventImportance importance,
                EventDetailLevel detailLevel,
                boolean starred,
                long preferenceVersion,
                CalendarAccessLevel accessLevel,
                long version) {
            this(eventId, calendarId, calendarName, calendarColor, organizerUserId,
                    organizerPersonPublicId, organizerName, organizerEmail, title,
                    description, type, startsAt, endsAt, timeZone, allDay, location,
                    conferenceUrl, status, visibility, recurrence, recurrenceInterval,
                    recurrenceUntil, responseRequired, myResponse, resource, importance,
                    detailLevel, starred, preferenceVersion, accessLevel, version,
                    List.of(), null);
        }

        EventRow(
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
                ResourceRow resource,
                long version) {
            this(eventId, calendarId, calendarName, calendarColor, organizerUserId,
                    organizerPersonPublicId, organizerName, organizerEmail, title,
                    description, type, startsAt, endsAt, timeZone, allDay, location,
                    conferenceUrl, status, visibility, recurrence, recurrenceInterval,
                    recurrenceUntil, responseRequired, myResponse, resource,
                    EventImportance.NORMAL, EventDetailLevel.FULL, false,
                    0, CalendarAccessLevel.OWNER, version, List.of(), null);
        }
    }

    record CalendarAccessDecision(
            UUID calendarId,
            CalendarType calendarType,
            CalendarSubscriptionPolicy subscriptionPolicy,
            Long ownerUserId,
            UUID ownerPersonPublicId,
            CalendarAccessLevel accessLevel,
            boolean canViewPrivate,
            long version) {
    }

    record AttendeeRow(
            Long userId,
            UUID personPublicId,
            String email,
            String name,
            AttendeeType type,
            ResponseStatus response) {
    }

    record IdempotencyRow(UUID eventId, String requestFingerprint) {
    }

    record ResourceRow(
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
            long version) implements CalendarResourceView {
    }

    record BookingRow(
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

    record PolicyRow(
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

    record AdminStats(
            long activeResources,
            long resourcesInMaintenance,
            long bookingsThisWeek,
            long pendingBookings,
            long eventsThisWeek,
            long conflictedUsers) {
    }

    record BusyRow(UUID personPublicId, OffsetDateTime startsAt, OffsetDateTime endsAt) {
    }
}
