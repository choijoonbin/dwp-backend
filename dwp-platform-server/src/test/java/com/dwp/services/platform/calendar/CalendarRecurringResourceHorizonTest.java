package com.dwp.services.platform.calendar;

import com.dwp.services.platform.workplace.WorkplaceRoomAccessPort;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarRecurringResourceHorizonTest {

    private static final long TENANT_ID = 1L;
    private static final long USER_ID = 7L;
    private static final UUID PERSON_ID = UUID.fromString(
            "70000000-0000-4000-8000-000000000001");
    private static final UUID CALENDAR_ID = UUID.fromString(
            "70000000-0000-4000-8000-000000000002");
    private static final UUID EVENT_ID = UUID.fromString(
            "70000000-0000-4000-8000-000000000003");
    private static final UUID ROOM_ID = UUID.fromString(
            "70000000-0000-4000-8000-000000000004");

    @Mock
    private CalendarRepository repository;

    @Mock
    private WorkplaceRoomAccessPort roomAccess;

    @Mock
    private RoomRepository roomRepository;

    @Test
    void roomsCreateAcceptsTheLastLocalDayAcrossFallBack() {
        CalendarService calendar = serviceAt("2026-10-25T13:00:00Z");
        RoomService rooms = new RoomService(
                calendar, repository, roomRepository, mock(RoomBookingPolicyService.class));
        CalendarDtos.CreateEventRequest request = create(
                "2026-10-25T10:00:00-04:00",
                "2026-10-25T11:00:00-04:00",
                LocalDate.parse("2026-11-08"));
        stubCreate(request);

        CalendarDtos.EventSummary created = rooms.createRoomBooking(
                TENANT_ID, USER_ID, PERSON_ID, "Owner", "en-US", "corr-fall",
                null, request);

        assertThat(created.eventId()).isEqualTo(EVENT_ID);
    }

    @Test
    void calendarUpdateAcceptsTheLastLocalDayAcrossFallBack() {
        CalendarService calendar = serviceAt("2026-10-25T13:00:00Z");
        CalendarDtos.UpdateEventRequest request = update(
                "2026-10-25T10:00:00-04:00",
                "2026-10-25T11:00:00-04:00",
                LocalDate.parse("2026-11-08"));
        CalendarRepository.EventRow row = event(
                request.startsAt(), request.endsAt(), request.recurrenceUntil());
        when(repository.policy(TENANT_ID)).thenReturn(policy());
        when(repository.event(TENANT_ID, USER_ID, PERSON_ID, EVENT_ID, false))
                .thenReturn(Optional.of(row));
        when(repository.resource(TENANT_ID, ROOM_ID, false)).thenReturn(Optional.of(room()));
        when(repository.updateEvent(
                TENANT_ID, USER_ID, PERSON_ID, EVENT_ID, request)).thenReturn(1);
        when(repository.attendees(TENANT_ID, EVENT_ID)).thenReturn(List.of());

        CalendarDtos.EventSummary updated = calendar.update(
                TENANT_ID, USER_ID, PERSON_ID, EVENT_ID,
                "en-US", "corr-update", request);

        assertThat(updated.eventId()).isEqualTo(EVENT_ID);
    }

    @Test
    void calendarCreateKeepsTheSameWallClockAcrossSpringForward() {
        CalendarService calendar = serviceAt("2026-03-01T13:00:00Z");
        CalendarDtos.CreateEventRequest request = create(
                "2026-03-01T10:00:00-05:00",
                "2026-03-01T11:00:00-05:00",
                LocalDate.parse("2026-03-15"));
        stubCreate(request);

        CalendarDtos.EventSummary created = calendar.create(
                TENANT_ID, USER_ID, PERSON_ID, "Owner",
                "en-US", "corr-spring", request);

        assertThat(created.eventId()).isEqualTo(EVENT_ID);
    }

    @Test
    void roomsCreateRejectsTheNextLocalDate() {
        CalendarService calendar = serviceAt("2026-10-25T13:00:00Z");
        RoomService rooms = new RoomService(
                calendar, repository, roomRepository, mock(RoomBookingPolicyService.class));
        CalendarDtos.CreateEventRequest request = create(
                "2026-10-25T10:00:00-04:00",
                "2026-10-25T11:00:00-04:00",
                LocalDate.parse("2026-11-09"));
        when(repository.resource(TENANT_ID, ROOM_ID, false)).thenReturn(Optional.of(room()));
        when(repository.policy(TENANT_ID)).thenReturn(policy());

        assertThatThrownBy(() -> rooms.createRoomBooking(
                TENANT_ID, USER_ID, PERSON_ID, "Owner", "en-US", "corr-next-day",
                null, request))
                .hasMessageContaining("advance booking policy");
    }

    private CalendarService serviceAt(String instant) {
        CalendarSchedulingHorizon horizon = new CalendarSchedulingHorizon(Clock.fixed(
                Instant.parse(instant), ZoneOffset.UTC));
        return new CalendarService(
                repository,
                roomAccess,
                horizon,
                new RoomBookingPolicyService(repository, horizon));
    }

    private void stubCreate(CalendarDtos.CreateEventRequest request) {
        when(repository.policy(TENANT_ID)).thenReturn(policy());
        when(repository.resource(TENANT_ID, ROOM_ID, false)).thenReturn(Optional.of(room()));
        when(repository.ensurePersonalCalendar(TENANT_ID, USER_ID, PERSON_ID))
                .thenReturn(CALENDAR_ID);
        when(repository.insertEvent(
                eq(TENANT_ID), eq(USER_ID), eq(PERSON_ID), eq("Owner"), eq(CALENDAR_ID),
                anyString(), eq(request))).thenReturn(EVENT_ID);
        when(repository.event(TENANT_ID, USER_ID, PERSON_ID, EVENT_ID, false))
                .thenReturn(Optional.of(event(
                        request.startsAt(), request.endsAt(), request.recurrenceUntil())));
        when(repository.attendees(TENANT_ID, EVENT_ID)).thenReturn(List.of());
    }

    private CalendarDtos.CreateEventRequest create(
            String startsAt,
            String endsAt,
            LocalDate recurrenceUntil) {
        return new CalendarDtos.CreateEventRequest(
                "Weekly room review", "Decision agenda", CalendarTypes.EventType.MEETING,
                OffsetDateTime.parse(startsAt), OffsetDateTime.parse(endsAt),
                "America/New_York", false, "Focus room", null,
                CalendarTypes.EventVisibility.DEFAULT, CalendarTypes.RecurrencePattern.WEEKLY,
                1, recurrenceUntil, false, List.of(), ROOM_ID, UUID.randomUUID());
    }

    private CalendarDtos.UpdateEventRequest update(
            String startsAt,
            String endsAt,
            LocalDate recurrenceUntil) {
        return new CalendarDtos.UpdateEventRequest(
                "Weekly room review", "Decision agenda", CalendarTypes.EventType.MEETING,
                OffsetDateTime.parse(startsAt), OffsetDateTime.parse(endsAt),
                "America/New_York", false, "Focus room", null,
                CalendarTypes.EventVisibility.DEFAULT, CalendarTypes.RecurrencePattern.WEEKLY,
                1, recurrenceUntil, false, List.of(), ROOM_ID, 0L);
    }

    private CalendarRepository.EventRow event(
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            LocalDate recurrenceUntil) {
        return new CalendarRepository.EventRow(
                EVENT_ID, CALENDAR_ID, "Team", "#2563EB",
                USER_ID, PERSON_ID, "Owner", "owner@sk.com",
                "Weekly room review", "Decision agenda", CalendarTypes.EventType.MEETING,
                startsAt, endsAt, "America/New_York", false, "Focus room", null,
                CalendarTypes.EventStatus.CONFIRMED, CalendarTypes.EventVisibility.DEFAULT,
                CalendarTypes.RecurrencePattern.WEEKLY, 1, recurrenceUntil,
                false, null, room(), 0L);
    }

    private CalendarRepository.ResourceRow room() {
        return new CalendarRepository.ResourceRow(
                ROOM_ID, "ROOM-01", "Focus room", "집중 회의실", "Focus room",
                CalendarTypes.ResourceType.ROOM, "New York", "6F", 8,
                List.of("VIDEO"), "America/New_York", false,
                CalendarTypes.ResourceState.AVAILABLE, true, 1L);
    }

    private CalendarRepository.PolicyRow policy() {
        return new CalendarRepository.PolicyRow(
                1, LocalTime.of(8, 0), LocalTime.of(20, 0),
                30, 30, 120, 14, 15, 240, 480,
                true, false, 1L);
    }
}
