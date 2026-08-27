package com.dwp.services.platform.calendar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomBookingPolicyServiceTest {

    private static final Long TENANT_ID = 1L;
    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

    @Mock
    private CalendarRepository repository;

    private RoomBookingPolicyService service;
    private CalendarRepository.ResourceRow room;

    @BeforeEach
    void setUp() {
        service = new RoomBookingPolicyService(
                repository,
                new CalendarSchedulingHorizon(Clock.fixed(NOW, ZoneOffset.UTC)));
        room = room();
    }

    @Test
    void roomEndpointRequiresMeetingEvents() {
        CalendarDtos.CreateEventRequest request = create(
                EventType.FOCUS,
                "2026-08-28T01:00:00Z",
                "2026-08-28T02:00:00Z",
                "Asia/Seoul",
                RecurrencePattern.NONE,
                null);
        assertThatThrownBy(() -> service.validateLockedCreate(
                TENANT_ID, room, policy(), request))
                .hasMessageContaining("meeting events");
    }

    @Test
    void roomPolicyRejectsPastWrongZoneAndAfterHoursRanges() {
        assertThatThrownBy(() -> service.validateLockedCreate(
                TENANT_ID, room, policy(), create(
                        "2026-08-26T01:00:00Z", "2026-08-26T02:00:00Z", 1)))
                .hasMessageContaining("past");
        assertThatThrownBy(() -> service.validateLockedCreate(
                TENANT_ID, room, policy(), create(
                        EventType.MEETING,
                        "2026-08-28T01:00:00Z",
                        "2026-08-28T02:00:00Z",
                        "UTC",
                        RecurrencePattern.NONE,
                        null,
                        2)))
                .hasMessageContaining("time zone");
        assertThatThrownBy(() -> service.validateLockedCreate(
                TENANT_ID, room, policy(), create(
                        "2026-08-28T12:00:00Z", "2026-08-28T13:00:00Z", 3)))
                .hasMessageContaining("operating hours");
    }

    @Test
    void bufferConflictUsesTheAlreadyLockedRoomWindow() {
        CalendarDtos.CreateEventRequest request = create(
                "2026-08-28T01:00:00Z", "2026-08-28T02:00:00Z", 4);
        when(repository.resourceConflict(
                TENANT_ID,
                room.resourceId(),
                OffsetDateTime.parse("2026-08-28T00:45:00Z"),
                OffsetDateTime.parse("2026-08-28T02:15:00Z"),
                null)).thenReturn(true);

        assertThatThrownBy(() -> service.validateLockedCreate(
                TENANT_ID, room, policy(), request))
                .hasMessageContaining("booking buffer");

        verify(repository).resourceConflict(
                TENANT_ID,
                room.resourceId(),
                OffsetDateTime.parse("2026-08-28T00:45:00Z"),
                OffsetDateTime.parse("2026-08-28T02:15:00Z"),
                null);
    }

    @Test
    void recurringBookingValidatesEveryOccurrenceInTheCanonicalZone() {
        CalendarDtos.CreateEventRequest request = create(
                EventType.MEETING,
                "2026-08-28T01:00:00Z",
                "2026-08-28T02:00:00Z",
                "Asia/Seoul",
                RecurrencePattern.WEEKLY,
                LocalDate.parse("2026-09-04"));
        service.validateLockedCreate(TENANT_ID, room, policy(), request);

        verify(repository).resourceConflict(
                TENANT_ID,
                room.resourceId(),
                OffsetDateTime.parse("2026-08-28T00:45:00Z"),
                OffsetDateTime.parse("2026-08-28T02:15:00Z"),
                null);
        verify(repository).resourceConflict(
                TENANT_ID,
                room.resourceId(),
                OffsetDateTime.parse("2026-09-04T09:45:00+09:00"),
                OffsetDateTime.parse("2026-09-04T11:15:00+09:00"),
                null);
    }

    @Test
    void updateExcludesTheCurrentEventFromBufferedConflictDetection() {
        UUID eventId = UUID.randomUUID();
        CalendarDtos.UpdateEventRequest request = update(
                "2026-08-28T01:00:00Z", "2026-08-28T02:00:00Z");

        service.validateLockedUpdate(TENANT_ID, room, policy(), eventId, request);

        verify(repository).resourceConflict(
                TENANT_ID,
                room.resourceId(),
                OffsetDateTime.parse("2026-08-28T00:45:00Z"),
                OffsetDateTime.parse("2026-08-28T02:15:00Z"),
                eventId);
    }

    @Test
    void roomAdvanceWindowIncludesTheEntireLastLocalDayAndRejectsTheNextDay() {
        CalendarDtos.CreateEventRequest lateOnLastDay = create(
                "2026-09-26T08:00:00Z", "2026-09-26T09:00:00Z", 11);

        service.validateLockedCreate(TENANT_ID, room, policy(), lateOnLastDay);

        assertThatThrownBy(() -> service.validateLockedCreate(
                TENANT_ID,
                room,
                policy(),
                create("2026-09-27T01:00:00Z", "2026-09-27T02:00:00Z", 12)))
                .hasMessageContaining("advance booking policy");
    }

    private CalendarDtos.CreateEventRequest create(String start, String end, int keySuffix) {
        return create(
                EventType.MEETING, start, end, "Asia/Seoul",
                RecurrencePattern.NONE, null, keySuffix);
    }

    private CalendarDtos.CreateEventRequest create(
            EventType type,
            String start,
            String end,
            String timeZone,
            RecurrencePattern recurrence,
            LocalDate recurrenceUntil) {
        return create(type, start, end, timeZone, recurrence, recurrenceUntil, 10);
    }

    private CalendarDtos.CreateEventRequest create(
            EventType type,
            String start,
            String end,
            String timeZone,
            RecurrencePattern recurrence,
            LocalDate recurrenceUntil,
            int keySuffix) {
        return new CalendarDtos.CreateEventRequest(
                "Architecture review",
                "Decision agenda",
                type,
                OffsetDateTime.parse(start),
                OffsetDateTime.parse(end),
                timeZone,
                false,
                room.name(),
                null,
                EventVisibility.DEFAULT,
                recurrence,
                1,
                recurrenceUntil,
                false,
                List.of(),
                room.resourceId(),
                UUID.fromString(String.format(
                        "10000000-0000-0000-0000-%012d", keySuffix)));
    }

    private CalendarDtos.UpdateEventRequest update(String start, String end) {
        return new CalendarDtos.UpdateEventRequest(
                "Architecture review",
                "Decision agenda",
                EventType.MEETING,
                OffsetDateTime.parse(start),
                OffsetDateTime.parse(end),
                "Asia/Seoul",
                false,
                room.name(),
                null,
                EventVisibility.DEFAULT,
                RecurrencePattern.NONE,
                1,
                null,
                false,
                List.of(),
                room.resourceId(),
                1L);
    }

    private CalendarRepository.ResourceRow room() {
        return new CalendarRepository.ResourceRow(
                UUID.randomUUID(), "ROOM-01", "Focus room", "집중 회의실", "Focus room",
                ResourceType.ROOM, "Seoul HQ", "6F", 8, List.of("VIDEO"),
                "Asia/Seoul", false, ResourceState.AVAILABLE, true, 1L);
    }

    private CalendarRepository.PolicyRow policy() {
        return new CalendarRepository.PolicyRow(
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
