package com.dwp.services.platform.workplace;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.dwp.services.platform.workplace.WorkplaceExperienceReportDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceExperienceReportRepository.*;

/** Reservation minutes are a union per current reservable resource, never sensor occupancy. */
final class WorkplaceExperienceReportMetrics {
    private static final Set<String> OCCUPIED =
            Set.of("RESERVED", "CHECKED_IN", "COMPLETED", "RELEASED");
    private static final Set<String> SETTLED = Set.of("COMPLETED", "RELEASED", "NO_SHOW");
    private final List<BookingRow> bookings;
    private final Set<UUID> roster;
    private final OffsetDateTime observedAt;

    WorkplaceExperienceReportMetrics(List<ResourceRow> resources, List<BookingRow> bookings,
                                     OffsetDateTime observedAt) {
        this.bookings = bookings;
        this.roster = resources.stream().filter(ResourceRow::inDenominator)
                .map(ResourceRow::resourceId).collect(Collectors.toUnmodifiableSet());
        this.observedAt = observedAt;
    }

    Summary summary(OffsetDateTime from, OffsetDateTime to) {
        List<BookingRow> rows = intersecting(from, to);
        long count = rows.stream().filter(b -> !"CANCELLED".equals(b.status())).count();
        long cancelled = rows.size() - count;
        long eligible = rows.stream().filter(b -> !b.startsAt().isBefore(from)).filter(this::settled).count();
        long noShow = rows.stream().filter(this::settled)
                .filter(b -> !b.startsAt().isBefore(from))
                .filter(b -> "NO_SHOW".equals(b.status())).count();
        long unresolved = rows.stream().filter(b -> !b.endsAt().isAfter(observedAt))
                .filter(b -> Set.of("RESERVED", "CHECKED_IN").contains(b.status())).count();
        double denominator = roster.size() * minutes(from, to);
        double booked = bookedMinutes(rows, from, to);
        Double utilization = count == 0 ? null : percent(booked, denominator);
        Double noShowPercent = eligible == 0 || unresolved > 0 ? null : percent(noShow, eligible);
        Double peak = null;
        for (OffsetDateTime start = from; start.isBefore(to); start = start.plusHours(1)) {
            OffsetDateTime end = earlier(start.plusHours(1), to);
            Double value = count == 0 ? null
                    : percent(bookedMinutes(rows, start, end), roster.size() * minutes(start, end));
            if (value != null && (peak == null || value > peak)) peak = value;
        }
        return new Summary(count, cancelled, round(booked), round(denominator), utilization,
                eligible, noShow, noShowPercent, peak, unresolved);
    }

    List<HeatmapCell> heatmap(LocalDate from, LocalDate to, ZoneId zone) {
        List<HeatmapCell> cells = new ArrayList<>();
        ZonedDateTime end = to.atStartOfDay(zone);
        // Walk instants: a DST fold produces two distinct cells with the same civil hour.
        for (ZonedDateTime cursor = from.atStartOfDay(zone); cursor.isBefore(end);
             cursor = cursor.plusHours(1)) {
            OffsetDateTime start = cursor.toOffsetDateTime();
            OffsetDateTime finish = earlier(cursor.plusHours(1).toOffsetDateTime(), end.toOffsetDateTime());
            List<BookingRow> rows = intersecting(start, finish);
            double booked = bookedMinutes(rows, start, finish);
            double denominator = roster.size() * minutes(start, finish);
            cells.add(new HeatmapCell(cursor.toLocalDate(), cursor.getDayOfWeek().getValue(),
                    cursor.getHour(), cursor.getOffset().getId(), start, finish,
                    round(booked), round(denominator), rows.isEmpty() ? null : percent(booked, denominator)));
        }
        return cells;
    }

    List<DailyTrend> trend(LocalDate from, LocalDate to, ZoneId zone) {
        List<DailyTrend> result = new ArrayList<>();
        for (LocalDate day = from; day.isBefore(to); day = day.plusDays(1)) {
            Summary s = summary(day.atStartOfDay(zone).toOffsetDateTime(),
                    day.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
            result.add(new DailyTrend(day, s.bookingCount(), s.bookedMinutes(),
                    s.denominatorResourceMinutes(), s.utilizationPercent(),
                    s.noShowCount(), s.noShowPercent()));
        }
        return result;
    }

    private List<BookingRow> intersecting(OffsetDateTime from, OffsetDateTime to) {
        return bookings.stream().filter(b -> b.startsAt().isBefore(to) && b.endsAt().isAfter(from)).toList();
    }

    private boolean settled(BookingRow b) {
        return b.requireCheckInSnapshot() && !b.endsAt().isAfter(observedAt) && SETTLED.contains(b.status());
    }

    private double bookedMinutes(List<BookingRow> rows, OffsetDateTime from, OffsetDateTime to) {
        Map<UUID, List<Interval>> byResource = new HashMap<>();
        for (BookingRow b : rows) {
            if (!roster.contains(b.resourceId()) || !OCCUPIED.contains(b.status())) continue;
            OffsetDateTime end = earlier(b.endsAt(), to);
            if (b.releasedAt() != null) end = earlier(end, b.releasedAt());
            OffsetDateTime start = later(b.startsAt(), from);
            if (end.isAfter(start)) byResource.computeIfAbsent(b.resourceId(), ignored -> new ArrayList<>())
                    .add(new Interval(start, end));
        }
        double total = 0;
        for (List<Interval> intervals : byResource.values()) {
            intervals.sort(Comparator.comparing(i -> i.from().toInstant()));
            Interval current = intervals.getFirst();
            for (int i = 1; i < intervals.size(); i++) {
                Interval next = intervals.get(i);
                if (!next.from().isAfter(current.to())) {
                    current = new Interval(current.from(), later(current.to(), next.to()));
                } else {
                    total += minutes(current.from(), current.to());
                    current = next;
                }
            }
            total += minutes(current.from(), current.to());
        }
        return total;
    }

    static Double change(Double current, Double previous) {
        return current == null || previous == null ? null : round(current - previous);
    }
    static Double percent(double numerator, double denominator) {
        return denominator <= 0 ? null : round(100 * numerator / denominator);
    }
    private static double round(double value) { return Math.round(value * 100.0) / 100.0; }
    private static double minutes(OffsetDateTime from, OffsetDateTime to) {
        return Duration.between(from.toInstant(), to.toInstant()).toMillis() / 60_000.0;
    }
    private static OffsetDateTime earlier(OffsetDateTime a, OffsetDateTime b) { return a.isBefore(b) ? a : b; }
    private static OffsetDateTime later(OffsetDateTime a, OffsetDateTime b) { return a.isAfter(b) ? a : b; }
    private record Interval(OffsetDateTime from, OffsetDateTime to) { }
}
