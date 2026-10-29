package com.dwp.services.platform.calendar;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.*;

@Component
final class CalendarOccurrenceProjector {

    private static final int MAX_OCCURRENCES = 4000;

    private final CalendarRepository repository;
    private final CalendarOccurrenceRepository occurrenceRepository;
    private final CalendarRecurrenceEngine recurrence = new CalendarRecurrenceEngine();

    CalendarOccurrenceProjector(CalendarRepository repository) {
        this(repository, null);
    }

    @Autowired
    CalendarOccurrenceProjector(
            CalendarRepository repository,
            CalendarOccurrenceRepository occurrenceRepository) {
        this.repository = repository;
        this.occurrenceRepository = occurrenceRepository;
    }

    List<CalendarDtos.EventSummary> summaries(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            OffsetDateTime from,
            OffsetDateTime to,
            String locale) {
        return summaries(
                tenantId, userId, personPublicId, (String) null, from, to, locale);
    }

    List<CalendarDtos.EventSummary> summaries(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            OffsetDateTime from,
            OffsetDateTime to,
            String locale) {
        List<CalendarRepository.EventRow> rows = CalendarRepositoryRouting.visibleEvents(
                repository, tenantId, userId, personPublicId,
                verifiedGroupRefs, from, to, korean(locale));
        return summariesForRows(
                tenantId, userId, personPublicId, rows, from, to, locale);
    }

    List<CalendarDtos.EventSummary> summariesForRows(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            List<CalendarRepository.EventRow> rows,
            OffsetDateTime from,
            OffsetDateTime to,
            String locale) {
        return summariesForRows(
                tenantId, userId, personPublicId, rows, from, to, locale,
                Integer.MAX_VALUE);
    }

    List<CalendarDtos.EventSummary> summariesForRows(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            List<CalendarRepository.EventRow> rows,
            OffsetDateTime from,
            OffsetDateTime to,
            String locale,
            int projectionBudget) {
        if (projectionBudget < 1) {
            throw new IllegalArgumentException("Calendar projection budget must be positive.");
        }
        List<CalendarOccurrenceRepository.OverrideRow> overrides = occurrenceRepository == null
                ? List.of()
                : occurrenceRepository.overrides(
                        tenantId,
                        rows.stream().map(CalendarRepository.EventRow::eventId).distinct().toList(),
                        from,
                        to,
                        MAX_OCCURRENCES + 1);
        if (overrides.size() > MAX_OCCURRENCES) {
            throw new IllegalStateException("Calendar occurrence projection exceeds its limit.");
        }
        List<Occurrence> occurrences = occurrences(
                rows, overrides, from, to, projectionBudget);
        Map<OccurrenceKey, Boolean> conflicts = conflictMap(occurrences);
        Map<UUID, List<CalendarRepository.AttendeeRow>> attendees = repository.attendeesByEvents(
                tenantId,
                rows.stream()
                        .filter(row -> row.detailLevel() == EventDetailLevel.FULL)
                        .filter(row -> row.accessLevel() == CalendarAccessLevel.OWNER
                                || row.accessLevel() == CalendarAccessLevel.MANAGE
                                || row.accessLevel() == CalendarAccessLevel.EDIT
                                || row.accessLevel() == CalendarAccessLevel.EVENT_ATTENDEE)
                        .map(CalendarRepository.EventRow::eventId)
                        .distinct()
                        .toList());
        if (attendees == null) attendees = Map.of();
        Map<UUID, List<CalendarRepository.AttendeeRow>> projectedAttendees = attendees;
        return occurrences.stream()
                .map(occurrence -> summary(
                        tenantId, userId, personPublicId, occurrence.row(),
                        conflicts.getOrDefault(occurrence.key(), false), locale,
                        occurrence.startsAt(), occurrence.endsAt(),
                        occurrence.originalStartsAt(), occurrence.override(), projectedAttendees))
                .sorted(Comparator.comparing(CalendarDtos.EventSummary::startsAt)
                        .thenComparing(CalendarDtos.EventSummary::eventId)
                        .thenComparing(value -> value.recurrenceId() == null
                                ? value.startsAt() : value.recurrenceId()))
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
                row.startsAt(), row.endsAt(), null, null, null);
    }

    CalendarDtos.EventSummary summaryForOccurrence(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            CalendarRepository.EventRow row,
            String locale,
            OffsetDateTime originalStartsAt,
            CalendarOccurrenceRepository.OverrideRow override) {
        return summary(
                tenantId, userId, personPublicId, row, false, locale,
                override.startsAt(), override.endsAt(), originalStartsAt, override, null);
    }

    boolean isOrganizer(
            CalendarRepository.EventRow row, Long userId, UUID personPublicId) {
        if (row.organizerPersonPublicId() != null) {
            return row.organizerPersonPublicId().equals(personPublicId);
        }
        return row.organizerUserId() != null && row.organizerUserId().equals(userId);
    }

    List<CalendarDtos.AttentionItem> attention(
            List<CalendarDtos.EventSummary> events,
            CalendarRepository.PolicyRow policy,
            String locale, int focus) {
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
            List<CalendarOccurrenceRepository.OverrideRow> overrides,
            OffsetDateTime from,
            OffsetDateTime to,
            int projectionBudget) {
        List<Occurrence> result = new ArrayList<>();
        Map<OccurrenceKey, CalendarOccurrenceRepository.OverrideRow> overridesByOccurrence =
                new HashMap<>();
        Map<UUID, List<CalendarOccurrenceRepository.OverrideRow>> overridesByEvent =
                new HashMap<>();
        overrides.forEach(override -> {
            overridesByOccurrence.put(
                    new OccurrenceKey(override.eventId(), override.originalStartsAt().toInstant()),
                    override);
            overridesByEvent.computeIfAbsent(override.eventId(), ignored -> new ArrayList<>())
                    .add(override);
        });
        for (CalendarRepository.EventRow row : rows) {
            Map<Instant, Boolean> handled = new HashMap<>();
            CalendarRecurrenceEngine.Rule rule = recurrence.rule(
                    row.recurrence(), row.recurrenceInterval(), row.recurrenceUntil(),
                    row.recurrenceCount(), Set.copyOf(row.recurrenceDays()), row.timeZone());
            for (CalendarRecurrenceEngine.Projection projection : recurrence.project(
                    row.startsAt(), row.endsAt(), rule, from, to, MAX_OCCURRENCES)) {
                addOccurrence(
                        result, handled, row, projection.startsAt(), projection.startsAt(),
                        projection.endsAt(),
                        overridesByOccurrence.get(new OccurrenceKey(
                                row.eventId(), projection.startsAt().toInstant())),
                        from, to, projectionBudget);
            }
            overridesByEvent.getOrDefault(row.eventId(), List.of()).stream()
                    .filter(override -> !handled.containsKey(override.originalStartsAt().toInstant()))
                    .forEach(override -> addOccurrence(
                            result, handled, row, override.originalStartsAt(),
                            override.originalStartsAt(), override.originalStartsAt(),
                            override, from, to, projectionBudget));
        }
        return result;
    }

    private void addOccurrence(
            List<Occurrence> result,
            Map<Instant, Boolean> handled,
            CalendarRepository.EventRow row,
            OffsetDateTime originalStartsAt,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            CalendarOccurrenceRepository.OverrideRow override,
            OffsetDateTime from,
            OffsetDateTime to,
            int projectionBudget) {
        handled.put(originalStartsAt.toInstant(), true);
        if (override != null) {
            if (!"MODIFIED".equals(override.kind())
                    || override.startsAt() == null
                    || override.endsAt() == null) return;
            startsAt = override.startsAt();
            endsAt = override.endsAt();
        }
        if (startsAt.isBefore(to) && endsAt.isAfter(from)) {
            if (result.size() >= projectionBudget) {
                throw new ProjectionBudgetExceededException(projectionBudget);
            }
            result.add(new Occurrence(row, originalStartsAt, startsAt, endsAt, override));
        }
    }

    static final class ProjectionBudgetExceededException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        ProjectionBudgetExceededException(int budget) {
            super("Calendar occurrence projection exceeds its budget of " + budget + ".");
        }
    }

    OffsetDateTime increment(
            OffsetDateTime value, RecurrencePattern pattern, int interval, String timeZone) {
        return recurrence.next(value, value, recurrence.rule(
                pattern, interval, null, null, Set.of(), timeZone));
    }

    private Map<OccurrenceKey, Boolean> conflictMap(List<Occurrence> values) {
        Set<OccurrenceKey> detected = CalendarConflictSweep.conflicts(values.stream()
                .map(value -> new CalendarConflictSweep.Interval<>(
                        value.key(), value.row().eventId(), value.startsAt(), value.endsAt(),
                        conflictEligible(value.row())))
                .toList()).conflicts();
        Map<OccurrenceKey, Boolean> conflicts = new HashMap<>();
        detected.forEach(key -> conflicts.put(key, true));
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
            OffsetDateTime endsAt,
            OffsetDateTime originalStartsAt,
            CalendarOccurrenceRepository.OverrideRow override,
            Map<UUID, List<CalendarRepository.AttendeeRow>> prefetchedAttendees) {
        String organizer = isOrganizer(row, userId, personPublicId)
                ? (korean(locale) ? "나" : "You") : row.organizerName();
        boolean redacted = row.detailLevel() == EventDetailLevel.FREE_BUSY;
        CalendarDtos.EventCapabilities capabilities = capabilities(row, redacted);
        if (redacted) {
            return new CalendarDtos.EventSummary(
                    redactedEventId(tenantId, userId, personPublicId, row.eventId()),
                    row.calendarId(), row.calendarName(), row.calendarColor(), null, null,
                    null, null, korean(locale) ? "바쁨" : "Busy", null,
                    EventType.MEETING, startsAt, endsAt, row.timeZone(), row.allDay(),
                    null, null, EventStatus.CONFIRMED, EventVisibility.DEFAULT,
                    RecurrencePattern.NONE, 1, null, false, null, List.of(), null,
                    false, EventImportance.NORMAL, EventDetailLevel.FREE_BUSY, true,
                    false, 0, capabilities, "FREE_BUSY_ONLY", null, row.version());
        }
        boolean completeOverride = override != null && override.type() != null;
        String title = completeOverride ? override.title() : row.title();
        String description = completeOverride ? override.description() : row.description();
        EventType type = completeOverride ? override.type() : row.type();
        boolean allDay = completeOverride && override.allDay() != null
                ? override.allDay() : row.allDay();
        String location = completeOverride ? override.location() : row.location();
        String conferenceUrl = completeOverride ? override.conferenceUrl() : row.conferenceUrl();
        EventVisibility visibility = completeOverride && override.visibility() != null
                ? override.visibility() : row.visibility();
        boolean responseRequired = completeOverride && override.responseRequired() != null
                ? override.responseRequired() : row.responseRequired();
        EventImportance importance = override != null && override.importance() != null
                ? override.importance() : row.importance();
        return new CalendarDtos.EventSummary(
                row.eventId(), row.calendarId(), row.calendarName(), row.calendarColor(),
                row.organizerPersonPublicId() == null ? row.organizerUserId() : null,
                row.organizerPersonPublicId(), organizer, row.organizerEmail(), title,
                description, type, startsAt, endsAt, row.timeZone(), allDay,
                location, conferenceUrl, row.status(), visibility,
                row.recurrence(), row.recurrenceInterval(), row.recurrenceUntil(),
                responseRequired, row.myResponse(),
                attendees(tenantId, row, prefetchedAttendees),
                row.resource() == null ? null : resource(row.resource()), conflict,
                importance, row.detailLevel(), false, row.starred(),
                row.preferenceVersion(), capabilities, null,
                row.recurrence() == RecurrencePattern.NONE ? null : originalStartsAt,
                row.version(), row.recurrenceDays(), row.recurrenceCount());
    }

    private List<CalendarDtos.Attendee> attendees(
            Long tenantId,
            CalendarRepository.EventRow row,
            Map<UUID, List<CalendarRepository.AttendeeRow>> prefetched) {
        if (row.detailLevel() != EventDetailLevel.FULL
                || (row.accessLevel() != CalendarAccessLevel.OWNER
                && row.accessLevel() != CalendarAccessLevel.MANAGE
                && row.accessLevel() != CalendarAccessLevel.EDIT
                && row.accessLevel() != CalendarAccessLevel.EVENT_ATTENDEE)) {
            return List.of();
        }
        List<CalendarRepository.AttendeeRow> values = prefetched == null
                ? repository.attendees(tenantId, row.eventId())
                : prefetched.getOrDefault(row.eventId(), List.of());
        return values.stream()
                .map(attendee -> new CalendarDtos.Attendee(
                        attendee.personPublicId() == null ? attendee.userId() : null,
                        attendee.personPublicId(), attendee.email(), attendee.name(),
                        attendee.type(), attendee.response()))
                .toList();
    }

    private boolean conflictEligible(CalendarRepository.EventRow row) {
        return row.detailLevel() == EventDetailLevel.FULL
                && (row.accessLevel() == CalendarAccessLevel.OWNER
                || row.accessLevel() == CalendarAccessLevel.EVENT_ATTENDEE);
    }

    private CalendarDtos.EventCapabilities capabilities(
            CalendarRepository.EventRow row, boolean redacted) {
        boolean edit = !redacted && (row.accessLevel() == CalendarAccessLevel.OWNER
                || row.accessLevel() == CalendarAccessLevel.MANAGE
                || row.accessLevel() == CalendarAccessLevel.EDIT);
        boolean delete = !redacted && (row.accessLevel() == CalendarAccessLevel.OWNER
                || row.accessLevel() == CalendarAccessLevel.MANAGE);
        return new CalendarDtos.EventCapabilities(
                !redacted, edit, delete, false,
                !redacted && row.myResponse() != null, !redacted);
    }

    private UUID redactedEventId(
            Long tenantId, Long userId, UUID personPublicId, UUID eventId) {
        String viewer = personPublicId == null ? "user:" + userId : "person:" + personPublicId;
        return UUID.nameUUIDFromBytes(("calendar-free-busy:" + tenantId + ":" + viewer
                + ":" + eventId).getBytes(StandardCharsets.UTF_8));
    }

    private CalendarDtos.ResourceSummary resource(CalendarRepository.ResourceRow value) {
        return new CalendarDtos.ResourceSummary(
                value.resourceId(), value.code(), value.name(), value.nameKo(), value.nameEn(),
                value.type(), value.site(), value.floor(), value.capacity(), value.features(),
                value.timeZone(), value.approvalRequired(),
                value.state(), value.available(), value.version());
    }

    private boolean korean(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ko");
    }

    private record Occurrence(
            CalendarRepository.EventRow row,
            OffsetDateTime originalStartsAt,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            CalendarOccurrenceRepository.OverrideRow override) {
        OccurrenceKey key() {
            return new OccurrenceKey(row.eventId(), originalStartsAt.toInstant());
        }
    }

    private record OccurrenceKey(UUID eventId, Instant startsAt) {
    }
}
