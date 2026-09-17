package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Coordinates company-calendar administration commands behind {@link CalendarService}'s
 * established transaction boundary.
 */
final class CalendarAdministrationOperations {

    private final CalendarRepository repository;

    CalendarAdministrationOperations(CalendarRepository repository) {
        this.repository = repository;
    }

    CalendarDtos.AdminOverview adminOverview(Long tenantId, String locale) {
        ZoneId zone = ZoneId.of("Asia/Seoul");
        LocalDate week = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        OffsetDateTime from = week.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime to = from.plusDays(7);
        CalendarRepository.AdminStats stats = repository.adminStats(tenantId, from, to);
        return new CalendarDtos.AdminOverview(
                stats.activeResources(), stats.resourcesInMaintenance(), stats.bookingsThisWeek(),
                stats.pendingBookings(), stats.eventsThisWeek(), stats.conflictedUsers(),
                policy(repository.policy(tenantId)),
                repository.resources(tenantId, from, to, korean(locale), true).stream()
                        .map(this::resource).toList(),
                OffsetDateTime.now());
    }

    CalendarDtos.Policy policy(Long tenantId) {
        return policy(repository.policy(tenantId));
    }

    List<CalendarDtos.BookingSummary> pendingBookings(Long tenantId, String locale) {
        return repository.pendingBookings(tenantId, korean(locale)).stream()
                .map(this::booking)
                .toList();
    }

    CalendarDtos.BookingSummary decideBooking(
            Long tenantId,
            Long actorId,
            UUID bookingId,
            String locale,
            String correlationId,
            CalendarDtos.BookingDecisionRequest request) {
        String status = "APPROVE".equals(request.decision()) ? "CONFIRMED" : "DECLINED";
        CalendarRepository.BookingRow saved = repository.decideBooking(
                tenantId, actorId, bookingId, status, request.note(), request.version(),
                korean(locale));
        if (saved == null) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The booking changed or was already decided. Refresh and try again.");
        }
        repository.audit(tenantId, actorId, saved.eventId(),
                "calendar.booking." + status.toLowerCase(Locale.ROOT), correlationId,
                Map.of("status", "PENDING"), Map.of(
                        "bookingId", bookingId,
                        "status", status,
                        "note", request.note() == null ? "" : request.note()));
        return booking(saved);
    }

    CalendarDtos.Policy updatePolicy(
            Long tenantId,
            Long actorId,
            String correlationId,
            CalendarDtos.PolicyRequest request) {
        if (!request.workingDayEnd().isAfter(request.workingDayStart())) {
            throw invalid("Working hours must end after they start.");
        }
        if (request.minimumEventMinutes() > request.defaultEventMinutes()
                || request.defaultEventMinutes() > request.maximumEventMinutes()) {
            throw invalid("The default duration must be within the minimum and maximum duration.");
        }
        CalendarRepository.PolicyRow before = repository.policy(tenantId);
        if (repository.updatePolicy(tenantId, actorId, request) == 0) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The scheduling policy changed. Refresh and try again.");
        }
        repository.audit(tenantId, actorId, null, "calendar.policy.updated", correlationId,
                Map.of("version", before.version()), Map.of("version", before.version() + 1));
        return policy(repository.policy(tenantId));
    }

    CalendarDtos.ResourceSummary saveResource(
            Long tenantId,
            Long actorId,
            UUID resourceId,
            String locale,
            String correlationId,
            CalendarDtos.ResourceRequest request,
            boolean workplaceWrite) {
        if (resourceId != null
                && !workplaceWrite
                && repository.isWorkplaceManagedResource(tenantId, resourceId)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This room is managed by Workplace. Update it from Workplace locations.");
        }
        CalendarRepository.ResourceRow saved = repository.saveResource(
                tenantId, actorId, resourceId, request, korean(locale));
        if (saved == null) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The resource changed. Refresh and try again.");
        }
        repository.audit(tenantId, actorId, null,
                resourceId == null ? "calendar.resource.created" : "calendar.resource.updated",
                correlationId, Map.of(), Map.of(
                        "resourceId", saved.resourceId(),
                        "code", saved.code(),
                        "state", saved.state().name()));
        return resource(saved);
    }

    private CalendarDtos.ResourceSummary resource(CalendarRepository.ResourceRow value) {
        return new CalendarDtos.ResourceSummary(
                value.resourceId(), value.code(), value.name(), value.nameKo(), value.nameEn(),
                value.type(), value.site(), value.floor(), value.capacity(), value.features(),
                value.timeZone(), value.approvalRequired(),
                value.state(), value.available(), value.version());
    }

    private CalendarDtos.BookingSummary booking(CalendarRepository.BookingRow value) {
        return new CalendarDtos.BookingSummary(
                value.bookingId(), value.eventId(), value.resourceId(), value.resourceName(),
                value.eventTitle(), value.startsAt(), value.endsAt(), value.organizerName(),
                value.organizerEmail(), value.status(), value.requestedBy(), value.decisionNote(),
                value.decidedAt(), value.decidedBy(), value.version());
    }

    private CalendarDtos.Policy policy(CalendarRepository.PolicyRow value) {
        return new CalendarDtos.Policy(
                value.weekStart(), value.workingDayStart(), value.workingDayEnd(),
                value.defaultEventMinutes(), value.minimumEventMinutes(),
                value.maximumEventMinutes(), value.maximumAdvanceDays(),
                value.defaultBufferMinutes(), value.weeklyFocusTargetMinutes(),
                value.dailyMeetingLimitMinutes(), value.enforceMeetingAgenda(),
                value.allowExternalAttendees(), value.version());
    }

    private boolean korean(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ko");
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
