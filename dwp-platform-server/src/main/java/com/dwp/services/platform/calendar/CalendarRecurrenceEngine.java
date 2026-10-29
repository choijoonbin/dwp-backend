package com.dwp.services.platform.calendar;

import com.dwp.services.platform.calendar.CalendarTypes.RecurrencePattern;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Canonical local-wall-time recurrence semantics for Calendar.
 *
 * <p>MONTHLY intentionally advances from the previously clamped local date. Consequently a
 * January 31 monthly series projects January 31, February 28/29, then March 28/29. This preserves
 * the product's established sequential-clamp behavior. Zoned projection resolves DST gaps and
 * overlaps through {@link LocalDateTime#atZone(ZoneId)}, keeping the requested local wall time
 * whenever that time exists.</p>
 */
final class CalendarRecurrenceEngine {

    static final int MAX_SERIES_OCCURRENCES = 4000;

    Rule rule(
            RecurrencePattern pattern,
            int interval,
            LocalDate until,
            Integer count,
            Set<DayOfWeek> days,
            String timeZone) {
        return new Rule(pattern, interval, until, count, days, zone(timeZone));
    }

    List<Projection> project(
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            Rule rule,
            OffsetDateTime from,
            OffsetDateTime to,
            int limit) {
        requireWindow(startsAt, endsAt, from, to, limit);
        validate(startsAt, rule);
        Duration duration = Duration.between(startsAt, endsAt);
        List<Projection> result = new ArrayList<>();
        LocalDateTime anchorLocal = startsAt.atZoneSameInstant(rule.zone()).toLocalDateTime();
        Cursor seed = fastForward(
                anchorLocal, rule,
                from.minus(duration).atZoneSameInstant(rule.zone()).toLocalDateTime());
        LocalDateTime currentLocal = seed.current();
        OffsetDateTime current = currentLocal.atZone(rule.zone()).toOffsetDateTime();
        long ordinal = seed.ordinal();
        int generated = 0;
        while (generated < limit && allowed(currentLocal.toLocalDate(), ordinal, rule)) {
            OffsetDateTime occurrenceEnd = current.plus(duration);
            if (current.isBefore(to) && occurrenceEnd.isAfter(from)) {
                result.add(new Projection(safeOrdinal(ordinal), current, occurrenceEnd));
            }
            if (rule.pattern() == RecurrencePattern.NONE || !current.isBefore(to)) break;
            currentLocal = nextLocal(anchorLocal, currentLocal, rule);
            current = currentLocal.atZone(rule.zone()).toOffsetDateTime();
            ordinal++;
            generated++;
        }
        if (generated >= limit && current.isBefore(to)
                && allowed(currentLocal.toLocalDate(), ordinal, rule)) {
            throw new ProjectionLimitExceededException(limit);
        }
        return List.copyOf(result);
    }

    OffsetDateTime next(OffsetDateTime anchor, OffsetDateTime current, Rule rule) {
        validate(anchor, rule);
        LocalDateTime anchorLocal = anchor.atZoneSameInstant(rule.zone()).toLocalDateTime();
        LocalDateTime currentLocal = current.atZoneSameInstant(rule.zone()).toLocalDateTime();
        LocalDateTime next = nextLocal(anchorLocal, currentLocal, rule);
        return next.atZone(rule.zone()).toOffsetDateTime();
    }

    boolean contains(
            OffsetDateTime anchor,
            OffsetDateTime candidate,
            Rule rule,
            int limit) {
        validate(anchor, rule);
        LocalDateTime anchorLocal = anchor.atZoneSameInstant(rule.zone()).toLocalDateTime();
        Cursor seed = fastForward(
                anchorLocal, rule,
                candidate.atZoneSameInstant(rule.zone()).toLocalDateTime());
        LocalDateTime currentLocal = seed.current();
        OffsetDateTime current = currentLocal.atZone(rule.zone()).toOffsetDateTime();
        long ordinal = seed.ordinal();
        int scanned = 0;
        while (scanned < limit && allowed(currentLocal.toLocalDate(), ordinal, rule)
                && !current.isAfter(candidate)) {
            if (current.isEqual(candidate)) return true;
            if (rule.pattern() == RecurrencePattern.NONE) return false;
            currentLocal = nextLocal(anchorLocal, currentLocal, rule);
            current = currentLocal.atZone(rule.zone()).toOffsetDateTime();
            ordinal++;
            scanned++;
        }
        return false;
    }

    void validate(OffsetDateTime anchor, Rule rule) {
        if (anchor == null || rule == null || rule.pattern() == null || rule.zone() == null) {
            throw new IllegalArgumentException("Recurrence anchor, pattern, and time zone are required.");
        }
        if (rule.interval() < 1 || rule.interval() > 52) {
            throw new IllegalArgumentException("Recurrence interval must be between 1 and 52.");
        }
        if (rule.until() != null && rule.count() != null) {
            throw new IllegalArgumentException("Recurrence UNTIL and COUNT are mutually exclusive.");
        }
        if (rule.count() != null
                && (rule.count() < 1 || rule.count() > MAX_SERIES_OCCURRENCES)) {
            throw new IllegalArgumentException("Recurrence COUNT is outside the supported bound.");
        }
        if (rule.pattern() == RecurrencePattern.NONE
                && (rule.until() != null || rule.count() != null || !rule.days().isEmpty())) {
            throw new IllegalArgumentException("A non-recurring event cannot have recurrence bounds.");
        }
        if (rule.pattern() != RecurrencePattern.WEEKLY && !rule.days().isEmpty()) {
            throw new IllegalArgumentException("BYDAY is supported only for weekly recurrence.");
        }
        if (rule.pattern() == RecurrencePattern.WEEKLY && !rule.days().isEmpty()) {
            DayOfWeek anchorDay = anchor.atZoneSameInstant(rule.zone()).getDayOfWeek();
            if (!rule.days().contains(anchorDay)) {
                throw new IllegalArgumentException("Weekly BYDAY must contain the series start day.");
            }
        }
        if (rule.until() != null
                && rule.until().isBefore(anchor.atZoneSameInstant(rule.zone()).toLocalDate())) {
            throw new IllegalArgumentException("Recurrence UNTIL cannot precede the series start.");
        }
    }

    private boolean allowed(LocalDate occurrenceDate, long ordinal, Rule rule) {
        if (rule.count() != null && ordinal > rule.count()) return false;
        return rule.until() == null || !occurrenceDate.isAfter(rule.until());
    }

    private LocalDateTime nextLocal(
            LocalDateTime anchor,
            LocalDateTime current,
            Rule rule) {
        return switch (rule.pattern()) {
            case NONE -> current;
            case DAILY -> current.plusDays(rule.interval());
            case WEEKLY -> weeklyNext(anchor, current, rule);
            case MONTHLY -> current.plusMonths(rule.interval());
            case YEARLY -> current.plusYears(rule.interval());
        };
    }

    private Cursor fastForward(
            LocalDateTime anchor,
            Rule rule,
            LocalDateTime target) {
        if (rule.count() != null || !target.isAfter(anchor)) return new Cursor(anchor, 1);
        return switch (rule.pattern()) {
            case NONE -> new Cursor(anchor, 1);
            case DAILY -> fixedStep(
                    anchor, target, rule.interval(), ChronoUnit.DAYS);
            case WEEKLY -> rule.days().isEmpty()
                    ? fixedStep(anchor, target, rule.interval(), ChronoUnit.WEEKS)
                    : weeklySeed(anchor, target, rule);
            case MONTHLY -> monthlySeed(anchor, target, rule.interval());
            case YEARLY -> yearlySeed(anchor, target, rule.interval());
        };
    }

    private Cursor fixedStep(
            LocalDateTime anchor,
            LocalDateTime target,
            int interval,
            ChronoUnit unit) {
        long distance = unit.between(anchor.toLocalDate(), target.toLocalDate());
        long steps = Math.max(0, Math.floorDiv(distance, interval) - 1);
        LocalDateTime current = unit == ChronoUnit.DAYS
                ? anchor.plusDays(steps * interval)
                : anchor.plusWeeks(steps * interval);
        return new Cursor(current, steps + 1);
    }

    private Cursor weeklySeed(
            LocalDateTime anchor,
            LocalDateTime target,
            Rule rule) {
        LocalDate anchorWeek = anchor.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate targetWeek = target.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long elapsedWeeks = ChronoUnit.WEEKS.between(anchorWeek, targetWeek);
        long cycle = Math.max(0, Math.floorDiv(elapsedWeeks, rule.interval()) - 1);
        if (cycle == 0) return new Cursor(anchor, 1);
        DayOfWeek first = rule.days().stream().min(DayOfWeek::compareTo).orElseThrow();
        LocalDate date = anchorWeek.plusWeeks(cycle * rule.interval())
                .plusDays(first.getValue() - 1L);
        long firstCycle = rule.days().stream()
                .filter(day -> day.getValue() >= anchor.getDayOfWeek().getValue())
                .count();
        long ordinal = firstCycle + (cycle - 1) * rule.days().size() + 1;
        return new Cursor(LocalDateTime.of(date, anchor.toLocalTime()), ordinal);
    }

    private Cursor monthlySeed(
            LocalDateTime anchor,
            LocalDateTime target,
            int interval) {
        LocalDateTime current = anchor;
        long steps = 0;
        int cycle = 12 / gcd(12, interval);
        for (int index = 0; index < cycle && current.isBefore(target); index++) {
            current = current.plusMonths(interval);
            steps++;
        }
        long distance = ChronoUnit.MONTHS.between(
                YearMonth.from(current), YearMonth.from(target));
        long jump = Math.max(0, Math.floorDiv(distance, interval) - 1);
        return new Cursor(current.plusMonths(jump * interval), steps + jump + 1);
    }

    private Cursor yearlySeed(
            LocalDateTime anchor,
            LocalDateTime target,
            int interval) {
        LocalDateTime current = anchor;
        long steps = 0;
        int cycle = 400 / gcd(400, interval);
        for (int index = 0; index < cycle && current.isBefore(target); index++) {
            current = current.plusYears(interval);
            steps++;
        }
        long distance = ChronoUnit.YEARS.between(current.toLocalDate(), target.toLocalDate());
        long jump = Math.max(0, Math.floorDiv(distance, interval) - 1);
        return new Cursor(current.plusYears(jump * interval), steps + jump + 1);
    }

    private int gcd(int left, int right) {
        int a = left;
        int b = right;
        while (b != 0) {
            int remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }

    private int safeOrdinal(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private LocalDateTime weeklyNext(
            LocalDateTime anchor,
            LocalDateTime current,
            Rule rule) {
        if (rule.days().isEmpty()) return current.plusWeeks(rule.interval());
        LocalDate anchorWeek = anchor.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate candidate = current.toLocalDate().plusDays(1);
        for (int scanned = 0; scanned < 7 * rule.interval() + 7; scanned++, candidate = candidate.plusDays(1)) {
            LocalDate candidateWeek = candidate.with(
                    TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            long week = ChronoUnit.WEEKS.between(anchorWeek, candidateWeek);
            if (week >= 0 && week % rule.interval() == 0
                    && rule.days().contains(candidate.getDayOfWeek())) {
                return LocalDateTime.of(candidate, current.toLocalTime());
            }
        }
        throw new IllegalStateException("Weekly recurrence projection did not converge.");
    }

    private void requireWindow(
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime from,
            OffsetDateTime to,
            int limit) {
        if (startsAt == null || endsAt == null || from == null || to == null
                || !endsAt.isAfter(startsAt) || !to.isAfter(from)) {
            throw new IllegalArgumentException("A valid event and projection window are required.");
        }
        if (limit < 1 || limit > MAX_SERIES_OCCURRENCES) {
            throw new IllegalArgumentException("Projection limit is outside the supported bound.");
        }
    }

    private ZoneId zone(String value) {
        return ZoneId.of(value == null || value.isBlank() ? "Asia/Seoul" : value);
    }

    record Rule(
            RecurrencePattern pattern,
            int interval,
            LocalDate until,
            Integer count,
            Set<DayOfWeek> days,
            ZoneId zone) {

        Rule {
            days = days == null || days.isEmpty()
                    ? Set.of() : Set.copyOf(EnumSet.copyOf(days));
        }
    }

    record Projection(int ordinal, OffsetDateTime startsAt, OffsetDateTime endsAt) {
    }

    private record Cursor(LocalDateTime current, long ordinal) {
    }

    static final class ProjectionLimitExceededException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        ProjectionLimitExceededException(int limit) {
            super("Calendar recurrence projection exceeds its limit of " + limit + ".");
        }
    }
}
