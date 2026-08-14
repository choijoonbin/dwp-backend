package com.dwp.services.platform.calendar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarServiceTest {

    @Mock
    private CalendarRepository repository;

    private CalendarService service;

    @BeforeEach
    void setUp() {
        service = new CalendarService(repository);
    }

    @Test
    void availabilityUsesPeopleIdentifiersWithoutRevealingEventDetails() {
        UUID personId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.parse("2026-08-17T09:00:00+09:00");
        OffsetDateTime to = OffsetDateTime.parse("2026-08-17T13:00:00+09:00");
        when(repository.policy(1L)).thenReturn(policy());
        when(repository.busySlots(1L, List.of(personId), from, to)).thenReturn(List.of(
                new CalendarRepository.BusyRow(
                        personId,
                        OffsetDateTime.parse("2026-08-17T09:00:00+09:00"),
                        OffsetDateTime.parse("2026-08-17T10:00:00+09:00"))));

        CalendarDtos.AvailabilityResponse response = service.availability(
                1L, 7L, personId, List.of(), from, to, 60, "Asia/Seoul", "ko-KR");

        assertThat(response.participants()).singleElement()
                .extracting(CalendarDtos.AvailabilityParticipant::busyMinutes)
                .isEqualTo(60);
        assertThat(response.suggestions())
                .allMatch(slot -> !slot.startsAt().isBefore(
                        OffsetDateTime.parse("2026-08-17T10:00:00+09:00")))
                .extracting(CalendarDtos.AvailabilitySlot::reason)
                .allMatch(reason -> !reason.isBlank());
        verify(repository).linkIdentity(1L, 7L, personId);
    }

    @Test
    void governedBookingDecisionIsOptimisticAndAudited() {
        UUID bookingId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        CalendarRepository.BookingRow saved = new CalendarRepository.BookingRow(
                bookingId, eventId, resourceId, "고객 데모 스튜디오", "고객 워크숍",
                OffsetDateTime.parse("2026-08-18T13:00:00+09:00"),
                OffsetDateTime.parse("2026-08-18T15:00:00+09:00"),
                "김민서", "minseo.kim@sk.com", "CONFIRMED", 7L,
                "고객 일정 확인", OffsetDateTime.now(), 11L, 3L);
        when(repository.decideBooking(
                1L, 11L, bookingId, "CONFIRMED", "고객 일정 확인", 2L, true))
                .thenReturn(saved);

        CalendarDtos.BookingSummary result = service.decideBooking(
                1L, 11L, bookingId, "ko-KR", "corr-1",
                new CalendarDtos.BookingDecisionRequest("APPROVE", "고객 일정 확인", 2L));

        assertThat(result.status()).isEqualTo("CONFIRMED");
        assertThat(result.version()).isEqualTo(3L);
        verify(repository).audit(
                eq(1L), eq(11L), eq(eventId), eq("calendar.booking.confirmed"),
                eq("corr-1"), anyMap(), anyMap());
    }

    @Test
    void recurrenceKeepsLocalWallClockAcrossDaylightSavingTime() {
        UUID personId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-03-01T09:00:00-05:00");
        OffsetDateTime endsAt = OffsetDateTime.parse("2026-03-01T10:00:00-05:00");
        CalendarRepository.EventRow row = event(
                eventId, startsAt, endsAt, "America/New_York",
                CalendarTypes.RecurrencePattern.WEEKLY, LocalDate.parse("2026-03-20"));
        when(repository.visibleEvents(
                eq(1L), eq(7L), eq(personId), any(), any(), eq(false)))
                .thenReturn(List.of(row));
        when(repository.attendees(1L, eventId)).thenReturn(List.of());

        List<CalendarDtos.EventSummary> events = service.events(
                1L, 7L, personId,
                OffsetDateTime.parse("2026-03-01T00:00:00-05:00"),
                OffsetDateTime.parse("2026-03-20T23:59:00-04:00"), "en-US");

        assertThat(events).hasSize(3);
        assertThat(events).extracting(value ->
                        value.startsAt().atZoneSameInstant(ZoneId.of("America/New_York")).getHour())
                .containsOnly(9);
        assertThat(events.get(1).startsAt().getOffset().toString()).isEqualTo("-04:00");
    }

    @Test
    void recurringResourceReservationRequiresFiniteEndDateBeforeLocking() {
        UUID personId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime startsAt = OffsetDateTime.now().plusDays(2).withSecond(0).withNano(0);
        CalendarDtos.CreateEventRequest request = new CalendarDtos.CreateEventRequest(
                "주간 회의", "안건", CalendarTypes.EventType.MEETING,
                startsAt, startsAt.plusMinutes(30), "Asia/Seoul", false,
                null, null, CalendarTypes.EventVisibility.DEFAULT,
                CalendarTypes.RecurrencePattern.WEEKLY, 1, null, true,
                List.of(), resourceId, UUID.randomUUID());
        when(repository.policy(1L)).thenReturn(policy());
        when(repository.resource(1L, resourceId, true)).thenReturn(java.util.Optional.of(
                new CalendarRepository.ResourceRow(
                        resourceId, "ROOM-1", "회의실", "회의실", "Room",
                        CalendarTypes.ResourceType.ROOM, "판교", "3F", 8,
                        List.of("VIDEO"), "Asia/Seoul", false,
                        CalendarTypes.ResourceState.AVAILABLE, true, 0L)));

        assertThatThrownBy(() -> service.create(
                1L, 7L, personId, "김민서", "ko-KR", "corr", request))
                .hasMessageContaining("require an end date");
        verify(repository, never()).lockResource(1L, resourceId);
    }

    @Test
    void homeFindsTheNextEventBeyondTheCurrentWeek() {
        UUID personId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        OffsetDateTime future = OffsetDateTime.now(ZoneId.of("Asia/Seoul"))
                .plusDays(10).withHour(10).withMinute(0).withSecond(0).withNano(0);
        when(repository.policy(1L)).thenReturn(policy());
        when(repository.visibleEvents(
                eq(1L), eq(7L), eq(personId), any(), any(), eq(true)))
                .thenReturn(List.of(event(
                        eventId, future, future.plusMinutes(30), "Asia/Seoul",
                        CalendarTypes.RecurrencePattern.NONE, null)));
        when(repository.attendees(1L, eventId)).thenReturn(List.of());
        when(repository.resources(eq(1L), any(), any(), eq(true), eq(false)))
                .thenReturn(List.of());

        CalendarDtos.HomeResponse home = service.home(
                1L, 7L, personId, "Asia/Seoul", "ko-KR");

        assertThat(home.nextEvent()).isNotNull();
        assertThat(home.nextEvent().eventId()).isEqualTo(eventId);
        assertThat(home.metrics().eventCount()).isZero();
    }

    @Test
    void organizerAuthorizationUsesStablePeopleIdentityAfterIamIdChanges() {
        UUID personId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        OffsetDateTime startsAt = OffsetDateTime.now().plusDays(1);
        CalendarRepository.EventRow row = event(
                eventId, startsAt, startsAt.plusMinutes(30), "Asia/Seoul",
                CalendarTypes.RecurrencePattern.NONE, null, 5L, personId);
        when(repository.event(1L, 20L, personId, eventId, true))
                .thenReturn(Optional.of(row));
        when(repository.cancelEvent(1L, 20L, personId, eventId, 0L)).thenReturn(1);

        service.cancel(
                1L, 20L, personId, eventId, "ko-KR", "corr-stable-person",
                new CalendarDtos.VersionRequest(0L));

        verify(repository).cancelEvent(1L, 20L, personId, eventId, 0L);
        verify(repository).cancelBookings(1L, 20L, eventId);
    }

    private CalendarRepository.EventRow event(
            UUID eventId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String timeZone,
            CalendarTypes.RecurrencePattern recurrence,
            LocalDate recurrenceUntil) {
        return event(
                eventId, startsAt, endsAt, timeZone, recurrence, recurrenceUntil, 7L, null);
    }

    private CalendarRepository.EventRow event(
            UUID eventId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String timeZone,
            CalendarTypes.RecurrencePattern recurrence,
            LocalDate recurrenceUntil,
            Long organizerUserId,
            UUID organizerPersonPublicId) {
        return new CalendarRepository.EventRow(
                eventId, UUID.randomUUID(), "내 캘린더", "#2563EB",
                organizerUserId, organizerPersonPublicId,
                "김민서", "minseo.kim@sk.com", "일정", "설명",
                CalendarTypes.EventType.MEETING, startsAt, endsAt, timeZone,
                false, null, null, CalendarTypes.EventStatus.CONFIRMED,
                CalendarTypes.EventVisibility.DEFAULT, recurrence, 1, recurrenceUntil,
                false, null, null, 0L);
    }

    private CalendarRepository.PolicyRow policy() {
        return new CalendarRepository.PolicyRow(
                1, LocalTime.of(9, 0), LocalTime.of(18, 0),
                30, 15, 480, 365, 10, 600, 300,
                false, true, 0L);
    }
}
