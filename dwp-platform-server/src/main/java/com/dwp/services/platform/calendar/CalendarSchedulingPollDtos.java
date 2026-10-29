package com.dwp.services.platform.calendar;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

final class CalendarSchedulingPollDtos {

    private CalendarSchedulingPollDtos() {
    }

    enum PollState { DRAFT, OPEN, AGREEMENT_REACHED, CONFIRMED, CANCELLED, EXPIRED }

    enum VoteChoice { YES, IF_NEEDED, NO }

    enum PollHoldKind { PEOPLE_SOFT, ROOM_HARD }

    enum PollHoldState { ACTIVE, CONSUMED, RELEASED, EXPIRED }

    record PollCandidateInput(
            @NotNull UUID candidateId,
            @NotNull OffsetDateTime startsAt,
            @NotNull OffsetDateTime endsAt,
            UUID resourceId) {
    }

    record PollParticipantInput(
            UUID personPublicId,
            @Email @Size(max = 255) String externalEmail,
            @NotBlank @Size(max = 160) String displayName,
            boolean required) {
    }

    record CreatePollRequest(
            @NotBlank @Size(max = 240) String title,
            @Size(max = 2000) String description,
            @NotBlank @Size(max = 80) String timeZone,
            @NotEmpty @Size(max = 20) List<@Valid PollCandidateInput> candidates,
            @NotEmpty @Size(max = 100) List<@Valid PollParticipantInput> participants,
            @NotNull OffsetDateTime expiresAt,
            @NotNull UUID idempotencyKey) {
    }

    record PollCandidate(
            UUID candidateId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            UUID resourceId,
            int yesCount,
            int ifNeededCount,
            int noCount,
            boolean agreementEligible) {
    }

    record PollParticipant(
            UUID participantId,
            UUID personPublicId,
            String externalEmail,
            String displayName,
            boolean required,
            boolean externalVotingEnabled) {
    }

    record PollVote(
            UUID participantId,
            UUID candidateId,
            VoteChoice choice) {
    }

    record SchedulingPoll(
            UUID pollId,
            UUID organizerPersonPublicId,
            PollState state,
            String title,
            String description,
            String timeZone,
            List<PollCandidate> candidates,
            List<PollParticipant> participants,
            List<PollVote> myVotes,
            OffsetDateTime expiresAt,
            UUID confirmedEventId,
            UUID confirmedCandidateId,
            long version,
            OffsetDateTime updatedAt) {
    }

    record ChangePollStateRequest(
            @NotNull PollState state,
            @NotNull @Min(0) Long version) {
    }

    record CastVotesRequest(
            @NotEmpty @Size(max = 20) List<@Valid VoteInput> votes,
            @Size(max = 1000) String comment,
            @NotNull UUID idempotencyKey) {
    }

    record VoteInput(
            @NotNull UUID candidateId,
            @NotNull VoteChoice choice) {
    }

    record ConfirmPollRequest(
            @NotNull UUID candidateId,
            @NotNull UUID calendarId,
            @NotNull UUID idempotencyKey,
            @NotNull @Min(0) Long version) {
    }

    record PollHold(
            UUID holdId,
            UUID pollId,
            UUID candidateId,
            PollHoldKind kind,
            PollHoldState state,
            UUID personPublicId,
            UUID resourceId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime expiresAt,
            long fencingToken) {
    }

    /** Safe, non-provider-specific availability evidence for one proposed time. */
    record PollCandidateEvidence(
            UUID candidateId,
            String availabilityState,
            String safeReasonCode,
            boolean roomHoldActive) {
    }

    /** Aggregate returned by every authenticated poll read and command endpoint. */
    record PollDetails(
            SchedulingPoll poll,
            List<PollCandidateEvidence> availability,
            List<PollHold> holds) {
    }

    /** Bounded keyset page; callers must return both cursor fields together. */
    record PollPage(
            List<PollDetails> items,
            OffsetDateTime nextUpdatedAt,
            UUID nextPollId,
            boolean hasMore) {
    }
}
