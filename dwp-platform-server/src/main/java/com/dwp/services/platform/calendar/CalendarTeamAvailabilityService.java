package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTeamAvailabilityDtos.*;
import static com.dwp.services.platform.calendar.CalendarTeamAvailabilityRepository.*;

@Service
public class CalendarTeamAvailabilityService {
    static final int MAX_MEMBERS = 20;
    static final int MAX_ROWS = 4000;
    private final CalendarTeamAvailabilityRepository repository;
    private final Clock clock;

    @Autowired
    public CalendarTeamAvailabilityService(CalendarTeamAvailabilityRepository repository) {
        this(repository, Clock.systemUTC());
    }

    CalendarTeamAvailabilityService(CalendarTeamAvailabilityRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot(CalendarTeamAvailabilityAccess.Actor actor, String timeZone) {
        if (actor == null || actor.tenantId() <= 0 || actor.userId() <= 0
                || actor.personPublicId() == null || actor.groupRefs() == null
                || actor.groupRefs().size() > 200) throw CalendarTeamAvailabilityAccess.denied();
        ZoneId zone = zone(timeZone);
        if (!repository.matchesIdentity(actor)) throw CalendarTeamAvailabilityAccess.denied();
        OffsetDateTime now = OffsetDateTime.now(clock);
        var date = now.atZoneSameInstant(zone).toLocalDate();
        OffsetDateTime from = date.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime to = date.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        List<MemberRow> candidates = repository.members(actor, now, MAX_MEMBERS + 1);
        List<MemberRow> members = candidates.stream().limit(MAX_MEMBERS).toList();
        OffsetDateTime validUntil = now.plusSeconds(30);
        // A day/time-zone boundary or a known share expiry must invalidate even an otherwise fresh response.
        if (to.isBefore(validUntil)) validUntil = to;
        for (MemberRow member : members) {
            if (member.validUntil() != null && member.validUntil().isBefore(validUntil)) {
                validUntil = member.validUntil();
            }
        }
        List<ScheduleRow> schedules = repository.schedules(actor, now,
                members.stream().map(MemberRow::personPublicId).toList(), from, to, MAX_ROWS + 1);
        if (schedules.size() > MAX_ROWS) throw unavailable();
        List<OverrideRow> overrides = repository.overrides(actor.tenantId(),
                schedules.stream().map(ScheduleRow::eventId).distinct().toList(), MAX_ROWS + 1);
        if (overrides.size() > MAX_ROWS) throw unavailable();
        Map<UUID, List<OverrideRow>> overridesByEvent = new HashMap<>();
        overrides.forEach(value -> overridesByEvent.computeIfAbsent(value.eventId(), ignored -> new ArrayList<>()).add(value));
        Map<UUID, List<Window>> byPerson = new HashMap<>();
        int count = 0;
        for (ScheduleRow row : schedules) {
            List<Window> windows = project(row, overridesByEvent.getOrDefault(row.eventId(), List.of()), from, to);
            count += windows.size();
            if (count > MAX_ROWS) throw unavailable();
            byPerson.computeIfAbsent(row.personPublicId(), ignored -> new ArrayList<>()).addAll(windows);
        }
        List<Member> result = members.stream()
                .map(member -> member(member, byPerson.getOrDefault(member.personPublicId(), List.of()), now, to))
                .toList();
        if (!validUntil.isAfter(OffsetDateTime.now(clock))) throw unavailable();
        return new Snapshot(date, zone.getId(), now, validUntil, "DWP_NATIVE_CALENDAR", "SHARED_WITH_ME",
                result, candidates.size() > MAX_MEMBERS);
    }

    private List<Window> project(ScheduleRow row, List<OverrideRow> overrides, OffsetDateTime from, OffsetDateTime to) {
        if (row.startsAt() == null || row.endsAt() == null || !row.endsAt().isAfter(row.startsAt())) throw unavailable();
        List<Window> result = new ArrayList<>();
        if ("NONE".equals(row.recurrence())) {
            addUnlessOverridden(result, row, row.startsAt(), row.endsAt(), overrides, from, to);
        } else {
            if (row.recurrenceInterval() < 1) throw unavailable();
            try {
                ZoneId zone = ZoneId.of(row.timeZone());
                ZonedDateTime origin = row.startsAt().atZoneSameInstant(zone);
                Duration duration = Duration.between(row.startsAt(), row.endsAt());
                ZonedDateTime earliest = from.minus(duration).atZoneSameInstant(zone);
                long distance = switch (row.recurrence()) {
                    case "DAILY" -> ChronoUnit.DAYS.between(origin.toLocalDate(), earliest.toLocalDate());
                    case "WEEKLY" -> ChronoUnit.DAYS.between(origin.toLocalDate(), earliest.toLocalDate()) / 7;
                    case "MONTHLY" -> ChronoUnit.MONTHS.between(origin.toLocalDate().withDayOfMonth(1), earliest.toLocalDate().withDayOfMonth(1));
                    default -> throw unavailable();
                };
                long index = Math.max(0, distance / row.recurrenceInterval() - 1);
                int guard = 0;
                while (true) {
                    if (++guard > MAX_ROWS) throw unavailable();
                    long increment = Math.multiplyExact(index++, row.recurrenceInterval());
                    ZonedDateTime local = switch (row.recurrence()) {
                        case "DAILY" -> origin.plusDays(increment);
                        case "WEEKLY" -> origin.plusWeeks(increment);
                        case "MONTHLY" -> origin.plusMonths(increment);
                        default -> throw unavailable();
                    };
                    OffsetDateTime start = local.toOffsetDateTime();
                    if (!start.isBefore(to) || (row.recurrenceUntil() != null && local.toLocalDate().isAfter(row.recurrenceUntil()))) break;
                    addUnlessOverridden(result, row, start, start.plus(duration), overrides, from, to);
                }
            } catch (DateTimeException | ArithmeticException exception) {
                throw unavailable();
            }
        }
        for (OverrideRow override : overrides) {
            if ("MODIFIED".equals(override.kind())) add(result, override.startsAt(), override.endsAt(), row.visibleType(), from, to);
        }
        return result;
    }

    private void addUnlessOverridden(List<Window> result, ScheduleRow row, OffsetDateTime start,
                                    OffsetDateTime end, List<OverrideRow> overrides, OffsetDateTime from, OffsetDateTime to) {
        if (overrides.stream().noneMatch(value -> value.originalStartsAt().isEqual(start))) {
            add(result, start, end, row.visibleType(), from, to);
        }
    }

    private void add(List<Window> result, OffsetDateTime start, OffsetDateTime end, Status type,
                     OffsetDateTime from, OffsetDateTime to) {
        if (start == null || end == null || !end.isAfter(start)) throw unavailable();
        if (start.isBefore(to) && end.isAfter(from)) result.add(new Window(
                start.isBefore(from) ? from : start, end.isAfter(to) ? to : end, type));
    }

    private Member member(MemberRow row, List<Window> windows, OffsetDateTime now, OffsetDateTime dayEnd) {
        List<BusyWindow> merged = new ArrayList<>();
        windows.stream().sorted(Comparator.comparing(Window::startsAt)).forEach(window -> {
            if (!merged.isEmpty() && !window.startsAt().isAfter(merged.getLast().endsAt())) {
                BusyWindow last = merged.removeLast();
                merged.add(new BusyWindow(last.startsAt(), window.endsAt().isAfter(last.endsAt()) ? window.endsAt() : last.endsAt()));
            } else merged.add(new BusyWindow(window.startsAt(), window.endsAt()));
        });
        List<Window> current = windows.stream().filter(value -> !value.startsAt().isAfter(now) && value.endsAt().isAfter(now)).toList();
        Status status = current.isEmpty() ? Status.AVAILABLE
                : current.stream().anyMatch(value -> value.type() == Status.BUSY) ? Status.BUSY
                : current.stream().anyMatch(value -> value.type() == Status.OUT_OF_OFFICE) ? Status.OUT_OF_OFFICE : Status.FOCUS;
        OffsetDateTime busyUntil = merged.stream().filter(value -> !value.startsAt().isAfter(now) && value.endsAt().isAfter(now))
                .map(BusyWindow::endsAt).findFirst().orElse(null);
        OffsetDateTime nextAvailableAt = busyUntil == null ? now : busyUntil.isBefore(dayEnd) ? busyUntil : null;
        long busySeconds = merged.stream().mapToLong(value -> Duration.between(value.startsAt(), value.endsAt()).getSeconds()).sum();
        return new Member(row.personPublicId(), row.displayName(), status, busyUntil, nextAvailableAt,
                Math.toIntExact(busySeconds / 60), List.copyOf(merged));
    }

    private ZoneId zone(String value) {
        try {
            if (value == null || value.isBlank() || value.length() > 80) throw new DateTimeException("Invalid zone");
            return ZoneId.of(value);
        } catch (DateTimeException exception) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The time zone is invalid.");
        }
    }

    private BaseException unavailable() {
        // Never turn truncated, invalid, or expired source data into a false AVAILABLE status.
        return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, "The shared calendar snapshot is unavailable. Refresh and try again.");
    }

    private record Window(OffsetDateTime startsAt, OffsetDateTime endsAt, Status type) { }
}
