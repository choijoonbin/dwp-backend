package com.dwp.services.platform.calendar;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Finds intervals that overlap an interval owned by another event.
 *
 * <p>The two directional sweeps deliberately avoid enumerating overlapping pairs. A forward
 * sweep proves that an interval overlaps an earlier interval and a reverse sweep proves that it
 * overlaps a later interval. Per-event extrema are kept in balanced trees, so dense calendars do
 * not degrade to quadratic work.</p>
 */
final class CalendarConflictSweep {

    private CalendarConflictSweep() {
    }

    static <K> Result<K> conflicts(List<Interval<K>> source) {
        List<Interval<K>> intervals = source.stream()
                .filter(Interval::eligible)
                .sorted(Comparator.comparing(Interval<K>::startsAt)
                        .thenComparing(value -> value.eventId().toString())
                        .thenComparing(value -> String.valueOf(value.key())))
                .toList();
        Set<K> conflicts = new HashSet<>();
        long operations = sweepForward(intervals, conflicts);
        List<Interval<K>> reverse = new ArrayList<>(intervals);
        reverse.sort(Comparator
                .comparing(Interval<K>::startsAt)
                .thenComparing(value -> value.eventId().toString())
                .thenComparing(value -> String.valueOf(value.key()))
                .reversed());
        operations += sweepReverse(reverse, conflicts);
        return new Result<>(Set.copyOf(conflicts), operations);
    }

    private static <K> long sweepForward(List<Interval<K>> values, Set<K> conflicts) {
        Comparator<EventExtreme<K>> order = Comparator
                .comparing(EventExtreme<K>::value)
                .thenComparing(value -> value.eventId().toString());
        TreeSet<EventExtreme<K>> extrema = new TreeSet<>(order);
        Map<UUID, EventExtreme<K>> byEvent = new HashMap<>();
        long operations = 0;
        for (Interval<K> current : values) {
            EventExtreme<K> candidate = extrema.isEmpty() ? null : extrema.last();
            operations++;
            if (candidate != null && candidate.eventId().equals(current.eventId())) {
                candidate = extrema.lower(candidate);
                operations++;
            }
            if (candidate != null && candidate.value().isAfter(current.startsAt())) {
                conflicts.add(current.key());
            }
            EventExtreme<K> previous = byEvent.get(current.eventId());
            if (previous == null || current.endsAt().isAfter(previous.value())) {
                if (previous != null) extrema.remove(previous);
                EventExtreme<K> replacement = new EventExtreme<>(
                        current.eventId(), current.endsAt(), current.key());
                extrema.add(replacement);
                byEvent.put(current.eventId(), replacement);
                operations += previous == null ? 2 : 3;
            }
        }
        return operations;
    }

    private static <K> long sweepReverse(List<Interval<K>> values, Set<K> conflicts) {
        Comparator<EventExtreme<K>> order = Comparator
                .comparing(EventExtreme<K>::value)
                .thenComparing(value -> value.eventId().toString());
        TreeSet<EventExtreme<K>> extrema = new TreeSet<>(order);
        Map<UUID, EventExtreme<K>> byEvent = new HashMap<>();
        long operations = 0;
        for (Interval<K> current : values) {
            EventExtreme<K> candidate = extrema.isEmpty() ? null : extrema.first();
            operations++;
            if (candidate != null && candidate.eventId().equals(current.eventId())) {
                candidate = extrema.higher(candidate);
                operations++;
            }
            if (candidate != null && candidate.value().isBefore(current.endsAt())) {
                conflicts.add(current.key());
            }
            EventExtreme<K> previous = byEvent.get(current.eventId());
            if (previous == null || current.startsAt().isBefore(previous.value())) {
                if (previous != null) extrema.remove(previous);
                EventExtreme<K> replacement = new EventExtreme<>(
                        current.eventId(), current.startsAt(), current.key());
                extrema.add(replacement);
                byEvent.put(current.eventId(), replacement);
                operations += previous == null ? 2 : 3;
            }
        }
        return operations;
    }

    record Interval<K>(
            K key,
            UUID eventId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            boolean eligible) {
    }

    record Result<K>(Set<K> conflicts, long operationCount) {
    }

    private record EventExtreme<K>(UUID eventId, OffsetDateTime value, K key) {
    }
}
