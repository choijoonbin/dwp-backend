package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.EventStatus;

@Service
class CompanyCalendarAdminService {

    private static final Duration MAX_QUERY_SPAN = Duration.ofDays(370);

    private final CompanyCalendarAdminRepository company;
    private final CalendarRepository calendar;
    private final CalendarService calendarService;
    private final CalendarRetentionRepository retention;

    CompanyCalendarAdminService(
            CompanyCalendarAdminRepository company,
            CalendarRepository calendar,
            CalendarService calendarService,
            CalendarRetentionRepository retention) {
        this.company = company;
        this.calendar = calendar;
        this.calendarService = calendarService;
        this.retention = retention;
    }

    @Transactional(readOnly = true)
    List<CalendarDtos.CompanyCalendarSummary> calendars(Long tenantId, String locale) {
        requireTenant(tenantId);
        return company.calendars(tenantId, korean(locale)).stream()
                .map(value -> new CalendarDtos.CompanyCalendarSummary(
                        value.calendarId(), value.key(), value.name(), value.nameKo(), value.nameEn(),
                        value.color(),
                        value.upcomingEventCount(), value.trashedEventCount(), value.version()))
                .toList();
    }

    @Transactional
    CalendarDtos.CompanyCalendarSummary createCalendar(
            Long tenantId, Long actorId, UUID actorPersonPublicId,
            String locale, String correlationId,
            CalendarDtos.CompanyCalendarRequest request) {
        requireActor(tenantId, actorId, actorPersonPublicId);
        if (request.version() != 0) {
            throw conflict("A new company calendar must start at version 0.");
        }
        try {
            UUID calendarId = company.insertCalendar(tenantId, actorId, request);
            calendar.audit(tenantId, actorId, null, "calendar.company.created", correlationId,
                    Map.of(), Map.of(
                            "calendarId", calendarId,
                            "key", request.key().trim(),
                            "subscriptionPolicy", "REQUIRED"));
            return company.calendars(tenantId, korean(locale)).stream()
                    .filter(value -> value.calendarId().equals(calendarId))
                    .findFirst()
                    .map(value -> new CalendarDtos.CompanyCalendarSummary(
                            value.calendarId(), value.key(), value.name(), value.nameKo(), value.nameEn(),
                            value.color(),
                            value.upcomingEventCount(), value.trashedEventCount(), value.version()))
                    .orElseThrow(this::notFound);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("The company calendar key is already in use.");
        }
    }

    @Transactional
    CalendarDtos.CompanyCalendarSummary updateCalendar(
            Long tenantId, Long actorId, UUID actorPersonPublicId,
            UUID calendarId, String locale, String correlationId,
            CalendarDtos.CompanyCalendarRequest request) {
        requireActor(tenantId, actorId, actorPersonPublicId);
        CompanyCalendarAdminRepository.CompanyCalendarState before =
                requireCalendar(tenantId, calendarId);
        if (before.version() != request.version()) {
            throw conflict("The company calendar changed. Refresh and try again.");
        }
        try {
            if (company.updateCalendar(tenantId, actorId, calendarId, request) == 0) {
                throw conflict("The company calendar changed. Refresh and try again.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("The company calendar key is already in use.");
        }
        calendar.audit(tenantId, actorId, null, "calendar.company.updated", correlationId,
                calendarSnapshot(before), Map.of(
                        "calendarId", calendarId,
                        "key", request.key().trim(),
                        "version", request.version() + 1));
        return company.calendars(tenantId, korean(locale)).stream()
                .filter(value -> value.calendarId().equals(calendarId))
                .findFirst()
                .map(value -> new CalendarDtos.CompanyCalendarSummary(
                        value.calendarId(), value.key(), value.name(), value.nameKo(), value.nameEn(),
                        value.color(),
                        value.upcomingEventCount(), value.trashedEventCount(), value.version()))
                .orElseThrow(this::notFound);
    }

    @Transactional(readOnly = true)
    List<CalendarDtos.CompanyEventSummary> events(
            Long tenantId, UUID calendarId, OffsetDateTime from,
            OffsetDateTime to, boolean deleted) {
        requireTenant(tenantId);
        validateRange(from, to);
        requireReadableCalendar(tenantId, calendarId);
        return company.events(tenantId, calendarId, from, to, deleted).stream()
                .map(row -> event(tenantId, row))
                .toList();
    }

    @Transactional
    CalendarDtos.CompanyEventSummary createEvent(
            Long tenantId, Long actorId, UUID actorPersonPublicId,
            String organizerName, UUID calendarId, String correlationId,
            CalendarDtos.CreateEventRequest request) {
        requireActor(tenantId, actorId, actorPersonPublicId);
        requireCalendar(tenantId, calendarId);
        CalendarDtos.CreateEventRequest command = companyCommand(calendarId, request);
        validateEventCommand(tenantId, command);
        String fingerprint = CalendarRequestFingerprint.create(command);
        calendar.lockEventIdempotency(tenantId, actorId, command.idempotencyKey());
        CalendarRepository.IdempotencyRow existing = calendar.eventIdempotency(
                tenantId, actorId, command.idempotencyKey()).orElse(null);
        if (existing != null) {
            if (!fingerprint.equals(existing.requestFingerprint())) {
                throw conflict("The idempotency key was used with different company event data.");
            }
            CompanyCalendarAdminRepository.CompanyEventRow row = company
                    .lockEvent(tenantId, calendarId, existing.eventId())
                    .orElseThrow(() -> conflict(
                            "The idempotency key belongs to another calendar."));
            return event(tenantId, row);
        }
        UUID eventId = calendar.insertEvent(
                tenantId, actorId, actorPersonPublicId,
                organizerName, calendarId, fingerprint, command);
        calendar.audit(tenantId, actorId, eventId,
                "calendar.company.event.published", correlationId,
                Map.of(), Map.of(
                        "calendarId", calendarId,
                        "title", command.title(),
                        "startsAt", command.startsAt(),
                        "endsAt", command.endsAt()));
        return event(tenantId, requireEvent(tenantId, calendarId, eventId));
    }

    @Transactional
    CalendarDtos.CompanyEventSummary updateEvent(
            Long tenantId, Long actorId, UUID actorPersonPublicId,
            UUID calendarId, UUID eventId, String correlationId,
            CalendarDtos.UpdateEventRequest request) {
        requireActor(tenantId, actorId, actorPersonPublicId);
        requireCalendar(tenantId, calendarId);
        CompanyCalendarAdminRepository.CompanyEventRow before =
                requireEvent(tenantId, calendarId, eventId);
        if (before.deletedAt() != null || before.status() == EventStatus.CANCELLED) {
            throw conflict("The company event is not editable.");
        }
        if (before.version() != request.version()) {
            throw conflict("The company event changed. Refresh and try again.");
        }
        validateEventCommand(tenantId, request);
        if (before.hasResource()) {
            throw invalid("Resource-backed events must be managed from the booking workflow.");
        }
        if (company.updateEvent(tenantId, actorId, calendarId, eventId, request) == 0) {
            throw conflict("The company event changed. Refresh and try again.");
        }
        calendar.replaceAttendees(tenantId, eventId, request.attendees());
        calendar.audit(tenantId, actorId, eventId,
                "calendar.company.event.updated", correlationId,
                eventSnapshot(before), Map.of(
                        "calendarId", calendarId,
                        "title", request.title(),
                        "startsAt", request.startsAt(),
                        "endsAt", request.endsAt(),
                        "version", request.version() + 1));
        return event(tenantId, requireEvent(tenantId, calendarId, eventId));
    }

    @Transactional
    CalendarDtos.CompanyEventSummary trashEvent(
            Long tenantId, Long actorId, UUID actorPersonPublicId,
            UUID calendarId, UUID eventId, String correlationId,
            CalendarDtos.TrashEventRequest request) {
        requireActor(tenantId, actorId, actorPersonPublicId);
        requireCalendar(tenantId, calendarId);
        CompanyCalendarAdminRepository.CompanyEventRow before =
                requireEvent(tenantId, calendarId, eventId);
        if (before.deletedAt() != null || before.status() == EventStatus.CANCELLED) {
            throw conflict("The company event is not available for deletion.");
        }
        if (before.version() != request.version()) {
            throw conflict("The company event changed. Refresh and try again.");
        }
        String reason = request.reason() == null || request.reason().isBlank()
                ? "Company administrator requested deletion" : request.reason().trim();
        if (company.trashEvent(
                tenantId, actorId, calendarId, eventId, reason, request.version()) == 0) {
            throw conflict("The company event changed. Refresh and try again.");
        }
        retention.recordTombstone(tenantId, eventId);
        calendar.cancelBookings(tenantId, actorId, eventId);
        CompanyCalendarAdminRepository.CompanyEventRow saved =
                requireEvent(tenantId, calendarId, eventId);
        calendar.audit(tenantId, actorId, eventId,
                "calendar.company.event.trashed", correlationId,
                eventSnapshot(before), eventSnapshot(saved));
        return event(tenantId, saved);
    }

    @Transactional
    CalendarDtos.CompanyEventSummary restoreEvent(
            Long tenantId, Long actorId, UUID actorPersonPublicId,
            UUID calendarId, UUID eventId, String correlationId,
            CalendarDtos.VersionRequest request) {
        requireActor(tenantId, actorId, actorPersonPublicId);
        requireCalendar(tenantId, calendarId);
        CompanyCalendarAdminRepository.CompanyEventRow before =
                requireEvent(tenantId, calendarId, eventId);
        if (before.deletedAt() == null || before.version() != request.version()) {
            throw conflict("The company event is not available for restoration.");
        }
        if (company.restoreEvent(
                tenantId, actorId, calendarId, eventId, request.version()) == 0) {
            throw conflict("The retention window expired or the company event changed.");
        }
        retention.removeTombstone(tenantId, eventId);
        CompanyCalendarAdminRepository.CompanyEventRow saved =
                requireEvent(tenantId, calendarId, eventId);
        calendar.audit(tenantId, actorId, eventId,
                "calendar.company.event.restored", correlationId,
                eventSnapshot(before), eventSnapshot(saved));
        return event(tenantId, saved);
    }

    private CalendarDtos.CompanyEventSummary event(
            Long tenantId, CompanyCalendarAdminRepository.CompanyEventRow row) {
        boolean deleted = row.deletedAt() != null;
        boolean restorable = deleted && (row.legalHold()
                || (row.purgeAfter() != null && row.purgeAfter().isAfter(OffsetDateTime.now())));
        List<CalendarDtos.Attendee> attendees = calendar.attendees(tenantId, row.eventId()).stream()
                .map(value -> new CalendarDtos.Attendee(
                        value.userId(), value.personPublicId(), value.email(), value.name(),
                        value.type(), value.response()))
                .toList();
        return new CalendarDtos.CompanyEventSummary(
                row.eventId(), row.calendarId(), row.title(), row.description(), row.type(),
                row.startsAt(), row.endsAt(), row.timeZone(), row.allDay(), row.location(),
                row.conferenceUrl(), row.status(), row.visibility(), row.recurrence(),
                row.recurrenceInterval(), row.recurrenceUntil(), row.responseRequired(),
                attendees, row.importance(), row.deletedAt(), row.purgeAfter(), row.legalHold(),
                new CalendarDtos.EventCapabilities(
                        true, !deleted && row.status() != EventStatus.CANCELLED,
                        !deleted && row.status() != EventStatus.CANCELLED,
                        restorable, false, false), row.version());
    }

    private CalendarDtos.CreateEventRequest companyCommand(
            UUID calendarId, CalendarDtos.CreateEventRequest request) {
        if (request.calendarId() != null && !request.calendarId().equals(calendarId)) {
            throw invalid("The company calendar does not match the request path.");
        }
        return new CalendarDtos.CreateEventRequest(
                request.title(), request.description(), request.type(), request.startsAt(),
                request.endsAt(), request.timeZone(), request.allDay(), request.location(),
                request.conferenceUrl(), request.visibility(), request.recurrence(),
                request.recurrenceInterval(), request.recurrenceUntil(),
                request.responseRequired(), request.attendees(), request.resourceId(),
                request.idempotencyKey(), calendarId, request.importance());
    }

    private void validateEventCommand(
            Long tenantId, CalendarDtos.CreateEventRequest request) {
        if (request.resourceId() != null) {
            throw invalid("Company events cannot reserve a resource from the publishing API.");
        }
        calendarService.validateEvent(
                tenantId, request.startsAt(), request.endsAt(), request.timeZone(),
                request.type(), request.description(), request.recurrence(),
                request.recurrenceUntil(), request.attendees());
    }

    private void validateEventCommand(
            Long tenantId, CalendarDtos.UpdateEventRequest request) {
        if (request.resourceId() != null) {
            throw invalid("Company events cannot reserve a resource from the publishing API.");
        }
        calendarService.validateEvent(
                tenantId, request.startsAt(), request.endsAt(), request.timeZone(),
                request.type(), request.description(), request.recurrence(),
                request.recurrenceUntil(), request.attendees());
    }

    private CompanyCalendarAdminRepository.CompanyCalendarState requireCalendar(
            Long tenantId, UUID calendarId) {
        return company.lockCalendar(tenantId, calendarId).orElseThrow(this::notFound);
    }

    private CompanyCalendarAdminRepository.CompanyCalendarState requireReadableCalendar(
            Long tenantId, UUID calendarId) {
        return company.calendar(tenantId, calendarId).orElseThrow(this::notFound);
    }

    private CompanyCalendarAdminRepository.CompanyEventRow requireEvent(
            Long tenantId, UUID calendarId, UUID eventId) {
        return company.lockEvent(tenantId, calendarId, eventId).orElseThrow(this::notFound);
    }

    private void requireActor(Long tenantId, Long actorId, UUID actorPersonPublicId) {
        requireTenant(tenantId);
        if (actorId == null || actorId < 1 || actorPersonPublicId == null) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        calendar.linkIdentity(tenantId, actorId, actorPersonPublicId);
    }

    private void requireTenant(Long tenantId) {
        if (tenantId == null || tenantId < 1) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
    }

    private void validateRange(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null || !to.isAfter(from)
                || Duration.between(from, to).compareTo(MAX_QUERY_SPAN) > 0) {
            throw invalid("A valid company calendar range of at most 370 days is required.");
        }
    }

    private Map<String, Object> calendarSnapshot(
            CompanyCalendarAdminRepository.CompanyCalendarState value) {
        return Map.of(
                "calendarId", value.calendarId(),
                "key", value.key(),
                "nameKo", value.nameKo(),
                "nameEn", value.nameEn(),
                "color", value.color(),
                "version", value.version());
    }

    private Map<String, Object> eventSnapshot(
            CompanyCalendarAdminRepository.CompanyEventRow value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("calendarId", value.calendarId());
        result.put("eventId", value.eventId());
        result.put("title", value.title());
        result.put("startsAt", value.startsAt());
        result.put("endsAt", value.endsAt());
        result.put("status", value.status().name());
        result.put("deletedAt", value.deletedAt());
        result.put("purgeAfter", value.purgeAfter());
        result.put("legalHold", value.legalHold());
        result.put("version", value.version());
        return result;
    }

    private boolean korean(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ko");
    }

    private BaseException notFound() {
        return new BaseException(ErrorCode.NOT_FOUND);
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
