package com.dwp.services.platform.calendar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        service = new RoomService(calendarService, calendarRepository, roomRepository);
    }

    @Test
    void availabilityReturnsOnlyRoomOccupancyWithoutEventDetails() {
        UUID roomId = UUID.randomUUID();
        UUID equipmentId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.parse("2026-08-19T08:00:00+09:00");
        OffsetDateTime to = OffsetDateTime.parse("2026-08-19T20:00:00+09:00");
        when(calendarService.resources(
                1L, 7L, "group-refs", from, to, "ko-KR")).thenReturn(List.of(
                resource(roomId, CalendarTypes.ResourceType.ROOM),
                resource(equipmentId, CalendarTypes.ResourceType.EQUIPMENT)));
        when(roomRepository.resourceOccupancy(1L, from, to)).thenReturn(List.of(
                new RoomRepository.ResourceOccupancyRow(
                        roomId, from.plusHours(1), from.plusHours(2), "CONFIRMED"),
                new RoomRepository.ResourceOccupancyRow(
                        equipmentId, from.plusHours(3), from.plusHours(4), "CONFIRMED")));

        CalendarDtos.RoomAvailabilityResponse result = service.roomAvailability(
                1L, 7L, "group-refs", from, to, "ko-KR");

        assertThat(result.rooms()).singleElement()
                .extracting(CalendarDtos.ResourceSummary::resourceId)
                .isEqualTo(roomId);
        assertThat(result.occupancy()).singleElement()
                .satisfies(slot -> {
                    assertThat(slot.resourceId()).isEqualTo(roomId);
                    assertThat(slot.bookingStatus()).isEqualTo("CONFIRMED");
                });
    }

    @Test
    void availabilityRejectsUnboundedSearchWindows() {
        OffsetDateTime from = OffsetDateTime.parse("2026-08-01T00:00:00+09:00");

        assertThatThrownBy(() -> service.roomAvailability(
                1L, 7L, null, from, from.plusDays(32), "ko-KR"))
                .hasMessageContaining("31 days");
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
}
