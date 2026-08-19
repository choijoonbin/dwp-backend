package com.dwp.services.platform.calendar;

import com.dwp.services.platform.workplace.WorkplaceRoomAccessPort;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarServiceTest {

    @Mock
    private CalendarRepository repository;

    @Mock
    private WorkplaceRoomAccessPort roomAccess;

    private CalendarService service;

    @BeforeEach
    void setUp() {
        service = new CalendarService(repository, roomAccess);
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
    void workplaceManagedRoomRejectsDirectCalendarAdministration() {
        UUID resourceId = UUID.randomUUID();
        when(repository.isWorkplaceManagedResource(1L, resourceId)).thenReturn(true);
        CalendarDtos.ResourceRequest request = new CalendarDtos.ResourceRequest(
                "ROOM-01", "회의실", "Meeting room", CalendarTypes.ResourceType.ROOM,
                "본사", "8F", 8, List.of("DISPLAY"), "Asia/Seoul",
                false, CalendarTypes.ResourceState.AVAILABLE, 1L);

        assertThatThrownBy(() -> service.saveResource(
                1L, 11L, resourceId, "ko-KR", "corr-workplace", request))
                .isInstanceOf(com.dwp.core.exception.BaseException.class)
                .hasMessageContaining("managed by Workplace");

        verify(repository, never()).saveResource(
                eq(1L), eq(11L), eq(resourceId), any(), eq(true));
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

    @Test
    void sameIdempotencyKeyWithDifferentPayloadIsRejectedAfterLocking() {
        CalendarDtos.CreateEventRequest request = createRequest(UUID.randomUUID(), null);
        when(repository.eventIdempotency(1L, 7L, request.idempotencyKey()))
                .thenReturn(Optional.of(new CalendarRepository.IdempotencyRow(
                        UUID.randomUUID(), "0".repeat(64))));

        assertThatThrownBy(() -> service.create(
                1L, 7L, UUID.randomUUID(), "User", "en-US", "corr",
                UUID.randomUUID().toString(), request))
                .hasMessageContaining("different request");

        var ordered = inOrder(repository);
        ordered.verify(repository).lockEventIdempotency(1L, 7L, request.idempotencyKey());
        ordered.verify(repository).eventIdempotency(1L, 7L, request.idempotencyKey());
        verify(repository, never()).insertEvent(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void deterministicReplayReturnsTheExistingEventAndRechecksRoomAccess() {
        UUID personId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        CalendarDtos.CreateEventRequest request = createRequest(UUID.randomUUID(), resourceId);
        CalendarRepository.EventRow existing = eventWithResource(eventId, resourceId, personId);
        String fingerprint = CalendarRequestFingerprint.create(request);
        when(repository.eventIdempotency(1L, 7L, request.idempotencyKey()))
                .thenReturn(Optional.of(new CalendarRepository.IdempotencyRow(eventId, fingerprint)));
        when(repository.event(1L, 7L, personId, eventId, true))
                .thenReturn(Optional.of(existing));
        when(repository.attendees(1L, eventId)).thenReturn(List.of());

        CalendarDtos.EventSummary replay = service.create(
                1L, 7L, personId, "사용자", "ko-KR", "corr",
                "a5a5a5a5-1111-2222-3333-444444444444", request);

        assertThat(replay.eventId()).isEqualTo(eventId);
        verify(roomAccess).requireBook(
                1L, 7L, "a5a5a5a5-1111-2222-3333-444444444444", resourceId);
        verify(repository, never()).ensurePersonalCalendar(any(), any(), any());
    }

    @Test
    void workplaceRoomAuthorizationRunsBeforeEventInsertion() {
        UUID personId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        CalendarDtos.CreateEventRequest request = createRequest(UUID.randomUUID(), resourceId);
        when(repository.policy(1L)).thenReturn(policy());
        when(repository.resource(1L, resourceId, true)).thenReturn(Optional.of(
                resource(resourceId)));
        org.mockito.Mockito.doThrow(new com.dwp.core.exception.BaseException(
                        com.dwp.core.common.ErrorCode.FORBIDDEN, "denied"))
                .when(roomAccess).requireBook(1L, 7L, "group-ref", resourceId);

        assertThatThrownBy(() -> service.create(
                1L, 7L, personId, "User", "ko-KR", "corr",
                "group-ref", request))
                .hasMessageContaining("denied");

        verify(repository, never()).insertEvent(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void calendarEventQueriesExcludeWorkplaceRoomsWithoutViewAccess() {
        UUID personId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.now();
        OffsetDateTime to = from.plusDays(2);
        when(repository.visibleEvents(1L, 7L, personId, from, to, true))
                .thenReturn(List.of(eventWithResource(eventId, resourceId, personId)));
        when(repository.attendees(1L, eventId)).thenReturn(List.of());
        when(roomAccess.viewableResourceIds(
                1L, 7L, "groups", java.util.Set.of(resourceId)))
                .thenReturn(java.util.Set.of());

        assertThat(service.events(
                1L, 7L, personId, "groups", from, to, "ko-KR")).isEmpty();
    }

    @Test
    void calendarCancellationCannotBypassWorkplaceBookAccess() {
        UUID personId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        when(repository.event(1L, 7L, personId, eventId, true))
                .thenReturn(Optional.of(eventWithResource(eventId, resourceId, personId)));
        org.mockito.Mockito.doThrow(new com.dwp.core.exception.BaseException(
                        com.dwp.core.common.ErrorCode.FORBIDDEN, "denied"))
                .when(roomAccess).requireBook(1L, 7L, "groups", resourceId);

        assertThatThrownBy(() -> service.cancel(
                1L, 7L, personId, eventId, "ko-KR", "corr", "groups",
                new CalendarDtos.VersionRequest(0L)))
                .hasMessageContaining("denied");

        verify(repository, never()).cancelEvent(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(repository, never()).cancelBookings(any(), any(), any());
    }

    private CalendarDtos.CreateEventRequest createRequest(
            UUID idempotencyKey, UUID resourceId) {
        OffsetDateTime startsAt = OffsetDateTime.now().plusDays(2)
                .withSecond(0).withNano(0);
        return new CalendarDtos.CreateEventRequest(
                "회의", "안건", CalendarTypes.EventType.MEETING,
                startsAt, startsAt.plusMinutes(30), "Asia/Seoul", false,
                "본사", null, CalendarTypes.EventVisibility.DEFAULT,
                CalendarTypes.RecurrencePattern.NONE, 1, null, true,
                List.of(), resourceId, idempotencyKey);
    }

    private CalendarRepository.EventRow eventWithResource(
            UUID eventId, UUID resourceId, UUID personId) {
        OffsetDateTime startsAt = OffsetDateTime.now().plusDays(1);
        CalendarRepository.EventRow base = event(
                eventId, startsAt, startsAt.plusMinutes(30), "Asia/Seoul",
                CalendarTypes.RecurrencePattern.NONE, null, 7L, personId);
        return new CalendarRepository.EventRow(
                base.eventId(), base.calendarId(), base.calendarName(), base.calendarColor(),
                base.organizerUserId(), base.organizerPersonPublicId(), base.organizerName(),
                base.organizerEmail(), base.title(), base.description(), base.type(),
                base.startsAt(), base.endsAt(), base.timeZone(), base.allDay(), base.location(),
                base.conferenceUrl(), base.status(), base.visibility(), base.recurrence(),
                base.recurrenceInterval(), base.recurrenceUntil(), base.responseRequired(),
                base.myResponse(), resource(resourceId), base.version());
    }

    private CalendarRepository.ResourceRow resource(UUID resourceId) {
        return new CalendarRepository.ResourceRow(
                resourceId, "ROOM-1", "회의실", "회의실", "Room",
                CalendarTypes.ResourceType.ROOM, "판교", "3F", 8,
                List.of("VIDEO"), "Asia/Seoul", false,
                CalendarTypes.ResourceState.AVAILABLE, true, 0L);
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
