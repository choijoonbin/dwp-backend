package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.dwp.services.platform.calendar.CalendarTypes.ResourceState;
import static com.dwp.services.platform.calendar.CalendarTypes.ResourceType;

@Service
public class RoomService {

    private static final Duration MAX_AVAILABILITY_SPAN = Duration.ofDays(31);

    private final CalendarService calendarService;
    private final CalendarRepository calendarRepository;
    private final RoomRepository roomRepository;
    private final RoomBookingPolicyService roomBookingPolicy;

    public RoomService(
            CalendarService calendarService,
            CalendarRepository calendarRepository,
            RoomRepository roomRepository,
            RoomBookingPolicyService roomBookingPolicy) {
        this.calendarService = calendarService;
        this.calendarRepository = calendarRepository;
        this.roomRepository = roomRepository;
        this.roomBookingPolicy = roomBookingPolicy;
    }

    @Transactional(readOnly = true)
    public CalendarDtos.RoomAvailabilityResponse roomAvailability(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            OffsetDateTime from,
            OffsetDateTime to,
            UUID excludeEventId,
            String locale) {
        validateAvailabilityRange(from, to);
        UUID verifiedExcludeEventId = verifiedExcludedEvent(
                tenantId, userId, personPublicId, verifiedGroupRefs, excludeEventId, locale);
        CalendarDtos.Policy policy = calendarService.policy(tenantId);
        List<CalendarDtos.ResourceSummary> rooms = calendarService
                .resources(tenantId, userId, verifiedGroupRefs, from, to, locale).stream()
                .filter(value -> value.type() == ResourceType.ROOM)
                .toList();
        Set<UUID> roomIds = rooms.stream()
                .map(CalendarDtos.ResourceSummary::resourceId)
                .collect(Collectors.toUnmodifiableSet());
        List<RoomRepository.ResourceOccupancyRow> bufferedOccupancy = roomRepository
                .resourceOccupancy(
                        tenantId,
                        from.minusMinutes(policy.defaultBufferMinutes()),
                        to.plusMinutes(policy.defaultBufferMinutes()),
                        verifiedExcludeEventId).stream()
                .filter(value -> roomIds.contains(value.resourceId()))
                .toList();
        Set<UUID> conflictingRoomIds = bufferedOccupancy.stream()
                .map(RoomRepository.ResourceOccupancyRow::resourceId)
                .collect(Collectors.toUnmodifiableSet());
        List<CalendarDtos.ResourceOccupancy> occupancy = bufferedOccupancy.stream()
                .filter(value -> value.startsAt().isBefore(to) && value.endsAt().isAfter(from))
                .map(value -> new CalendarDtos.ResourceOccupancy(
                        value.resourceId(), value.startsAt(), value.endsAt(), value.bookingStatus()))
                .toList();
        Set<UUID> occupiedRoomIds = occupancy.stream()
                .map(CalendarDtos.ResourceOccupancy::resourceId)
                .collect(Collectors.toUnmodifiableSet());
        List<CalendarDtos.ResourceSummary> evaluatedRooms = rooms.stream()
                .map(value -> availabilityRoom(value, !occupiedRoomIds.contains(value.resourceId())))
                .toList();
        OffsetDateTime evaluatedAt = OffsetDateTime.now();
        List<CalendarDtos.RoomBookingEligibility> bookingEligibility = evaluatedRooms.stream()
                .map(value -> roomBookingPolicy.evaluateAvailability(
                        value,
                        policy,
                        from,
                        to,
                        verifiedExcludeEventId,
                        evaluatedAt,
                        conflictingRoomIds.contains(value.resourceId())))
                .toList();
        return new CalendarDtos.RoomAvailabilityResponse(
                evaluatedRooms, occupancy, bookingEligibility, evaluatedAt);
    }

    @Transactional(readOnly = true)
    public List<CalendarDtos.EventSummary> roomBookings(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            OffsetDateTime from,
            OffsetDateTime to,
            String locale) {
        return calendarService.events(
                        tenantId, userId, personPublicId,
                        verifiedGroupRefs, from, to, locale).stream()
                .filter(event -> event.resource() != null
                        && event.resource().type() == ResourceType.ROOM)
                .toList();
    }

    @Transactional
    public CalendarDtos.EventSummary createRoomBooking(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String organizerName,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            CalendarDtos.CreateEventRequest request) {
        requireRoomResource(tenantId, request.resourceId(), locale);
        return calendarService.create(
                tenantId, userId, personPublicId, organizerName,
                locale, correlationId, verifiedGroupRefs, request);
    }

    @Transactional
    public CalendarDtos.EventSummary updateRoomBooking(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID eventId,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            CalendarDtos.UpdateEventRequest request) {
        requireRoomBooking(
                tenantId, userId, personPublicId, verifiedGroupRefs, eventId, locale);
        requireRoomResource(tenantId, request.resourceId(), locale);
        return calendarService.update(
                tenantId, userId, personPublicId, eventId,
                locale, correlationId, verifiedGroupRefs, request);
    }

    @Transactional
    public void cancelRoomBooking(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID eventId,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            CalendarDtos.VersionRequest request) {
        requireRoomBooking(
                tenantId, userId, personPublicId, verifiedGroupRefs, eventId, locale);
        calendarService.cancel(
                tenantId, userId, personPublicId, eventId,
                locale, correlationId, verifiedGroupRefs, request);
    }

    @Transactional
    public CalendarDtos.EventSummary respondRoomBooking(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID eventId,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            CalendarDtos.RespondRequest request) {
        requireRoomBooking(
                tenantId, userId, personPublicId, verifiedGroupRefs, eventId, locale);
        return calendarService.respond(
                tenantId, userId, personPublicId, eventId,
                locale, correlationId, verifiedGroupRefs, request);
    }

    @Transactional(readOnly = true)
    public CalendarDtos.AdminOverview adminOverview(Long tenantId, String locale) {
        CalendarDtos.AdminOverview shared = calendarService.adminOverview(tenantId, locale);
        List<CalendarDtos.ResourceSummary> rooms = shared.resources().stream()
                .filter(value -> value.type() == ResourceType.ROOM)
                .toList();
        Set<UUID> roomIds = rooms.stream()
                .map(CalendarDtos.ResourceSummary::resourceId)
                .collect(Collectors.toUnmodifiableSet());
        ZoneId zone = ZoneId.of("Asia/Seoul");
        LocalDate week = LocalDate.now(zone)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        OffsetDateTime from = week.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime to = from.plusDays(7);
        long bookingsThisWeek = roomRepository.resourceOccupancy(tenantId, from, to).stream()
                .filter(value -> roomIds.contains(value.resourceId()))
                .count();
        long pending = pendingBookings(tenantId, locale).size();
        return new CalendarDtos.AdminOverview(
                rooms.stream().filter(value -> value.state() == ResourceState.AVAILABLE).count(),
                rooms.stream().filter(value -> value.state() == ResourceState.MAINTENANCE).count(),
                bookingsThisWeek,
                pending,
                shared.eventsThisWeek(),
                shared.conflictedUsers(),
                shared.policy(),
                rooms,
                OffsetDateTime.now());
    }

    @Transactional(readOnly = true)
    public CalendarDtos.Policy policy(Long tenantId) {
        return calendarService.policy(tenantId);
    }

    @Transactional(readOnly = true)
    public List<CalendarDtos.BookingSummary> pendingBookings(Long tenantId, String locale) {
        OffsetDateTime now = OffsetDateTime.now();
        Set<UUID> roomIds = calendarRepository
                .resources(tenantId, now, now.plusMinutes(1), korean(locale), true).stream()
                .filter(value -> value.type() == ResourceType.ROOM)
                .map(CalendarRepository.ResourceRow::resourceId)
                .collect(Collectors.toUnmodifiableSet());
        return calendarRepository.pendingBookings(tenantId, korean(locale)).stream()
                .filter(value -> roomIds.contains(value.resourceId()))
                .map(this::booking)
                .toList();
    }

    @Transactional
    public CalendarDtos.BookingSummary decideBooking(
            Long tenantId,
            Long actorId,
            UUID bookingId,
            String locale,
            String correlationId,
            CalendarDtos.BookingDecisionRequest request) {
        CalendarRepository.BookingRow booking = calendarRepository.booking(
                        tenantId, bookingId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireRoomResource(tenantId, booking.resourceId(), locale);
        return calendarService.decideBooking(
                tenantId, actorId, bookingId, locale, correlationId, request);
    }

    @Transactional
    public CalendarDtos.Policy updatePolicy(
            Long tenantId,
            Long actorId,
            String correlationId,
            CalendarDtos.PolicyRequest request) {
        return calendarService.updatePolicy(tenantId, actorId, correlationId, request);
    }

    @Transactional
    public CalendarDtos.ResourceSummary saveResource(
            Long tenantId,
            Long actorId,
            UUID resourceId,
            String locale,
            String correlationId,
            CalendarDtos.ResourceRequest request) {
        if (request.type() != ResourceType.ROOM) {
            throw invalid("Rooms administration accepts room resources only.");
        }
        return calendarService.saveResource(
                tenantId, actorId, resourceId, locale, correlationId, request);
    }

    private CalendarRepository.ResourceRow requireRoomResource(
            Long tenantId,
            UUID resourceId,
            String locale) {
        if (resourceId == null) {
            throw invalid("A meeting room is required.");
        }
        CalendarRepository.ResourceRow resource = calendarRepository.resource(
                        tenantId, resourceId, korean(locale))
                .orElseThrow(() -> new BaseException(
                        ErrorCode.NOT_FOUND, "The meeting room was not found."));
        if (resource.type() != ResourceType.ROOM) {
            throw invalid("The selected resource is not a meeting room.");
        }
        return resource;
    }

    private CalendarRepository.EventRow requireRoomBooking(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            UUID eventId,
            String locale) {
        CalendarRepository.EventRow event = CalendarRepositoryRouting.event(
                        calendarRepository, tenantId, userId, personPublicId,
                        verifiedGroupRefs, eventId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (event.resource() == null || event.resource().type() != ResourceType.ROOM) {
            throw new BaseException(ErrorCode.NOT_FOUND, "The room booking was not found.");
        }
        return event;
    }

    private UUID verifiedExcludedEvent(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            UUID excludeEventId,
            String locale) {
        if (excludeEventId == null) return null;
        CalendarRepository.EventRow event = requireRoomBooking(
                tenantId, userId, personPublicId, verifiedGroupRefs, excludeEventId, locale);
        if (!CalendarAccessPolicy.canEdit(event)) {
            throw new BaseException(ErrorCode.FORBIDDEN, "This room booking is read-only.");
        }
        return event.eventId();
    }

    private CalendarDtos.ResourceSummary availabilityRoom(
            CalendarDtos.ResourceSummary value,
            boolean physicallyOpen) {
        return new CalendarDtos.ResourceSummary(
                value.resourceId(), value.code(), value.name(), value.nameKo(), value.nameEn(),
                value.type(), value.site(), value.floor(), value.capacity(), value.features(),
                value.timeZone(), value.approvalRequired(), value.state(),
                physicallyOpen && value.state() == ResourceState.AVAILABLE,
                value.version());
    }

    private CalendarDtos.BookingSummary booking(CalendarRepository.BookingRow value) {
        return new CalendarDtos.BookingSummary(
                value.bookingId(), value.eventId(), value.resourceId(), value.resourceName(),
                value.eventTitle(), value.startsAt(), value.endsAt(), value.organizerName(),
                value.organizerEmail(), value.status(), value.requestedBy(), value.decisionNote(),
                value.decidedAt(), value.decidedBy(), value.version());
    }

    private void validateAvailabilityRange(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw invalid("A valid date range is required.");
        }
        if (Duration.between(from, to).compareTo(MAX_AVAILABILITY_SPAN) > 0) {
            throw invalid("Room availability searches are limited to 31 days.");
        }
    }

    private boolean korean(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ko");
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
