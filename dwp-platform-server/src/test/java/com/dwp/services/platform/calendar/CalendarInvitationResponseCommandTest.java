package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.WorkplaceRoomAccessPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.ResponseStatus.ACCEPTED;
import static com.dwp.services.platform.calendar.CalendarTypes.ResponseStatus.NEEDS_ACTION;
import static com.dwp.services.platform.calendar.CalendarTypes.ResponseStatus.TENTATIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarInvitationResponseCommandTest {

    private static final long TENANT_ID = 7L;
    private static final long ACTOR_ID = 71L;
    private static final UUID PERSON_ID =
            UUID.fromString("71000000-0000-4000-8000-000000000001");
    private static final UUID EVENT_ID =
            UUID.fromString("71000000-0000-4000-8000-000000000002");
    private static final UUID ATTENDEE_ID =
            UUID.fromString("71000000-0000-4000-8000-000000000003");

    @Mock
    private CalendarRepository repository;
    @Mock
    private CalendarInvitationResponseRepository responses;
    @Mock
    private CalendarOccurrenceProjector projector;
    @Mock
    private WorkplaceRoomAccessPort roomAccess;

    private CalendarRoomAccessGuard roomAccessGuard;

    @BeforeEach
    void setUp() {
        roomAccessGuard = new CalendarRoomAccessGuard(roomAccess);
        lenient().when(repository.invitationResponses()).thenReturn(responses);
    }

    @Test
    void firstResponseMutatesOnceAndExactRetryReplaysWithoutAnotherAudit() {
        UUID key = UUID.randomUUID();
        CalendarDtos.RespondRequest request = request(ACCEPTED, 4L, key);
        String fingerprint = CalendarInvitationResponseCommand.fingerprint(
                EVENT_ID, PERSON_ID, request);
        CalendarRepository.EventRow before = event(NEEDS_ACTION, 4L);
        CalendarRepository.EventRow after = event(ACCEPTED, 5L);
        CalendarDtos.EventSummary summary = org.mockito.Mockito.mock(
                CalendarDtos.EventSummary.class);

        when(repository.event(TENANT_ID, ACTOR_ID, PERSON_ID, EVENT_ID, false))
                .thenReturn(Optional.of(before), Optional.of(after),
                        Optional.of(after), Optional.of(after));
        when(responses.receipt(TENANT_ID, ACTOR_ID, key))
                .thenReturn(Optional.empty(), Optional.of(new CalendarInvitationResponseRepository.ResponseReceipt(
                        PERSON_ID, EVENT_ID, fingerprint, 4L, 5L, ACCEPTED, 1L)));
        when(responses.lockState(TENANT_ID, ACTOR_ID, PERSON_ID, EVENT_ID))
                .thenReturn(
                        Optional.of(new CalendarInvitationResponseRepository.LockedResponseState(
                                ATTENDEE_ID, 4L, NEEDS_ACTION, 0L)),
                        Optional.of(new CalendarInvitationResponseRepository.LockedResponseState(
                                ATTENDEE_ID, 5L, ACCEPTED, 1L)));
        when(responses.updateAttendee(TENANT_ID, ATTENDEE_ID, 0L, ACCEPTED))
                .thenReturn(1L);
        when(responses.updateEventVersion(TENANT_ID, ACTOR_ID, EVENT_ID, 4L))
                .thenReturn(5L);
        when(responses.insertReceipt(
                TENANT_ID, ACTOR_ID, PERSON_ID, key, EVENT_ID, fingerprint,
                4L, 5L, ACCEPTED, 1L)).thenReturn(1);
        when(projector.summary(
                TENANT_ID, ACTOR_ID, PERSON_ID, after, false, "en-US"))
                .thenReturn(summary);

        CalendarDtos.EventSummary first = respond(request);
        CalendarDtos.EventSummary replay = respond(request);

        assertThat(first).isSameAs(summary);
        assertThat(replay).isSameAs(summary);
        verify(responses, times(2)).lockCommand(TENANT_ID, ACTOR_ID, key);
        verify(responses, times(1)).updateAttendee(
                TENANT_ID, ATTENDEE_ID, 0L, ACCEPTED);
        verify(responses, times(1)).updateEventVersion(
                TENANT_ID, ACTOR_ID, EVENT_ID, 4L);
        verify(responses, times(1)).insertReceipt(
                TENANT_ID, ACTOR_ID, PERSON_ID, key, EVENT_ID, fingerprint,
                4L, 5L, ACCEPTED, 1L);
        verify(repository, times(1)).audit(
                eq(TENANT_ID), eq(ACTOR_ID), eq(EVENT_ID),
                eq("calendar.attendee.responded"), eq("corr"), anyMap(), anyMap());
    }

    @Test
    void reusedKeyWithDifferentFingerprintFailsBeforeAnyMutation() {
        UUID key = UUID.randomUUID();
        CalendarDtos.RespondRequest request = request(TENTATIVE, 4L, key);
        when(repository.event(TENANT_ID, ACTOR_ID, PERSON_ID, EVENT_ID, false))
                .thenReturn(Optional.of(event(ACCEPTED, 5L)));
        when(responses.receipt(TENANT_ID, ACTOR_ID, key)).thenReturn(Optional.of(
                new CalendarInvitationResponseRepository.ResponseReceipt(
                        PERSON_ID, EVENT_ID, "0".repeat(64), 4L, 5L, ACCEPTED, 1L)));
        when(responses.lockState(TENANT_ID, ACTOR_ID, PERSON_ID, EVENT_ID))
                .thenReturn(Optional.of(new CalendarInvitationResponseRepository.LockedResponseState(
                        ATTENDEE_ID, 5L, ACCEPTED, 1L)));

        assertConflict(() -> respond(request));

        verify(responses, never()).updateAttendee(any(), any(), any(Long.class), any());
        verify(responses, never()).updateEventVersion(any(), any(), any(), any(Long.class));
        verify(repository, never()).audit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void staleExpectedEventVersionFailsBeforeAttendeeMutation() {
        UUID key = UUID.randomUUID();
        CalendarDtos.RespondRequest request = request(ACCEPTED, 4L, key);
        when(repository.event(TENANT_ID, ACTOR_ID, PERSON_ID, EVENT_ID, false))
                .thenReturn(Optional.of(event(NEEDS_ACTION, 5L)));
        when(responses.receipt(TENANT_ID, ACTOR_ID, key)).thenReturn(Optional.empty());
        when(responses.lockState(TENANT_ID, ACTOR_ID, PERSON_ID, EVENT_ID))
                .thenReturn(Optional.of(new CalendarInvitationResponseRepository.LockedResponseState(
                        ATTENDEE_ID, 5L, NEEDS_ACTION, 0L)));

        assertConflict(() -> respond(request));

        verify(responses, never()).updateAttendee(any(), any(), any(Long.class), any());
        verify(repository, never()).audit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void replayFailsClosedWhenEventOrResponseStateDrifted() {
        UUID eventDriftKey = UUID.randomUUID();
        CalendarDtos.RespondRequest eventDrift = request(ACCEPTED, 4L, eventDriftKey);
        String eventFingerprint = CalendarInvitationResponseCommand.fingerprint(
                EVENT_ID, PERSON_ID, eventDrift);
        when(repository.event(TENANT_ID, ACTOR_ID, PERSON_ID, EVENT_ID, false))
                .thenReturn(Optional.of(event(ACCEPTED, 6L)));
        when(responses.receipt(TENANT_ID, ACTOR_ID, eventDriftKey)).thenReturn(Optional.of(
                new CalendarInvitationResponseRepository.ResponseReceipt(
                        PERSON_ID, EVENT_ID, eventFingerprint, 4L, 5L, ACCEPTED, 1L)));
        when(responses.lockState(TENANT_ID, ACTOR_ID, PERSON_ID, EVENT_ID))
                .thenReturn(Optional.of(new CalendarInvitationResponseRepository.LockedResponseState(
                        ATTENDEE_ID, 6L, ACCEPTED, 1L)));

        assertConflict(() -> respond(eventDrift));

        UUID responseDriftKey = UUID.randomUUID();
        CalendarDtos.RespondRequest responseDrift = request(ACCEPTED, 4L, responseDriftKey);
        String responseFingerprint = CalendarInvitationResponseCommand.fingerprint(
                EVENT_ID, PERSON_ID, responseDrift);
        when(repository.event(TENANT_ID, ACTOR_ID, PERSON_ID, EVENT_ID, false))
                .thenReturn(Optional.of(event(TENTATIVE, 5L)));
        when(responses.receipt(TENANT_ID, ACTOR_ID, responseDriftKey)).thenReturn(Optional.of(
                new CalendarInvitationResponseRepository.ResponseReceipt(
                        PERSON_ID, EVENT_ID, responseFingerprint, 4L, 5L, ACCEPTED, 1L)));
        when(responses.lockState(TENANT_ID, ACTOR_ID, PERSON_ID, EVENT_ID))
                .thenReturn(Optional.of(new CalendarInvitationResponseRepository.LockedResponseState(
                        ATTENDEE_ID, 5L, TENTATIVE, 2L)));

        assertConflict(() -> respond(responseDrift));
        verify(repository, never()).audit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void inaccessibleOrCrossTenantEventStopsBeforeCommandRepositoryInvocation() {
        CalendarDtos.RespondRequest request = request(ACCEPTED, 0L, UUID.randomUUID());
        when(repository.event(TENANT_ID, ACTOR_ID, PERSON_ID, EVENT_ID, false))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> respond(request))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verifyNoInteractions(responses, projector, roomAccess);
        verify(repository, never()).audit(any(), any(), any(), any(), any(), any(), any());
    }

    private CalendarDtos.EventSummary respond(CalendarDtos.RespondRequest request) {
        return CalendarInvitationResponseCommand.respond(
                repository, roomAccessGuard, projector,
                TENANT_ID, ACTOR_ID, PERSON_ID, EVENT_ID,
                "en-US", "corr", null, request);
    }

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    private CalendarDtos.RespondRequest request(
            CalendarTypes.ResponseStatus response,
            long expectedVersion,
            UUID key) {
        return new CalendarDtos.RespondRequest(response, expectedVersion, key);
    }

    private CalendarRepository.EventRow event(
            CalendarTypes.ResponseStatus response,
            long version) {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-09-21T09:00:00Z");
        return new CalendarRepository.EventRow(
                EVENT_ID, UUID.randomUUID(), "Shared calendar", "#2563EB",
                99L, UUID.randomUUID(), "Organizer", "organizer@example.com",
                "Invitation", "Agenda", CalendarTypes.EventType.MEETING,
                startsAt, startsAt.plusHours(1), "UTC", false, null, null,
                CalendarTypes.EventStatus.CONFIRMED,
                CalendarTypes.EventVisibility.DEFAULT,
                CalendarTypes.RecurrencePattern.NONE, 1, null,
                true, response, null, version);
    }
}
