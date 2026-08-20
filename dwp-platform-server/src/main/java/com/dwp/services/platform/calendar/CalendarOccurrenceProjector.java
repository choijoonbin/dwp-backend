package com.dwp.services.platform.calendar;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.*;

final class CalendarOccurrenceProjector {

    private static final int MAX_OCCURRENCES = 4000;

    private final CalendarRepository repository;

    CalendarOccurrenceProjector(CalendarRepository repository) {
        this.repository = repository;
    }

    List<CalendarDtos.EventSummary> summaries(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            OffsetDateTime from,
            OffsetDateTime to,
            String locale) {
        List<Occurrence> occurrences = occurrences(
                repository.visibleEvents(
                        tenantId, userId, personPublicId, from, to, korean(locale)), from, to);
        Map<OccurrenceKey, Boolean> conflicts = conflictMap(occurrences);
        return occurrences.stream()
                .map(occurrence -> summary(
                        tenantId, userId, personPublicId, occurrence.row(),
                        conflicts.getOrDefault(occurrence.key(), false), locale,
                        occurrence.startsAt(), occurrence.endsAt()))
                .sorted(Comparator.comparing(CalendarDtos.EventSummary::startsAt)
                        .thenComparing(CalendarDtos.EventSummary::title))
                .toList();
    }

    CalendarDtos.EventSummary summary(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            CalendarRepository.EventRow row,
            boolean conflict,
            String locale) {
        return summary(
                tenantId, userId, personPublicId, row, conflict, locale,
                row.startsAt(), row.endsAt());
    }

    boolean isOrganizer(
            CalendarRepository.EventRow row, Long userId, UUID personPublicId) {
        if (row.organizerPersonPublicId() != null) {
            return row.organizerPersonPublicId().equals(personPublicId);
        }
        return row.organizerUserId().equals(userId);
    }

    List<CalendarDtos.AttentionItem> attention(
            List<CalendarDtos.EventSummary> events,
            CalendarRepository.PolicyRow policy,
            String locale) {
        boolean ko = korean(locale);
        Map<String, CalendarDtos.AttentionItem> items = new LinkedHashMap<>();
        events.stream().filter(CalendarDtos.EventSummary::conflict).findFirst().ifPresent(event ->
                items.put("conflict", new CalendarDtos.AttentionItem(
                        "conflict", "HIGH", ko ? "겹치는 일정이 있습니다" : "Schedules overlap",
                        ko ? "시간을 조정하거나 참석 우선순위를 확인하세요."
                                : "Adjust the time or confirm which commitment takes priority.",
                        event.eventId(), "/calendar/schedule")));
        events.stream().filter(event -> event.myResponse() == ResponseStatus.NEEDS_ACTION)
                .findFirst().ifPresent(event -> items.put("response", new CalendarDtos.AttentionItem(
                        "response", "MEDIUM", ko ? "참석 응답이 필요합니다" : "A response is due",
                        ko ? event.title() + " 초대에 응답해 주세요."
                                : "Respond to the invitation for " + event.title() + ".",
                        event.eventId(), "/calendar/schedule")));
        int focus = minutes(events, EventType.FOCUS);
        if (focus < policy.weeklyFocusTargetMinutes()) {
            int gap = policy.weeklyFocusTargetMinutes() - focus;
            items.put("focus", new CalendarDtos.AttentionItem(
                    "focus", "LOW", ko ? "집중시간이 목표보다 부족합니다" : "Focus time is below target",
                    ko ? "이번 주에 " + gap + "분의 집중시간을 더 확보해 보세요."
                            : "Protect " + gap + " more minutes this week.",
                    null, "/calendar/schedule?create=focus"));
        }
        if (policy.enforceMeetingAgenda()) {
            events.stream()
                    .filter(event -> event.type() == EventType.MEETING
                            && (event.description() == null || event.description().isBlank()))
                    .findFirst().ifPresent(event -> items.put("agenda", new CalendarDtos.AttentionItem(
                            "agenda", "MEDIUM", ko ? "회의 안건이 비어 있습니다" : "A meeting needs an agenda",
                            ko ? event.title() + "에 목적과 준비사항을 추가하세요."
                                    : "Add purpose and preparation notes to " + event.title() + ".",
                            event.eventId(), "/calendar/schedule")));
        }
        return items.values().stream().limit(4).toList();
    }

    private List<Occurrence> occurrences(
            List<CalendarRepository.EventRow> rows,
            OffsetDateTime from,
            OffsetDateTime to) {
        List<Occurrence> result = new ArrayList<>();
        for (CalendarRepository.EventRow row : rows) {
            OffsetDateTime startsAt = row.startsAt();
            OffsetDateTime endsAt = row.endsAt();
            if (row.recurrence() == RecurrencePattern.NONE) {
                if (startsAt.isBefore(to) && endsAt.isAfter(from)) {
                    result.add(new Occurrence(row, startsAt, endsAt));
                }
                continue;
            }
            int guard = 0;
            while (!endsAt.isAfter(from) && guard++ < MAX_OCCURRENCES) {
                OffsetDateTime next = increment(
                        startsAt, row.recurrence(), row.recurrenceInterval(), row.timeZone());
                endsAt = next.plus(Duration.between(startsAt, endsAt));
                startsAt = next;
            }
            while (startsAt.isBefore(to) && guard++ < MAX_OCCURRENCES) {
                if (row.recurrenceUntil() != null
                        && startsAt.toLocalDate().isAfter(row.recurrenceUntil())) break;
                if (endsAt.isAfter(from)) result.add(new Occurrence(row, startsAt, endsAt));
                OffsetDateTime next = increment(
                        startsAt, row.recurrence(), row.recurrenceInterval(), row.timeZone());
                endsAt = next.plus(Duration.between(startsAt, endsAt));
                startsAt = next;
            }
        }
        return result;
    }

    OffsetDateTime increment(
            OffsetDateTime value, RecurrencePattern pattern, int interval, String timeZone) {
        ZonedDateTime local = value.atZoneSameInstant(zone(timeZone));
        return switch (pattern) {
            case DAILY -> local.plusDays(interval).toOffsetDateTime();
            case WEEKLY -> local.plusWeeks(interval).toOffsetDateTime();
            case MONTHLY -> local.plusMonths(interval).toOffsetDateTime();
            case NONE -> value;
        };
    }

    private Map<OccurrenceKey, Boolean> conflictMap(List<Occurrence> values) {
        Map<OccurrenceKey, Boolean> conflicts = new HashMap<>();
        for (int left = 0; left < values.size(); left++) {
            for (int right = left + 1; right < values.size(); right++) {
                Occurrence first = values.get(left);
                Occurrence second = values.get(right);
                if (first.row().eventId().equals(second.row().eventId())) continue;
                if (first.startsAt().isBefore(second.endsAt())
                        && first.endsAt().isAfter(second.startsAt())) {
                    conflicts.put(first.key(), true);
                    conflicts.put(second.key(), true);
                }
            }
        }
        return conflicts;
    }

    private CalendarDtos.EventSummary summary(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            CalendarRepository.EventRow row,
            boolean conflict,
            String locale,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt) {
        String organizer = isOrganizer(row, userId, personPublicId)
                ? (korean(locale) ? "나" : "You") : row.organizerName();
        return new CalendarDtos.EventSummary(
                row.eventId(), row.calendarId(), row.calendarName(), row.calendarColor(),
                row.organizerPersonPublicId() == null ? row.organizerUserId() : null,
                row.organizerPersonPublicId(), organizer, row.organizerEmail(), row.title(),
                row.description(), row.type(), startsAt, endsAt, row.timeZone(), row.allDay(),
                row.location(), row.conferenceUrl(), row.status(), row.visibility(),
                row.recurrence(), row.recurrenceInterval(), row.recurrenceUntil(),
                row.responseRequired(), row.myResponse(),
                repository.attendees(tenantId, row.eventId()).stream()
                        .map(attendee -> new CalendarDtos.Attendee(
                                attendee.personPublicId() == null ? attendee.userId() : null,
                                attendee.personPublicId(), attendee.email(), attendee.name(),
                                attendee.type(), attendee.response()))
                        .toList(),
                row.resource() == null ? null : resource(row.resource()), conflict, row.version());
    }

    private int minutes(List<CalendarDtos.EventSummary> events, EventType type) {
        return events.stream().filter(event -> event.type() == type)
                .mapToInt(event -> (int) Duration.between(
                        event.startsAt(), event.endsAt()).toMinutes())
                .sum();
    }

    private CalendarDtos.ResourceSummary resource(CalendarRepository.ResourceRow value) {
        return new CalendarDtos.ResourceSummary(
                value.resourceId(), value.code(), value.name(), value.nameKo(), value.nameEn(),
                value.type(), value.site(), value.floor(), value.capacity(), value.features(),
                value.timeZone(), value.approvalRequired(),
                value.state(), value.available(), value.version());
    }

    private ZoneId zone(String value) {
        return ZoneId.of(value == null || value.isBlank() ? "Asia/Seoul" : value);
    }

    private boolean korean(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ko");
    }

    private record Occurrence(
            CalendarRepository.EventRow row,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt) {
        OccurrenceKey key() {
            return new OccurrenceKey(row.eventId(), startsAt);
        }
    }

    private record OccurrenceKey(UUID eventId, OffsetDateTime startsAt) {
    }
}
