package com.dwp.services.platform.calendar;

import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.workplace.WorkplaceRoomAccessPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarSchedulingPollDtos.*;
import static com.dwp.services.platform.calendar.CalendarTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CalendarSchedulingPollServiceTest {

    private static final Instant FIXED = Instant.parse("2026-10-28T01:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
    private static final UUID PERSON = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");

    @Mock private CalendarSchedulingPollRepository polls;
    @Mock private CalendarRepository calendar;
    @Mock private CalendarService calendarService;
    @Mock private WorkplaceRoomAccessPort roomAccess;
    @Mock private PlatformAuditService audit;
    @Mock private CalendarSchedulingPollMetrics metrics;

    private CalendarSchedulingPollService service;
    private CalendarSchedulingPollAccess.Actor actor;

    @BeforeEach
    void setUp() {
        actor = new CalendarSchedulingPollAccess.Actor(
                41L, 77L, PERSON, null, Set.of(), "corr-1", "en", false);
        service = new CalendarSchedulingPollService(
                polls, calendar, calendarService, roomAccess, audit, metrics,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(FIXED, ZoneOffset.UTC), Duration.ofMinutes(15),
                Duration.ofDays(365));
        lenient().when(polls.identityMatches(41L, 77L, PERSON)).thenReturn(true);
    }

    @Test
    void requiredYesVoteAdvancesOpenPollToAgreementAndVersionsTheAggregate() {
        UUID pollId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        CalendarSchedulingPollRepository.PollRow before = poll(
                pollId, PollState.OPEN, 3L, null, null);
        CalendarSchedulingPollRepository.PollRow after = poll(
                pollId, PollState.AGREEMENT_REACHED, 4L, null, null);
        CalendarSchedulingPollRepository.CandidateRow candidate = candidate(
                candidateId, null, "AVAILABLE", null);
        CalendarSchedulingPollRepository.ParticipantRow participant =
                participant(participantId, true);
        CalendarSchedulingPollRepository.VoteRow stored =
                new CalendarSchedulingPollRepository.VoteRow(
                        participantId, candidateId, VoteChoice.YES, null, 0L);
        arrangeVisible(before, participant);
        when(polls.candidates(41L, pollId)).thenReturn(List.of(candidate));
        when(polls.participants(41L, pollId)).thenReturn(List.of(participant));
        when(polls.votes(41L, pollId)).thenReturn(List.of(stored));
        when(polls.holds(41L, pollId)).thenReturn(List.of());
        when(polls.transition(
                41L, pollId, PollState.OPEN, PollState.AGREEMENT_REACHED,
                3L, 77L, null, NOW)).thenReturn(1);
        when(polls.poll(41L, pollId)).thenReturn(Optional.of(after));

        PollDetails result = service.vote(actor, pollId, new CastVotesRequest(
                List.of(new VoteInput(candidateId, VoteChoice.YES)),
                null, UUID.randomUUID()));

        assertThat(result.poll().state()).isEqualTo(PollState.AGREEMENT_REACHED);
        assertThat(result.poll().version()).isEqualTo(4L);
        assertThat(result.poll().candidates().getFirst().agreementEligible()).isTrue();
        verify(polls).insertReceipt(
                eq(41L), eq(77L), eq(PERSON), any(), eq(pollId), eq("VOTE"),
                anyString(), eq(PollState.AGREEMENT_REACHED), eq(4L), isNull(), eq(NOW));
        verify(polls, never()).insertRoomHold(
                any(), anyLong(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void roomConflictKeepsPollOpenAndReturnsSafeUnavailableEvidence() {
        UUID pollId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        CalendarSchedulingPollRepository.PollRow before = poll(
                pollId, PollState.OPEN, 6L, null, null);
        CalendarSchedulingPollRepository.PollRow after = poll(
                pollId, PollState.OPEN, 7L, null, null);
        CalendarSchedulingPollRepository.CandidateRow available = candidate(
                candidateId, resourceId, "AVAILABLE", null);
        CalendarSchedulingPollRepository.CandidateRow unavailable = candidate(
                candidateId, resourceId, "ROOM_UNAVAILABLE", "ACTIVE_ROOM_CONFLICT");
        CalendarSchedulingPollRepository.ParticipantRow participant =
                participant(participantId, true);
        CalendarSchedulingPollRepository.VoteRow stored =
                new CalendarSchedulingPollRepository.VoteRow(
                        participantId, candidateId, VoteChoice.IF_NEEDED, null, 0L);
        arrangeVisible(before, participant);
        when(polls.candidates(41L, pollId))
                .thenReturn(List.of(available), List.of(unavailable));
        when(polls.participants(41L, pollId)).thenReturn(List.of(participant));
        when(polls.votes(41L, pollId)).thenReturn(List.of(stored));
        when(polls.holds(41L, pollId)).thenReturn(List.of());
        when(calendar.resource(41L, resourceId, false)).thenReturn(Optional.of(room(resourceId)));
        when(polls.activeResourceBookingConflict(
                41L, resourceId, available.startsAt(), available.endsAt())).thenReturn(true);
        when(polls.advanceVersion(
                41L, pollId, PollState.OPEN, 6L, 77L, NOW)).thenReturn(1);
        when(polls.poll(41L, pollId)).thenReturn(Optional.of(after));

        PollDetails result = service.vote(actor, pollId, new CastVotesRequest(
                List.of(new VoteInput(candidateId, VoteChoice.IF_NEEDED)),
                "works if needed", UUID.randomUUID()));

        assertThat(result.poll().state()).isEqualTo(PollState.OPEN);
        assertThat(result.availability()).singleElement().satisfies(evidence -> {
            assertThat(evidence.availabilityState()).isEqualTo("ROOM_UNAVAILABLE");
            assertThat(evidence.safeReasonCode()).isEqualTo("ACTIVE_ROOM_CONFLICT");
            assertThat(evidence.roomHoldActive()).isFalse();
        });
        verify(polls).markCandidateAvailability(
                41L, pollId, candidateId, "ROOM_UNAVAILABLE",
                "ACTIVE_ROOM_CONFLICT", NOW);
        verify(polls, never()).insertRoomHold(
                any(), anyLong(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void confirmationConsumesEvidenceThenCreatesEventAndConfirmsPoll() {
        UUID pollId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        UUID calendarId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        CalendarSchedulingPollRepository.PollRow before = poll(
                pollId, PollState.AGREEMENT_REACHED, 8L, null, null);
        CalendarSchedulingPollRepository.PollRow after = poll(
                pollId, PollState.CONFIRMED, 9L, candidateId, eventId);
        CalendarSchedulingPollRepository.CandidateRow candidate = candidate(
                candidateId, null, "AVAILABLE", null);
        CalendarSchedulingPollRepository.ParticipantRow participant =
                participant(participantId, true);
        CalendarSchedulingPollRepository.VoteRow vote =
                new CalendarSchedulingPollRepository.VoteRow(
                        participantId, candidateId, VoteChoice.YES, null, 0L);
        arrangeVisible(before, participant);
        when(polls.candidates(41L, pollId)).thenReturn(List.of(candidate));
        when(polls.participants(41L, pollId)).thenReturn(List.of(participant));
        when(polls.votes(41L, pollId)).thenReturn(List.of(vote));
        when(polls.holds(41L, pollId)).thenReturn(List.of());
        when(polls.activePeopleHoldCount(41L, pollId, candidateId, NOW)).thenReturn(1);
        when(polls.identityProfile(41L, 77L, PERSON)).thenReturn(Optional.of(
                new CalendarSchedulingPollRepository.IdentityProfile(
                        77L, PERSON, "owner@example.com", "Owner")));
        CalendarDtos.EventSummary event = mock(CalendarDtos.EventSummary.class);
        when(event.eventId()).thenReturn(eventId);
        when(calendarService.create(
                eq(41L), eq(77L), eq(PERSON), eq("Owner"), eq("en"), eq("corr-1"),
                isNull(), any(CalendarDtos.CreateEventRequest.class))).thenReturn(event);
        when(polls.confirm(
                41L, pollId, 8L, candidateId, eventId, 77L,
                NOW.plusDays(365), NOW)).thenReturn(1);
        when(polls.poll(41L, pollId)).thenReturn(Optional.of(after));

        PollDetails result = service.confirm(actor, pollId, new ConfirmPollRequest(
                candidateId, calendarId, UUID.randomUUID(), 8L));

        assertThat(result.poll().state()).isEqualTo(PollState.CONFIRMED);
        assertThat(result.poll().confirmedEventId()).isEqualTo(eventId);
        ArgumentCaptor<CalendarDtos.CreateEventRequest> request =
                ArgumentCaptor.forClass(CalendarDtos.CreateEventRequest.class);
        verify(calendarService).create(
                eq(41L), eq(77L), eq(PERSON), eq("Owner"), eq("en"), eq("corr-1"),
                isNull(), request.capture());
        assertThat(request.getValue().calendarId()).isEqualTo(calendarId);
        assertThat(request.getValue().attendees()).singleElement().satisfies(attendee -> {
            assertThat(attendee.personPublicId()).isEqualTo(PERSON);
            assertThat(attendee.type()).isEqualTo(AttendeeType.REQUIRED);
        });
        InOrder order = inOrder(polls, calendarService);
        order.verify(polls).consumeCandidateHolds(41L, pollId, candidateId, 77L, NOW);
        order.verify(calendarService).create(
                eq(41L), eq(77L), eq(PERSON), eq("Owner"), eq("en"), eq("corr-1"),
                isNull(), any(CalendarDtos.CreateEventRequest.class));
        order.verify(polls).confirm(
                41L, pollId, 8L, candidateId, eventId, 77L,
                NOW.plusDays(365), NOW);
    }

    private void arrangeVisible(
            CalendarSchedulingPollRepository.PollRow row,
            CalendarSchedulingPollRepository.ParticipantRow participant) {
        when(polls.receipt(eq(41L), eq(77L), any())).thenReturn(Optional.empty());
        when(polls.lockPoll(41L, row.pollId())).thenReturn(Optional.of(row));
        when(polls.participant(41L, row.pollId(), 77L, PERSON))
                .thenReturn(Optional.of(participant));
    }

    private CalendarSchedulingPollRepository.PollRow poll(
            UUID pollId,
            PollState state,
            long version,
            UUID confirmedCandidateId,
            UUID confirmedEventId) {
        return new CalendarSchedulingPollRepository.PollRow(
                41L, pollId, 77L, PERSON, state, "Planning", "Agenda", "UTC",
                NOW.plusDays(1), confirmedCandidateId, confirmedEventId,
                UUID.randomUUID(), "a".repeat(64),
                state == PollState.CONFIRMED ? NOW.plusDays(365) : null,
                false, version, NOW);
    }

    private CalendarSchedulingPollRepository.CandidateRow candidate(
            UUID candidateId,
            UUID resourceId,
            String availability,
            String reason) {
        return new CalendarSchedulingPollRepository.CandidateRow(
                candidateId, 1, NOW.plusDays(2), NOW.plusDays(2).plusHours(1),
                resourceId, availability, reason, 0L, NOW);
    }

    private CalendarSchedulingPollRepository.ParticipantRow participant(
            UUID participantId, boolean required) {
        return new CalendarSchedulingPollRepository.ParticipantRow(
                participantId, 77L, PERSON, null, "Owner", required, false);
    }

    private CalendarRepository.ResourceRow room(UUID resourceId) {
        return new CalendarRepository.ResourceRow(
                resourceId, "ROOM-1", "Room", "회의실", "Room",
                ResourceType.ROOM, "SEOUL", "10", 8, List.of(), "UTC",
                false, ResourceState.AVAILABLE, true, 1L);
    }
}
