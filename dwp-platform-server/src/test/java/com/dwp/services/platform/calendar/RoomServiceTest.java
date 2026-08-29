package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock
    private CalendarService calendarService;

    @Mock
    private CalendarRepository calendarRepository;

    @Mock
    private RoomRepository roomRepository;

    private RoomService service;

    @BeforeEach
    void setUp() {
        RoomBookingPolicyService roomBookingPolicy = new RoomBookingPolicyService(
                calendarRepository,
                new CalendarSchedulingHorizon(Clock.fixed(
                        Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC)));
        service = new RoomService(
                calendarService, calendarRepository, roomRepository, roomBookingPolicy);
    }

    @Test
    void availabilityReturnsOnlyRoomOccupancyWithoutEventDetails() {
        UUID roomId = UUID.randomUUID();
        UUID equipmentId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.parse("2026-08-19T09:00:00+09:00");
        OffsetDateTime to = from.plusHours(1);
        when(calendarService.resources(
                1L, 7L, "group-refs", from, to, "ko-KR")).thenReturn(List.of(
                resource(roomId, CalendarTypes.ResourceType.ROOM),
                resource(equipmentId, CalendarTypes.ResourceType.EQUIPMENT)));
        when(calendarService.policy(1L)).thenReturn(policy());
        when(roomRepository.resourceOccupancy(
                1L, from.minusMinutes(15), to.plusMinutes(15), null)).thenReturn(List.of(
                new RoomRepository.ResourceOccupancyRow(
                        roomId, from.plusMinutes(30), from.plusMinutes(45), "CONFIRMED"),
                new RoomRepository.ResourceOccupancyRow(
                        equipmentId, from.plusHours(3), from.plusHours(4), "CONFIRMED")));

        CalendarDtos.RoomAvailabilityResponse result = service.roomAvailability(
                1L, 7L, null, "group-refs", from, to, null, "ko-KR");

        assertThat(result.rooms()).singleElement()
                .extracting(CalendarDtos.ResourceSummary::resourceId)
                .isEqualTo(roomId);
        assertThat(result.occupancy()).singleElement()
                .satisfies(slot -> {
                    assertThat(slot.resourceId()).isEqualTo(roomId);
                    assertThat(slot.bookingStatus()).isEqualTo("CONFIRMED");
                });
        assertThat(result.bookingEligibility()).singleElement()
                .satisfies(eligibility -> {
                    assertThat(eligibility.resourceId()).isEqualTo(roomId);
                    assertThat(eligibility.eligible()).isFalse();
                    assertThat(eligibility.reasonCode())
                            .isEqualTo(CalendarTypes.RoomBookingEligibilityReason.RESOURCE_CONFLICT);
                    assertThat(eligibility.policyVersion()).isEqualTo(1L);
                });
    }

    @Test
    void availabilityExcludesOnlyAnEditableRoomEventFromEligibility() {
        UUID personId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.parse("2026-08-19T01:00:00Z");
        OffsetDateTime to = from.plusHours(1);
        CalendarRepository.ResourceRow room = room(roomId);
        CalendarDtos.ResourceSummary summary = resource(roomId, CalendarTypes.ResourceType.ROOM);
        when(calendarRepository.event(
                1L, 7L, personId, "group-ref", eventId, true))
                .thenReturn(Optional.of(roomEvent(eventId, personId, room)));
        when(calendarService.policy(1L)).thenReturn(policy());
        when(calendarService.resources(
                1L, 7L, "group-ref", from, to, "ko-KR")).thenReturn(List.of(summary));
        when(roomRepository.resourceOccupancy(
                1L, from.minusMinutes(15), to.plusMinutes(15), eventId)).thenReturn(List.of());

        CalendarDtos.RoomAvailabilityResponse result = service.roomAvailability(
                1L, 7L, personId, "group-ref", from, to, eventId, "ko-KR");

        assertThat(result.rooms()).singleElement()
                .extracting(CalendarDtos.ResourceSummary::available)
                .isEqualTo(true);
        assertThat(result.bookingEligibility()).singleElement()
                .satisfies(eligibility -> {
                    assertThat(eligibility.eligible()).isTrue();
                    assertThat(eligibility.excludedEventId()).isEqualTo(eventId);
                    assertThat(eligibility.evaluatedFrom()).isEqualTo(from);
                    assertThat(eligibility.evaluatedTo()).isEqualTo(to);
                });
    }

    @Test
    void availabilityUsesTheTenantBufferForAdjacentRoomConflicts() {
        UUID roomId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.parse("2026-08-19T00:00:00Z");
        OffsetDateTime to = from.plusHours(1);
        when(calendarService.policy(1L)).thenReturn(policy());
        when(calendarService.resources(
                1L, 7L, null, from, to, "en-US")).thenReturn(List.of(
                resource(roomId, CalendarTypes.ResourceType.ROOM)));
        when(roomRepository.resourceOccupancy(
                1L, from.minusMinutes(15), to.plusMinutes(15), null)).thenReturn(List.of(
                new RoomRepository.ResourceOccupancyRow(
                        roomId, to.plusMinutes(10), to.plusHours(1), "CONFIRMED")));

        CalendarDtos.RoomAvailabilityResponse result = service.roomAvailability(
                1L, 7L, null, null, from, to, null, "en-US");

        assertThat(result.occupancy()).isEmpty();
        assertThat(result.rooms()).singleElement()
                .extracting(CalendarDtos.ResourceSummary::available)
                .isEqualTo(true);
        assertThat(result.bookingEligibility()).singleElement()
                .satisfies(eligibility -> {
                    assertThat(eligibility.eligible()).isFalse();
                    assertThat(eligibility.reasonCode())
                            .isEqualTo(CalendarTypes.RoomBookingEligibilityReason.RESOURCE_CONFLICT);
                });
    }

    @Test
    void availabilityCannotExcludeARoomEventWithoutEditAuthority() {
        UUID personId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        CalendarRepository.ResourceRow room = room(UUID.randomUUID());
        when(calendarRepository.event(
                1L, 7L, personId, "viewer-group", eventId, false))
                .thenReturn(Optional.of(roomEvent(
                        eventId,
                        personId,
                        room,
                        CalendarTypes.CalendarAccessLevel.VIEW_DETAILS)));

        assertThatThrownBy(() -> service.roomAvailability(
                1L,
                7L,
                personId,
                "viewer-group",
                OffsetDateTime.parse("2026-08-19T00:00:00Z"),
                OffsetDateTime.parse("2026-08-19T01:00:00Z"),
                eventId,
                "en-US"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(calendarService, never()).policy(1L);
    }

    @Test
    void availabilityRejectsUnboundedSearchWindows() {
        OffsetDateTime from = OffsetDateTime.parse("2026-08-01T00:00:00+09:00");

        assertThatThrownBy(() -> service.roomAvailability(
                1L, 7L, null, null,
                from, from.plusDays(32), null, "ko-KR"))
                .hasMessageContaining("31 days");
    }

    @Test
    void groupEditContextReachesRoomUpdatePreflightAndMutation() {
        UUID personId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        CalendarRepository.ResourceRow room = room(roomId);
        CalendarDtos.UpdateEventRequest request = update(roomId);
        when(calendarRepository.event(
                1L, 7L, personId, "group-ref", eventId, true))
                .thenReturn(Optional.of(roomEvent(eventId, personId, room)));
        when(calendarRepository.resource(1L, roomId, true)).thenReturn(Optional.of(room));

        service.updateRoomBooking(
                1L, 7L, personId, eventId, "ko-KR", "corr-update", "group-ref", request);

        verify(calendarService).update(
                1L, 7L, personId, eventId, "ko-KR", "corr-update", "group-ref", request);
    }

    @Test
    void groupManageContextReachesRoomCancelAndResponseMutations() {
        UUID personId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        CalendarRepository.ResourceRow room = room(UUID.randomUUID());
        when(calendarRepository.event(
                1L, 7L, personId, "group-ref", eventId, false))
                .thenReturn(Optional.of(roomEvent(eventId, personId, room)));
        CalendarDtos.VersionRequest cancel = new CalendarDtos.VersionRequest(0L);
        CalendarDtos.RespondRequest response = new CalendarDtos.RespondRequest(
                CalendarTypes.ResponseStatus.ACCEPTED);

        service.cancelRoomBooking(
                1L, 7L, personId, eventId, "en-US", "corr-cancel", "group-ref", cancel);
        service.respondRoomBooking(
                1L, 7L, personId, eventId, "en-US", "corr-response", "group-ref", response);

        verify(calendarService).cancel(
                1L, 7L, personId, eventId, "en-US", "corr-cancel", "group-ref", cancel);
        verify(calendarService).respond(
                1L, 7L, personId, eventId, "en-US", "corr-response", "group-ref", response);
    }

    @Test
    void revokedGroupContextFailsBeforeAnyRoomMutation() {
        UUID personId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        CalendarDtos.UpdateEventRequest request = update(roomId);
        when(calendarRepository.event(
                1L, 7L, personId, "revoked-group", eventId, false))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateRoomBooking(
                1L, 7L, personId, eventId,
                "en-US", "corr-revoked", "revoked-group", request))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(calendarService, never()).update(
                1L, 7L, personId, eventId,
                "en-US", "corr-revoked", "revoked-group", request);
        verify(calendarRepository, never()).resource(1L, roomId, false);
    }

    private CalendarDtos.UpdateEventRequest update(UUID roomId) {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-09-01T01:00:00Z");
        return new CalendarDtos.UpdateEventRequest(
                "Team review", "Decisions", CalendarTypes.EventType.MEETING,
                startsAt, startsAt.plusHours(1), "Asia/Seoul", false,
                "Focus room", null, CalendarTypes.EventVisibility.DEFAULT,
                CalendarTypes.RecurrencePattern.NONE, 1, null, false,
                List.of(), roomId, 0L);
    }

    private CalendarRepository.EventRow roomEvent(
            UUID eventId,
            UUID personId,
            CalendarRepository.ResourceRow room) {
        return roomEvent(
                eventId, personId, room, CalendarTypes.CalendarAccessLevel.OWNER);
    }

    private CalendarRepository.EventRow roomEvent(
            UUID eventId,
            UUID personId,
            CalendarRepository.ResourceRow room,
            CalendarTypes.CalendarAccessLevel accessLevel) {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-09-01T01:00:00Z");
        return new CalendarRepository.EventRow(
                eventId, UUID.randomUUID(), "Team", "#2563EB",
                99L, personId, "Owner", "owner@sk.com", "Team review", "Decisions",
                CalendarTypes.EventType.MEETING, startsAt, startsAt.plusHours(1),
                "Asia/Seoul", false, "Focus room", null,
                CalendarTypes.EventStatus.CONFIRMED, CalendarTypes.EventVisibility.DEFAULT,
                CalendarTypes.RecurrencePattern.NONE, 1, (LocalDate) null,
                false, null, room, CalendarTypes.EventImportance.NORMAL,
                CalendarTypes.EventDetailLevel.FULL, false, 0,
                accessLevel, 0L);
    }

    private CalendarRepository.ResourceRow room(UUID roomId) {
        return new CalendarRepository.ResourceRow(
                roomId, "ROOM-01", "Focus room", "집중 회의실", "Focus room",
                CalendarTypes.ResourceType.ROOM, "Seoul HQ", "6F", 8,
                List.of("VIDEO"), "Asia/Seoul", false,
                CalendarTypes.ResourceState.AVAILABLE, true, 1L);
    }

    private CalendarDtos.ResourceSummary resource(
            UUID resourceId,
            CalendarTypes.ResourceType type) {
        return new CalendarDtos.ResourceSummary(
                resourceId,
                type == CalendarTypes.ResourceType.ROOM ? "ROOM-01" : "DEVICE-01",
                type == CalendarTypes.ResourceType.ROOM ? "Focus 01" : "Camera",
                type == CalendarTypes.ResourceType.ROOM ? "포커스 01" : "카메라",
                type == CalendarTypes.ResourceType.ROOM ? "Focus 01" : "Camera",
                type,
                "판교",
                "3F",
                type == CalendarTypes.ResourceType.ROOM ? 8 : 1,
                List.of("VIDEO"),
                "Asia/Seoul",
                false,
                CalendarTypes.ResourceState.AVAILABLE,
                true,
                1L);
    }

    private CalendarDtos.Policy policy() {
        return new CalendarDtos.Policy(
                1,
                LocalTime.of(8, 0),
                LocalTime.of(20, 0),
                30,
                30,
                120,
                30,
                15,
                240,
                480,
                true,
                false,
                1L);
    }
}
