package com.dwp.services.platform.calendar;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.EventType;
import static com.dwp.services.platform.calendar.CalendarTypes.ResourceType;
import static com.dwp.services.platform.calendar.CalendarTypes.ResponseStatus;

/**
 * Builds the Calendar home read model inside the read-only transaction owned by
 * {@link CalendarService}. Query aggregation stays separate from command orchestration while all
 * repository reads retain one transaction and one view of access policy.
 */
final class CalendarHomeQuery {

    private final CalendarRepository repository;
    private final CalendarOccurrenceProjector occurrenceProjector;
    private final CalendarEventValidation eventValidation;
    private final CalendarRoomAccessGuard roomAccessGuard;

    CalendarHomeQuery(
            CalendarRepository repository,
            CalendarOccurrenceProjector occurrenceProjector,
            CalendarEventValidation eventValidation,
            CalendarRoomAccessGuard roomAccessGuard) {
        this.repository = repository;
        this.occurrenceProjector = occurrenceProjector;
        this.eventValidation = eventValidation;
        this.roomAccessGuard = roomAccessGuard;
    }

    CalendarDtos.HomeResponse home(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String timeZone,
            String locale,
            String verifiedGroupRefs) {
        ZoneId zone = eventValidation.zone(timeZone);
        repository.linkIdentity(tenantId, userId, personPublicId);
        CalendarRepository.PolicyRow policy = repository.policy(tenantId);
        ZonedDateTime now = ZonedDateTime.now(zone);
        LocalDate today = now.toLocalDate();
        LocalDate weekStartDate = startOfWeek(today, policy.weekStart());
        OffsetDateTime weekStart = weekStartDate.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime weekEnd = weekStartDate.plusDays(7).atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime horizonEnd = now.plusDays(30).toOffsetDateTime();
        if (horizonEnd.isBefore(weekEnd)) horizonEnd = weekEnd;

        List<CalendarDtos.EventSummary> horizonEvents = roomAccessGuard.filterViewableEvents(
                tenantId, userId, verifiedGroupRefs,
                occurrenceProjector.summaries(
                        tenantId, userId, personPublicId, verifiedGroupRefs,
                        weekStart, horizonEnd, locale));
        List<CalendarDtos.EventSummary> weekEvents = horizonEvents.stream()
                .filter(event -> event.startsAt().isBefore(weekEnd)
                        && event.endsAt().isAfter(weekStart))
                .toList();
        OffsetDateTime dayStart = today.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime dayEnd = today.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        List<CalendarDtos.EventSummary> todayEvents = weekEvents.stream()
                .filter(event -> event.startsAt().isBefore(dayEnd)
                        && event.endsAt().isAfter(dayStart))
                .sorted(Comparator.comparing(CalendarDtos.EventSummary::startsAt))
                .toList();
        CalendarDtos.EventSummary next = horizonEvents.stream()
                .filter(event -> event.endsAt().isAfter(now.toOffsetDateTime()))
                .min(Comparator.comparing(CalendarDtos.EventSummary::startsAt))
                .orElse(null);

        int meetingMinutes = minutes(weekEvents, EventType.MEETING, weekStart, weekEnd);
        int focusMinutes = minutes(weekEvents, EventType.FOCUS, weekStart, weekEnd);
        int conflicts = (int) weekEvents.stream()
                .filter(CalendarDtos.EventSummary::conflict)
                .count();
        int responses = (int) weekEvents.stream()
                .filter(event -> event.myResponse() == ResponseStatus.NEEDS_ACTION)
                .count();
        int availableRooms = (int) roomAccessGuard.filterViewableResources(
                        tenantId, userId, verifiedGroupRefs,
                        repository.resources(
                                tenantId, now.toOffsetDateTime(), now.plusHours(1).toOffsetDateTime(),
                                korean(locale), false))
                .stream()
                .filter(resource -> resource.type() == ResourceType.ROOM && resource.available())
                .count();
        CalendarDtos.HomeMetrics metrics = new CalendarDtos.HomeMetrics(
                weekEvents.size(), meetingMinutes, focusMinutes,
                policy.weeklyFocusTargetMinutes(), conflicts, responses, availableRooms);

        List<CalendarDtos.DayLoad> load = new ArrayList<>();
        for (int day = 0; day < 7; day++) {
            LocalDate date = weekStartDate.plusDays(day);
            OffsetDateTime start = date.atStartOfDay(zone).toOffsetDateTime();
            OffsetDateTime end = date.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
            List<CalendarDtos.EventSummary> values = weekEvents.stream()
                    .filter(event -> event.startsAt().isBefore(end)
                            && event.endsAt().isAfter(start))
                    .toList();
            int dailyMeetings = minutes(values, EventType.MEETING, start, end);
            int dailyFocus = minutes(values, EventType.FOCUS, start, end);
            int dailyConflicts = (int) values.stream()
                    .filter(CalendarDtos.EventSummary::conflict)
                    .count();
            int loadPercent = Math.round(
                    dailyMeetings * 100f / Math.max(1, policy.dailyMeetingLimitMinutes()));
            load.add(new CalendarDtos.DayLoad(
                    date, dailyMeetings, dailyFocus,
                    values.size(), dailyConflicts, loadPercent));
        }

        return new CalendarDtos.HomeResponse(
                today, zone.getId(), next, todayEvents, metrics, List.copyOf(load),
                occurrenceProjector.attention(
                        weekEvents, policy, locale, focusMinutes), OffsetDateTime.now());
    }

    private static int minutes(
            List<CalendarDtos.EventSummary> events,
            EventType type,
            OffsetDateTime from,
            OffsetDateTime to) {
        return CalendarHomeTimeAccounting.minutes(events, type, from, to);
    }

    private static LocalDate startOfWeek(LocalDate date, int weekStart) {
        int delta = Math.floorMod(date.getDayOfWeek().getValue() - weekStart, 7);
        return date.minusDays(delta);
    }

    private static boolean korean(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ko");
    }
}
