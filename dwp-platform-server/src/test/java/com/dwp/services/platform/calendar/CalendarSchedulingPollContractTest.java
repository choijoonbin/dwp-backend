package com.dwp.services.platform.calendar;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarSchedulingPollDtos.PollState.AGREEMENT_REACHED;
import static com.dwp.services.platform.calendar.CalendarSchedulingPollDtos.PollState.CONFIRMED;
import static com.dwp.services.platform.calendar.CalendarSchedulingPollDtos.PollState.DRAFT;
import static com.dwp.services.platform.calendar.CalendarSchedulingPollDtos.PollState.OPEN;
import static com.dwp.services.platform.calendar.CalendarSchedulingPollDtos.VoteChoice.IF_NEEDED;
import static com.dwp.services.platform.calendar.CalendarSchedulingPollDtos.VoteChoice.NO;
import static com.dwp.services.platform.calendar.CalendarSchedulingPollDtos.VoteChoice.YES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarSchedulingPollContractTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void validatesAStableBoundedPollContract() {
        assertThatCode(() -> CalendarSchedulingPollContract.validateCreate(valid(), CLOCK))
                .doesNotThrowAnyException();
    }

    @Test
    void agreementDoesNotConfirmAutomatically() {
        assertThatCode(() -> CalendarSchedulingPollContract.requireTransition(
                DRAFT, OPEN)).doesNotThrowAnyException();
        assertThatCode(() -> CalendarSchedulingPollContract.requireTransition(
                OPEN, AGREEMENT_REACHED)).doesNotThrowAnyException();
        assertThatCode(() -> CalendarSchedulingPollContract.requireTransition(
                AGREEMENT_REACHED, CONFIRMED)).doesNotThrowAnyException();
        assertThatThrownBy(() -> CalendarSchedulingPollContract.requireTransition(
                OPEN, CONFIRMED)).isInstanceOf(BaseException.class);
        assertThatCode(() -> CalendarSchedulingPollContract.requireTransition(
                AGREEMENT_REACHED, OPEN)).doesNotThrowAnyException();
    }

    @Test
    void everyRequiredParticipantMustVoteWithoutNoForAgreement() {
        UUID candidate = UUID.randomUUID();
        UUID requiredOne = UUID.randomUUID();
        UUID requiredTwo = UUID.randomUUID();
        UUID optional = UUID.randomUUID();
        List<CalendarSchedulingPollDtos.PollParticipant> participants = List.of(
                participant(requiredOne, true), participant(requiredTwo, true),
                participant(optional, false));

        assertThat(CalendarSchedulingPollContract.agreementCandidates(
                participants,
                List.of(
                        vote(requiredOne, candidate, YES),
                        vote(requiredTwo, candidate, IF_NEEDED),
                        vote(optional, candidate, NO)),
                Set.of(candidate))).containsExactly(candidate);

        assertThat(CalendarSchedulingPollContract.agreementCandidates(
                participants,
                List.of(vote(requiredOne, candidate, YES)),
                Set.of(candidate))).isEmpty();
    }

    @Test
    void duplicateParticipantIdentityFailsClosed() {
        CalendarSchedulingPollDtos.CreatePollRequest value = valid();
        CalendarSchedulingPollDtos.PollParticipantInput participant =
                value.participants().getFirst();
        CalendarSchedulingPollDtos.CreatePollRequest duplicate =
                new CalendarSchedulingPollDtos.CreatePollRequest(
                        value.title(), value.description(), value.timeZone(), value.candidates(),
                        List.of(participant, participant), value.expiresAt(), value.idempotencyKey());
        assertThatThrownBy(() -> CalendarSchedulingPollContract.validateCreate(duplicate, CLOCK))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void externalVotingRemainsFailClosedUntilItsVerificationBoundaryIsEnabled() {
        CalendarSchedulingPollDtos.CreatePollRequest value = valid();
        CalendarSchedulingPollDtos.CreatePollRequest external =
                new CalendarSchedulingPollDtos.CreatePollRequest(
                        value.title(), value.description(), value.timeZone(), value.candidates(),
                        List.of(new CalendarSchedulingPollDtos.PollParticipantInput(
                                null, "guest@example.com", "External guest", true)),
                        value.expiresAt(), value.idempotencyKey());

        assertThatThrownBy(() -> CalendarSchedulingPollContract.validateCreate(external, CLOCK))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("not enabled");
        assertThatCode(() -> CalendarSchedulingPollContract.validateCreate(
                external, CLOCK, true)).doesNotThrowAnyException();
    }

    @Test
    void votesMustTargetEachCandidateAtMostOnce() {
        UUID candidate = UUID.randomUUID();
        CalendarSchedulingPollDtos.CastVotesRequest duplicate =
                new CalendarSchedulingPollDtos.CastVotesRequest(
                        List.of(
                                new CalendarSchedulingPollDtos.VoteInput(candidate, YES),
                                new CalendarSchedulingPollDtos.VoteInput(candidate, NO)),
                        null, UUID.randomUUID());

        assertThatThrownBy(() -> CalendarSchedulingPollContract.validateVotes(
                duplicate, Set.of(candidate))).isInstanceOf(BaseException.class);
    }

    @Test
    void confirmationRequiresTheAgreementStateAndAnEligibleCandidate() {
        UUID agreed = UUID.randomUUID();
        assertThatCode(() -> CalendarSchedulingPollContract.requireConfirmable(
                AGREEMENT_REACHED, agreed, Set.of(agreed))).doesNotThrowAnyException();
        assertThatThrownBy(() -> CalendarSchedulingPollContract.requireConfirmable(
                OPEN, agreed, Set.of(agreed))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> CalendarSchedulingPollContract.requireConfirmable(
                AGREEMENT_REACHED, UUID.randomUUID(), Set.of(agreed)))
                .isInstanceOf(BaseException.class);
    }

    private CalendarSchedulingPollDtos.CreatePollRequest valid() {
        OffsetDateTime start = OffsetDateTime.ofInstant(
                Instant.parse("2026-10-30T01:00:00Z"), ZoneOffset.UTC);
        return new CalendarSchedulingPollDtos.CreatePollRequest(
                "Planning session", "Choose a time", "Asia/Seoul",
                List.of(new CalendarSchedulingPollDtos.PollCandidateInput(
                        UUID.randomUUID(), start, start.plusMinutes(30), null)),
                List.of(new CalendarSchedulingPollDtos.PollParticipantInput(
                        UUID.randomUUID(), null, "Minseo Kim", true)),
                start.minusHours(1), UUID.randomUUID());
    }

    private CalendarSchedulingPollDtos.PollParticipant participant(UUID id, boolean required) {
        return new CalendarSchedulingPollDtos.PollParticipant(
                id, UUID.randomUUID(), null, "Participant", required, false);
    }

    private CalendarSchedulingPollDtos.PollVote vote(
            UUID participant, UUID candidate, CalendarSchedulingPollDtos.VoteChoice choice) {
        return new CalendarSchedulingPollDtos.PollVote(participant, candidate, choice);
    }
}
