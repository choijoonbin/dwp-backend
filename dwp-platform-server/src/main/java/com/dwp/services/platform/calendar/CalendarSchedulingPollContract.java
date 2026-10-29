package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class CalendarSchedulingPollContract {

    private static final Map<CalendarSchedulingPollDtos.PollState,
            Set<CalendarSchedulingPollDtos.PollState>> TRANSITIONS = Map.of(
            CalendarSchedulingPollDtos.PollState.DRAFT,
            EnumSet.of(CalendarSchedulingPollDtos.PollState.OPEN,
                    CalendarSchedulingPollDtos.PollState.CANCELLED),
            CalendarSchedulingPollDtos.PollState.OPEN,
            EnumSet.of(CalendarSchedulingPollDtos.PollState.AGREEMENT_REACHED,
                    CalendarSchedulingPollDtos.PollState.CANCELLED,
                    CalendarSchedulingPollDtos.PollState.EXPIRED),
            CalendarSchedulingPollDtos.PollState.AGREEMENT_REACHED,
            EnumSet.of(CalendarSchedulingPollDtos.PollState.OPEN,
                    CalendarSchedulingPollDtos.PollState.CONFIRMED,
                    CalendarSchedulingPollDtos.PollState.CANCELLED,
                    CalendarSchedulingPollDtos.PollState.EXPIRED));

    private CalendarSchedulingPollContract() {
    }

    static CalendarSchedulingPollDtos.CreatePollRequest validateCreate(
            CalendarSchedulingPollDtos.CreatePollRequest request,
            Clock clock) {
        return validateCreate(request, clock, false);
    }

    static CalendarSchedulingPollDtos.CreatePollRequest validateCreate(
            CalendarSchedulingPollDtos.CreatePollRequest request,
            Clock clock,
            boolean externalVotingEnabled) {
        if (request == null || request.title() == null || request.timeZone() == null
                || request.candidates() == null || request.participants() == null
                || request.expiresAt() == null || request.idempotencyKey() == null) {
            throw invalid("All required Calendar scheduling poll values must be present.");
        }
        canonicalZone(request.timeZone());
        if (request.title().isBlank() || request.title().trim().length() > 240
                || !request.title().equals(request.title().trim())
                || request.candidates().isEmpty() || request.candidates().size() > 20
                || request.participants().isEmpty() || request.participants().size() > 100) {
            throw invalid("The Calendar scheduling poll size is outside its supported bound.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!request.expiresAt().isAfter(now)
                || request.expiresAt().isAfter(now.plusDays(90))) {
            throw invalid("A Calendar scheduling poll must expire within ninety days.");
        }
        validateCandidates(request.candidates(), now);
        OffsetDateTime firstCandidate = request.candidates().stream()
                .map(CalendarSchedulingPollDtos.PollCandidateInput::startsAt)
                .min(OffsetDateTime::compareTo)
                .orElseThrow();
        if (request.expiresAt().isAfter(firstCandidate)) {
            throw invalid("A Calendar scheduling poll must close before its first candidate.");
        }
        validateParticipants(request.participants(), externalVotingEnabled);
        return request;
    }

    static void requireTransition(
            CalendarSchedulingPollDtos.PollState from,
            CalendarSchedulingPollDtos.PollState to) {
        if (from == null || to == null
                || !TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The Calendar scheduling poll lifecycle transition is not allowed.");
        }
    }

    /** A candidate is eligible only after every required participant has cast a non-NO vote. */
    static Set<UUID> agreementCandidates(
            List<CalendarSchedulingPollDtos.PollParticipant> participants,
            List<CalendarSchedulingPollDtos.PollVote> votes,
            Set<UUID> candidateIds) {
        Set<UUID> required = new HashSet<>();
        participants.stream().filter(CalendarSchedulingPollDtos.PollParticipant::required)
                .forEach(value -> required.add(value.participantId()));
        Set<UUID> eligible = new HashSet<>();
        for (UUID candidateId : candidateIds) {
            Map<UUID, CalendarSchedulingPollDtos.VoteChoice> choices = new java.util.HashMap<>();
            votes.stream().filter(value -> value.candidateId().equals(candidateId))
                    .forEach(value -> choices.put(value.participantId(), value.choice()));
            if (required.stream().allMatch(participantId -> {
                CalendarSchedulingPollDtos.VoteChoice choice = choices.get(participantId);
                return choice == CalendarSchedulingPollDtos.VoteChoice.YES
                        || choice == CalendarSchedulingPollDtos.VoteChoice.IF_NEEDED;
            })) {
                eligible.add(candidateId);
            }
        }
        return Set.copyOf(eligible);
    }

    static CalendarSchedulingPollDtos.CastVotesRequest validateVotes(
            CalendarSchedulingPollDtos.CastVotesRequest request,
            Set<UUID> candidateIds) {
        if (request == null || request.votes() == null || request.idempotencyKey() == null
                || candidateIds == null || request.votes().isEmpty()
                || request.votes().size() > 20) {
            throw invalid("Calendar scheduling poll votes are incomplete.");
        }
        Set<UUID> seen = new HashSet<>();
        for (CalendarSchedulingPollDtos.VoteInput vote : request.votes()) {
            if (vote == null || vote.candidateId() == null || vote.choice() == null
                    || !candidateIds.contains(vote.candidateId())
                    || !seen.add(vote.candidateId())) {
                throw invalid("Calendar scheduling poll votes must target unique candidates.");
            }
        }
        if (request.comment() != null && request.comment().length() > 1000) {
            throw invalid("A Calendar scheduling poll vote comment is too long.");
        }
        return request;
    }

    static void requireConfirmable(
            CalendarSchedulingPollDtos.PollState state,
            UUID candidateId,
            Set<UUID> agreementCandidates) {
        if (state != CalendarSchedulingPollDtos.PollState.AGREEMENT_REACHED
                || candidateId == null || agreementCandidates == null
                || !agreementCandidates.contains(candidateId)) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Only an agreed Calendar scheduling poll candidate can be confirmed.");
        }
    }

    private static void validateCandidates(
            List<CalendarSchedulingPollDtos.PollCandidateInput> candidates,
            OffsetDateTime now) {
        Set<UUID> ids = new HashSet<>();
        Set<String> ranges = new HashSet<>();
        for (CalendarSchedulingPollDtos.PollCandidateInput candidate : candidates) {
            if (candidate == null || candidate.candidateId() == null
                    || candidate.startsAt() == null || candidate.endsAt() == null
                    || !ids.add(candidate.candidateId())
                    || !candidate.endsAt().isAfter(candidate.startsAt())
                    || candidate.startsAt().isBefore(now)
                    || candidate.startsAt().isAfter(now.plusDays(365))
                    || Duration.between(candidate.startsAt(), candidate.endsAt()).toMinutes() < 5
                    || Duration.between(candidate.startsAt(), candidate.endsAt()).toMinutes() > 480
                    || !ranges.add(candidate.startsAt().toInstant() + "/"
                    + candidate.endsAt().toInstant())) {
                throw invalid("Calendar poll candidates must be unique valid future ranges.");
            }
        }
    }

    private static void validateParticipants(
            List<CalendarSchedulingPollDtos.PollParticipantInput> participants,
            boolean externalVotingEnabled) {
        Set<String> identities = new HashSet<>();
        boolean required = false;
        for (CalendarSchedulingPollDtos.PollParticipantInput participant : participants) {
            boolean internal = participant != null && participant.personPublicId() != null;
            boolean external = participant != null && participant.externalEmail() != null
                    && !participant.externalEmail().isBlank();
            String identity = internal ? "person:" + participant.personPublicId()
                    : external ? "email:" + participant.externalEmail().trim().toLowerCase() : "";
            if (participant == null || internal == external || participant.displayName() == null
                    || participant.displayName().isBlank()
                    || !participant.displayName().equals(participant.displayName().trim())
                    || participant.displayName().length() > 160
                    || !identities.add(identity)) {
                throw invalid("Calendar poll participants require one unique verified identity.");
            }
            if (external && !externalVotingEnabled) {
                throw new BaseException(
                        ErrorCode.INVALID_STATE,
                        "External Calendar poll voting is not enabled.");
            }
            required |= participant.required();
        }
        if (!required) {
            throw invalid("A Calendar scheduling poll requires at least one required participant.");
        }
    }

    private static ZoneId canonicalZone(String value) {
        try {
            if (value.isBlank() || !value.equals(value.trim())) throw new DateTimeException("blank");
            ZoneId zone = ZoneId.of(value);
            if (!zone.getId().equals(value)) throw new DateTimeException("non-canonical");
            return zone;
        } catch (DateTimeException exception) {
            throw invalid("The Calendar scheduling poll time zone must be canonical IANA data.");
        }
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
