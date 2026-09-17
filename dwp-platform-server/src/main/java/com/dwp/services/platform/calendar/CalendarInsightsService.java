package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.dwp.services.platform.calendar.CalendarTypes.EventStatus;
import static com.dwp.services.platform.calendar.CalendarTypes.EventType;

@Service
public class CalendarInsightsService {

    private static final EnumSet<EventType> WORKLOAD_TYPES = EnumSet.of(
            EventType.MEETING, EventType.FOCUS, EventType.TASK);

    private final CalendarRepository repository;
    private final CalendarOccurrenceProjector projector;
    private final CalendarSettingsService settingsService;
    private final Clock clock;

    @Autowired
    public CalendarInsightsService(
            CalendarRepository repository,
            CalendarOccurrenceProjector projector,
            CalendarSettingsService settingsService) {
        this(repository, projector, settingsService, Clock.systemUTC());
    }

    CalendarInsightsService(
            CalendarRepository repository,
            CalendarOccurrenceProjector projector,
            CalendarSettingsService settingsService,
            Clock clock) {
        this.repository = repository;
        this.projector = projector;
        this.settingsService = settingsService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CalendarInsightsDtos.Response insights(
            CalendarSettingsAccess.Actor actor,
            String verifiedGroupRefs,
            int weeks,
            String requestedTimeZone,
            String locale) {
        validateWeeks(weeks);
        repository.linkIdentity(actor.tenantId(), actor.userId(), actor.personPublicId());
        CalendarSettingsDtos.Settings settings = settingsService.settings(actor);
        ZoneId zone = zone(requestedTimeZone, settings.timeZone());
        LocalDate today = Instant.now(clock).atZone(zone).toLocalDate();
        LocalDate currentWeekStart = today.with(
                TemporalAdjusters.previousOrSame(settings.weekStart()));
        LocalDate periodEnd = currentWeekStart.plusWeeks(1);
        LocalDate periodStart = periodEnd.minusWeeks(weeks);
        LocalDate previousStart = periodStart.minusWeeks(weeks);
        OffsetDateTime queryFrom = previousStart.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime queryTo = periodEnd.atStartOfDay(zone).toOffsetDateTime();

        List<CalendarDtos.EventSummary> events = projector.summaries(
                actor.tenantId(), actor.userId(), actor.personPublicId(), verifiedGroupRefs,
                queryFrom, queryTo, locale);
        CalendarInsightsDtos.Metrics current = metrics(
                events, periodStart, periodEnd, zone, settings);
        CalendarInsightsDtos.Metrics previous = metrics(
                events, previousStart, periodStart, zone, settings);
        List<CalendarInsightsDtos.Week> trend = new ArrayList<>();
        for (int index = 0; index < weeks; index++) {
            LocalDate start = periodStart.plusWeeks(index);
            LocalDate end = start.plusWeeks(1);
            trend.add(new CalendarInsightsDtos.Week(
                    start, end.minusDays(1), metrics(events, start, end, zone, settings)));
        }
        return new CalendarInsightsDtos.Response(
                weeks, periodStart, periodEnd.minusDays(1), previousStart,
                periodStart.minusDays(1), zone.getId(), settings.workingDays(),
                settings.workingDayStart(), settings.workingDayEnd(), current, previous,
                trend, "DWP_NATIVE_CALENDAR", "COMPLETE",
                OffsetDateTime.ofInstant(Instant.now(clock), zone));
    }

    static void validateWeeks(int weeks) {
        if (weeks == 4 || weeks == 8 || weeks == 12) return;
        throw new BaseException(
                ErrorCode.INVALID_INPUT_VALUE,
                "Calendar insight period must be 4, 8, or 12 weeks.");
    }

    private CalendarInsightsDtos.Metrics metrics(
            List<CalendarDtos.EventSummary> events,
            LocalDate from,
            LocalDate to,
            ZoneId zone,
            CalendarSettingsDtos.Settings settings) {
        Instant periodStart = from.atStartOfDay(zone).toInstant();
        Instant periodEnd = to.atStartOfDay(zone).toInstant();
        Map<LocalDate, DayMetrics> days = workingDayMetrics(from, to, settings.workingDays());
        Totals totals = new Totals();
        for (CalendarDtos.EventSummary event : events) {
            if (event.status() == EventStatus.CANCELLED || !WORKLOAD_TYPES.contains(event.type())) {
                continue;
            }
            Instant startsAt = latest(event.startsAt().toInstant(), periodStart);
            Instant endsAt = earliest(event.endsAt().toInstant(), periodEnd);
            if (!startsAt.isBefore(endsAt)) continue;
            totals.eventCount++;
            if (event.conflict()) totals.conflictCount++;
            boolean protectedFocus = event.type() == EventType.FOCUS
                    && !event.conflict()
                    && Duration.between(startsAt, endsAt).toMinutes() >= 45;
            splitAcrossDays(
                    startsAt, endsAt, event.type(), protectedFocus, zone, settings, days, totals);
        }
        int noMeetingDays = (int) days.values().stream()
                .filter(DayMetrics::workingDay)
                .filter(day -> day.meetingMinutes == 0)
                .count();
        int fragmentedDays = (int) days.values().stream()
                .filter(day -> day.meetingBlocks >= 3)
                .count();
        int quality = totals.focusMinutes == 0 ? 0 : Math.min(
                100, Math.round(totals.protectedFocusMinutes * 100f / totals.focusMinutes));
        return new CalendarInsightsDtos.Metrics(
                totals.eventCount, totals.meetingMinutes, totals.focusMinutes,
                totals.protectedFocusMinutes, quality, totals.afterHoursMinutes,
                noMeetingDays, fragmentedDays, totals.conflictCount);
    }

    private void splitAcrossDays(
            Instant startsAt,
            Instant endsAt,
            EventType type,
            boolean protectedFocus,
            ZoneId zone,
            CalendarSettingsDtos.Settings settings,
            Map<LocalDate, DayMetrics> days,
            Totals totals) {
        LocalDate date = startsAt.atZone(zone).toLocalDate();
        LocalDate last = endsAt.minusNanos(1).atZone(zone).toLocalDate();
        while (!date.isAfter(last)) {
            Instant dayStart = date.atStartOfDay(zone).toInstant();
            Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
            Instant segmentStart = latest(startsAt, dayStart);
            Instant segmentEnd = earliest(endsAt, dayEnd);
            int minutes = minutes(segmentStart, segmentEnd);
            DayMetrics day = days.get(date);
            if (day == null) {
                day = new DayMetrics(settings.workingDays().contains(date.getDayOfWeek()));
                days.put(date, day);
            }
            if (type == EventType.MEETING) {
                totals.meetingMinutes += minutes;
                day.meetingMinutes += minutes;
                day.meetingBlocks++;
            } else if (type == EventType.FOCUS) {
                totals.focusMinutes += minutes;
                if (protectedFocus) totals.protectedFocusMinutes += minutes;
            }
            totals.afterHoursMinutes += outsideWorkingMinutes(
                    date, segmentStart, segmentEnd, zone, settings, day.workingDay);
            date = date.plusDays(1);
        }
    }

    private int outsideWorkingMinutes(
            LocalDate date,
            Instant startsAt,
            Instant endsAt,
            ZoneId zone,
            CalendarSettingsDtos.Settings settings,
            boolean workingDay) {
        int total = minutes(startsAt, endsAt);
        if (!workingDay) return total;
        Instant workingStart = date.atTime(settings.workingDayStart()).atZone(zone).toInstant();
        Instant workingEnd = date.atTime(settings.workingDayEnd()).atZone(zone).toInstant();
        int inside = minutes(latest(startsAt, workingStart), earliest(endsAt, workingEnd));
        return Math.max(0, total - inside);
    }

    private Map<LocalDate, DayMetrics> workingDayMetrics(
            LocalDate from,
            LocalDate to,
            List<DayOfWeek> workingDays) {
        Map<LocalDate, DayMetrics> result = new HashMap<>();
        for (LocalDate date = from; date.isBefore(to); date = date.plusDays(1)) {
            result.put(date, new DayMetrics(workingDays.contains(date.getDayOfWeek())));
        }
        return result;
    }

    private ZoneId zone(String requested, String fallback) {
        String value = requested == null || requested.isBlank() ? fallback : requested.trim();
        try {
            return ZoneId.of(value);
        } catch (RuntimeException exception) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid calendar time zone.");
        }
    }

    private Instant latest(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private Instant earliest(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private int minutes(Instant start, Instant end) {
        return start.isBefore(end) ? Math.toIntExact(Duration.between(start, end).toMinutes()) : 0;
    }

    private static final class DayMetrics {
        private final boolean workingDay;
        private int meetingMinutes;
        private int meetingBlocks;

        private DayMetrics(boolean workingDay) {
            this.workingDay = workingDay;
        }

        private boolean workingDay() {
            return workingDay;
        }
    }

    private static final class Totals {
        private int eventCount;
        private int meetingMinutes;
        private int focusMinutes;
        private int protectedFocusMinutes;
        private int afterHoursMinutes;
        private int conflictCount;
    }
}
