package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.WorkplaceRoomAccessPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarMutationFailClosedTest {

    private static final long TENANT_ID = 91L;
    private static final long REQUESTER_USER_ID = 9201L;
    private static final UUID REQUESTER_PERSON_ID =
            UUID.fromString("92000000-0000-4000-8000-000000000001");
    private static final UUID OWNER_PERSON_ID =
            UUID.fromString("92000000-0000-4000-8000-000000000002");

    @Mock
    private CalendarRepository repository;

    @Mock
    private WorkplaceRoomAccessPort roomAccess;

    @Mock
    private RoomBookingPolicyService roomBookingPolicy;

    @Mock
    private CalendarCollaborationRepository collaborationRepository;

    @Mock
    private CalendarRetentionRepository retentionRepository;

    private CalendarService service;
    private CalendarCollaborationService collaborationService;

    @BeforeEach
    void setUp() {
        service = new CalendarService(
                repository,
                roomAccess,
                new CalendarSchedulingHorizon(Clock.fixed(
                        Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC)),
                roomBookingPolicy);
        collaborationService = new CalendarCollaborationService(
                collaborationRepository, repository, retentionRepository);
    }

    @Test
    void viewerCannotCreateOrEscalateACalendarShare() {
        UUID calendarId = UUID.randomUUID();
        UUID targetPersonId = UUID.randomUUID();
        allowKnownRequester();
        when(collaborationRepository.lockCalendar(TENANT_ID, calendarId)).thenReturn(true);
        when(collaborationRepository.accessDecision(
                eq(TENANT_ID), eq(REQUESTER_USER_ID), eq(REQUESTER_PERSON_ID),
                any(UUID[].class), eq(calendarId)))
                .thenReturn(Optional.of(new CalendarCollaborationRepository.AccessDecision(
                        calendarId, "PERSONAL", "OPTIONAL", 9301L, OWNER_PERSON_ID,
                        "VIEW_DETAILS", false, 0L)));

        assertThatThrownBy(() -> collaborationService.upsertPersonShare(
                TENANT_ID,
                REQUESTER_USER_ID,
                REQUESTER_PERSON_ID,
                null,
                calendarId,
                targetPersonId,
                "corr-share-denied",
                new CalendarDtos.CalendarShareRequest(
                        targetPersonId,
                        "Target person",
                        CalendarTypes.CalendarAccessLevel.VIEW_DETAILS,
                        false,
                        null,
                        null)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(collaborationRepository, never()).upsertPersonShare(
                any(), any(), any(), any(), anyString(), anyString(),
                any(boolean.class), any(), anyLong());
        verify(repository, never()).audit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void viewerCannotTrashAnEvent() {
        UUID calendarId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        allowKnownRequester();
        when(collaborationRepository.eventCalendarId(TENANT_ID, eventId))
                .thenReturn(Optional.of(calendarId));
        when(collaborationRepository.lockCalendar(TENANT_ID, calendarId)).thenReturn(true);
        when(collaborationRepository.eventDecisionForUpdate(
                eq(TENANT_ID), eq(REQUESTER_USER_ID), eq(REQUESTER_PERSON_ID),
                any(UUID[].class), eq(eventId)))
                .thenReturn(Optional.of(new CalendarCollaborationRepository.EventDecision(
                        eventId, calendarId, "CONFIRMED", "DEFAULT", false,
                        null, null, false, 0L, false, "VIEW_DETAILS", false)));

        assertThatThrownBy(() -> collaborationService.trashEvent(
                TENANT_ID,
                REQUESTER_USER_ID,
                REQUESTER_PERSON_ID,
                null,
                eventId,
                "corr-trash-denied",
                new CalendarDtos.TrashEventRequest(0L, "Unauthorized deletion")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(collaborationRepository, never()).trashEvent(
                any(), any(), any(), anyString(), any(long.class));
        verify(repository, never()).cancelBookings(any(), any(), any());
        verify(repository, never()).audit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void freeBusyProjectionDoesNotLeakEventIdentityOrDetails() {
        UUID eventId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.parse("2026-09-01T00:00:00Z");
        OffsetDateTime to = from.plusDays(1);
        when(repository.visibleEvents(
                TENANT_ID, REQUESTER_USER_ID, REQUESTER_PERSON_ID, from, to, false))
                .thenReturn(List.of(freeBusyEvent(eventId)));

        CalendarDtos.EventSummary result = service.events(
                TENANT_ID,
                REQUESTER_USER_ID,
                REQUESTER_PERSON_ID,
                from,
                to,
                "en-US").getFirst();

        assertThat(result.eventId()).isNotEqualTo(eventId);
        assertThat(result.title()).isEqualTo("Busy");
        assertThat(result.redacted()).isTrue();
        assertThat(result.detailLevel()).isEqualTo(CalendarTypes.EventDetailLevel.FREE_BUSY);
        assertThat(result.importance()).isEqualTo(CalendarTypes.EventImportance.NORMAL);
        assertThat(result.restrictionReason()).isEqualTo("FREE_BUSY_ONLY");
        assertThat(result.organizerUserId()).isNull();
        assertThat(result.organizerPersonPublicId()).isNull();
        assertThat(result.organizerName()).isNull();
        assertThat(result.organizerEmail()).isNull();
        assertThat(result.description()).isNull();
        assertThat(result.location()).isNull();
        assertThat(result.conferenceUrl()).isNull();
        assertThat(result.attendees()).isEmpty();
        assertThat(result.capabilities().canViewDetails()).isFalse();
        assertThat(result.capabilities().canEdit()).isFalse();
        assertThat(result.capabilities().canDelete()).isFalse();
        verify(repository, never()).attendees(any(), any());
    }

    @Test
    void unauthorizedVisibleAttendeeCannotUpdateAnEvent() {
        UUID eventId = UUID.randomUUID();
        when(repository.event(
                TENANT_ID, REQUESTER_USER_ID, REQUESTER_PERSON_ID, eventId, false))
                .thenReturn(Optional.of(eventOwnedByAnotherPerson(eventId)));

        assertThatThrownBy(() -> service.update(
                TENANT_ID, REQUESTER_USER_ID, REQUESTER_PERSON_ID, eventId,
                "en-US", "corr-update-denied", null, updateRequest()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(repository, never()).updateEvent(any(), any(), any(), any(), any());
        verify(repository, never()).replaceAttendees(any(), any(), any());
        verify(repository, never()).audit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unauthorizedVisibleAttendeeCannotReachTheCurrentCancellationPath() {
        UUID eventId = UUID.randomUUID();
        when(repository.event(
                TENANT_ID, REQUESTER_USER_ID, REQUESTER_PERSON_ID, eventId, false))
                .thenReturn(Optional.of(eventOwnedByAnotherPerson(eventId)));

        assertThatThrownBy(() -> service.cancel(
                TENANT_ID, REQUESTER_USER_ID, REQUESTER_PERSON_ID, eventId,
                "en-US", "corr-cancel-denied", null,
                new CalendarDtos.VersionRequest(0L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(repository, never()).cancelEvent(any(), any(), any(), any(), any(long.class));
        verify(repository, never()).cancelBookings(any(), any(), any());
        verify(repository, never()).audit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void inaccessibleOrCrossTenantEventIdsRemainNonEnumerableBeforeMutation() {
        UUID eventId = UUID.randomUUID();
        when(repository.event(
                TENANT_ID, REQUESTER_USER_ID, REQUESTER_PERSON_ID, eventId, false))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(
                TENANT_ID, REQUESTER_USER_ID, REQUESTER_PERSON_ID, eventId,
                "en-US", "corr-hidden-update", null, updateRequest()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.cancel(
                TENANT_ID, REQUESTER_USER_ID, REQUESTER_PERSON_ID, eventId,
                "en-US", "corr-hidden-cancel", null,
                new CalendarDtos.VersionRequest(0L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(repository, never()).updateEvent(any(), any(), any(), any(), any());
        verify(repository, never()).cancelEvent(any(), any(), any(), any(), any(long.class));
        verify(repository, never()).cancelBookings(any(), any(), any());
        verify(repository, never()).audit(any(), any(), any(), any(), any(), any(), any());
    }

    private CalendarDtos.UpdateEventRequest updateRequest() {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-09-01T10:00:00Z");
        return new CalendarDtos.UpdateEventRequest(
                "Unauthorized update", "Must not be persisted",
                CalendarTypes.EventType.MEETING,
                startsAt, startsAt.plusHours(1), "UTC", false,
                null, null, CalendarTypes.EventVisibility.DEFAULT,
                CalendarTypes.RecurrencePattern.NONE, 1, null,
                false, List.of(), null, 0L);
    }

    private CalendarRepository.EventRow eventOwnedByAnotherPerson(UUID eventId) {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-09-01T10:00:00Z");
        return new CalendarRepository.EventRow(
                eventId, UUID.randomUUID(), "Shared calendar", "#2563EB",
                9301L, OWNER_PERSON_ID, "Owner", "owner@example.com",
                "Visible attendee event", "Details",
                CalendarTypes.EventType.MEETING,
                startsAt, startsAt.plusHours(1), "UTC", false,
                null, null, CalendarTypes.EventStatus.CONFIRMED,
                CalendarTypes.EventVisibility.DEFAULT,
                CalendarTypes.RecurrencePattern.NONE, 1, (LocalDate) null,
                false, CalendarTypes.ResponseStatus.ACCEPTED, null,
                CalendarTypes.EventImportance.NORMAL,
                CalendarTypes.EventDetailLevel.FULL,
                false, 0,
                CalendarTypes.CalendarAccessLevel.EVENT_ATTENDEE,
                0L);
    }

    private CalendarRepository.EventRow freeBusyEvent(UUID eventId) {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-09-01T10:00:00Z");
        return new CalendarRepository.EventRow(
                eventId, UUID.randomUUID(), "Shared calendar", "#2563EB",
                9301L, OWNER_PERSON_ID, "Secret owner", "secret-owner@example.com",
                "Confidential acquisition", "Highly confidential details",
                CalendarTypes.EventType.MEETING,
                startsAt, startsAt.plusHours(1), "UTC", false,
                "Secret location", "https://example.com/secret-call",
                CalendarTypes.EventStatus.CONFIRMED,
                CalendarTypes.EventVisibility.CONFIDENTIAL,
                CalendarTypes.RecurrencePattern.WEEKLY, 1,
                LocalDate.parse("2026-09-30"), true,
                null, null, CalendarTypes.EventImportance.HIGH,
                CalendarTypes.EventDetailLevel.FREE_BUSY,
                true, 0, CalendarTypes.CalendarAccessLevel.VIEW_FREE_BUSY, 7L);
    }

    private void allowKnownRequester() {
        when(collaborationRepository.verifiedActor(
                TENANT_ID, REQUESTER_USER_ID, REQUESTER_PERSON_ID)).thenReturn(true);
    }
}
