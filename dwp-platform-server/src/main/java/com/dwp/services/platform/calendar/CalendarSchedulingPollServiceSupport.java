package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.workplace.WorkplaceRoomAccessPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.dwp.services.platform.calendar.CalendarSchedulingPollDtos.*;
import static com.dwp.services.platform.calendar.CalendarTypes.*;

/** Shared access, expiry, room-hold, mapping, and request-integrity support for poll commands. */
abstract class CalendarSchedulingPollServiceSupport {

    static final String TARGET = "CALENDAR_SCHEDULING_POLL";
    static final String ROOM_CONFLICT = "ACTIVE_ROOM_CONFLICT";
    static final String ROOM_CLOSED = "ROOM_NOT_BOOKABLE";
    static final String HOLD_EXPIRED = "ROOM_HOLD_EXPIRED";

    final CalendarSchedulingPollRepository polls;
    final CalendarRepository calendar;
    final CalendarService calendarService;
    final WorkplaceRoomAccessPort roomAccess;
    final PlatformAuditService audit;
    final CalendarSchedulingPollMetrics metrics;
    final ObjectMapper json;
    final Clock clock;
    final Duration roomHoldTtl;
    final Duration retention;

    CalendarSchedulingPollServiceSupport(
            CalendarSchedulingPollRepository polls,
            CalendarRepository calendar,
            CalendarService calendarService,
            WorkplaceRoomAccessPort roomAccess,
            PlatformAuditService audit,
            CalendarSchedulingPollMetrics metrics,
            ObjectMapper json,
            Clock clock,
            Duration roomHoldTtl,
            Duration retention) {
        if (roomHoldTtl == null || roomHoldTtl.compareTo(Duration.ofSeconds(30)) < 0
                || roomHoldTtl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException(
                    "Calendar poll room-hold TTL must be between 30 and 3600 seconds.");
        }
        if (retention == null || retention.compareTo(Duration.ofDays(1)) < 0
                || retention.compareTo(Duration.ofDays(3650)) > 0) {
            throw new IllegalArgumentException(
                    "Calendar poll retention must be between 1 and 3650 days.");
        }
        this.polls = Objects.requireNonNull(polls);
        this.calendar = Objects.requireNonNull(calendar);
        this.calendarService = Objects.requireNonNull(calendarService);
        this.roomAccess = Objects.requireNonNull(roomAccess);
        this.audit = Objects.requireNonNull(audit);
        this.metrics = Objects.requireNonNull(metrics);
        this.json = Objects.requireNonNull(json);
        this.clock = Objects.requireNonNull(clock);
        this.roomHoldTtl = roomHoldTtl;
        this.retention = retention;
    }

    Set<UUID> reconcileRoomHolds(
            CalendarSchedulingPollAccess.Actor actor,
            CalendarSchedulingPollRepository.PollRow poll,
            List<CalendarSchedulingPollRepository.CandidateRow> candidates,
            Set<UUID> voteEligible,
            OffsetDateTime now) {
        Set<UUID> result = new LinkedHashSet<>();
        for (CalendarSchedulingPollRepository.CandidateRow candidate : candidates) {
            if (!voteEligible.contains(candidate.candidateId())) {
                polls.releaseRoomHold(actor.tenantId(), poll.pollId(),
                        candidate.candidateId(), actor.userId(), now);
                continue;
            }
            if (candidate.resourceId() == null) {
                polls.markCandidateAvailability(actor.tenantId(), poll.pollId(),
                        candidate.candidateId(), "AVAILABLE", null, now);
                result.add(candidate.candidateId());
                continue;
            }
            polls.lockRoom(actor.tenantId(), candidate.resourceId());
            polls.expireRoomHolds(
                    actor.tenantId(), candidate.resourceId(), now, actor.userId());
            String unavailable = roomUnavailabilityReason(
                    actor.tenantId(), poll.pollId(), candidate, now);
            if (unavailable != null) {
                polls.releaseRoomHold(actor.tenantId(), poll.pollId(),
                        candidate.candidateId(), actor.userId(), now);
                polls.markCandidateAvailability(actor.tenantId(), poll.pollId(),
                        candidate.candidateId(), "ROOM_UNAVAILABLE", unavailable, now);
                metrics.roomUnavailable();
                continue;
            }
            if (!polls.activeRoomHold(
                    actor.tenantId(), poll.pollId(), candidate.candidateId(), now)) {
                OffsetDateTime expiresAt = minimum(now.plus(roomHoldTtl), poll.expiresAt());
                polls.insertRoomHold(UUID.randomUUID(), actor.tenantId(), poll.pollId(),
                        candidate, expiresAt, actor.userId(), now);
            }
            polls.markCandidateAvailability(actor.tenantId(), poll.pollId(),
                    candidate.candidateId(), "AVAILABLE", null, now);
            result.add(candidate.candidateId());
        }
        return Set.copyOf(result);
    }

    String roomUnavailabilityReason(
            long tenantId,
            UUID pollId,
            CalendarSchedulingPollRepository.CandidateRow candidate,
            OffsetDateTime now) {
        CalendarRepository.ResourceRow resource = calendar.resource(
                        tenantId, candidate.resourceId(), false)
                .orElse(null);
        if (resource == null || resource.type() != ResourceType.ROOM
                || resource.state() != ResourceState.AVAILABLE
                || (calendar.isWorkplaceManagedResource(tenantId, candidate.resourceId())
                    && !calendar.isWorkplaceResourceBookable(
                            tenantId, candidate.resourceId()))
                || calendar.facilityClosureConflict(
                        tenantId, candidate.resourceId(),
                        candidate.startsAt(), candidate.endsAt())) {
            return ROOM_CLOSED;
        }
        if (polls.activeResourceBookingConflict(
                        tenantId, candidate.resourceId(),
                        candidate.startsAt(), candidate.endsAt())
                || polls.activeRoomHoldConflict(
                        tenantId, pollId, candidate.candidateId(),
                        candidate.resourceId(), candidate.startsAt(), candidate.endsAt(), now)) {
            return ROOM_CONFLICT;
        }
        return null;
    }

    PollDetails reopenAfterRoomFailure(
            CalendarSchedulingPollAccess.Actor actor,
            CalendarSchedulingPollRepository.PollRow before,
            CalendarSchedulingPollRepository.CandidateRow candidate,
            String availabilityState,
            String reason,
            OffsetDateTime now) {
        polls.markCandidateAvailability(
                actor.tenantId(), before.pollId(), candidate.candidateId(),
                availabilityState, reason, now);
        polls.releaseRoomHolds(
                actor.tenantId(), before.pollId(), actor.userId(), now);
        if (polls.transition(
                actor.tenantId(), before.pollId(), before.state(), PollState.OPEN,
                before.version(), actor.userId(), null, now) != 1) {
            throw changed(actor);
        }
        polls.audit(
                actor.tenantId(), before.pollId(), actor.userId(),
                "calendar.poll.room.unavailable",
                Map.of("state", before.state().name()),
                Map.of("state", PollState.OPEN.name(), "reason", reason),
                actor.correlationId(), now);
        metrics.roomUnavailable();
        return details(actor, requiredPoll(actor, before.pollId()), now);
    }

    PollDetails details(
            CalendarSchedulingPollAccess.Actor actor,
            CalendarSchedulingPollRepository.PollRow poll,
            OffsetDateTime now) {
        List<CalendarSchedulingPollRepository.CandidateRow> candidates = polls.candidates(
                actor.tenantId(), poll.pollId());
        List<CalendarSchedulingPollRepository.ParticipantRow> participants = polls.participants(
                actor.tenantId(), poll.pollId());
        List<CalendarSchedulingPollRepository.VoteRow> votes = polls.votes(
                actor.tenantId(), poll.pollId());
        List<CalendarSchedulingPollRepository.HoldRow> holds = polls.holds(
                actor.tenantId(), poll.pollId());
        Set<UUID> candidateIds = candidates.stream()
                .map(CalendarSchedulingPollRepository.CandidateRow::candidateId)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> voteEligible = CalendarSchedulingPollContract.agreementCandidates(
                participantDtos(participants), voteDtos(votes), candidateIds);
        Set<UUID> activeRoomHolds = holds.stream()
                .filter(value -> value.kind() == PollHoldKind.ROOM_HARD
                        && value.state() == PollHoldState.ACTIVE
                        && value.expiresAt().isAfter(now))
                .map(CalendarSchedulingPollRepository.HoldRow::candidateId)
                .collect(Collectors.toUnmodifiableSet());
        Map<UUID, List<CalendarSchedulingPollRepository.VoteRow>> votesByCandidate =
                votes.stream().collect(Collectors.groupingBy(
                        CalendarSchedulingPollRepository.VoteRow::candidateId));
        List<PollCandidate> candidateDtos = candidates.stream().map(candidate -> {
            List<CalendarSchedulingPollRepository.VoteRow> candidateVotes =
                    votesByCandidate.getOrDefault(candidate.candidateId(), List.of());
            boolean roomReady = candidate.resourceId() == null
                    || activeRoomHolds.contains(candidate.candidateId())
                    || (poll.state() == PollState.CONFIRMED
                        && candidate.candidateId().equals(poll.confirmedCandidateId()));
            return new PollCandidate(
                    candidate.candidateId(), candidate.startsAt(), candidate.endsAt(),
                    candidate.resourceId(), count(candidateVotes, VoteChoice.YES),
                    count(candidateVotes, VoteChoice.IF_NEEDED),
                    count(candidateVotes, VoteChoice.NO),
                    voteEligible.contains(candidate.candidateId())
                            && "AVAILABLE".equals(candidate.availabilityState()) && roomReady);
        }).toList();
        CalendarSchedulingPollRepository.ParticipantRow mine = participants.stream()
                .filter(value -> Objects.equals(value.userId(), actor.userId())
                        && Objects.equals(value.personPublicId(), actor.personPublicId()))
                .findFirst().orElse(null);
        List<PollVote> myVotes = mine == null ? List.of() : votes.stream()
                .filter(value -> value.participantId().equals(mine.participantId()))
                .map(value -> new PollVote(
                        value.participantId(), value.candidateId(), value.choice()))
                .toList();
        SchedulingPoll schedulingPoll = new SchedulingPoll(
                poll.pollId(), poll.organizerPersonPublicId(), poll.state(), poll.title(),
                poll.description(), poll.timeZone(), candidateDtos,
                participantDtos(participants), myVotes, poll.expiresAt(),
                poll.confirmedEventId(), poll.confirmedCandidateId(),
                poll.version(), poll.updatedAt());
        List<PollCandidateEvidence> availability = candidates.stream()
                .map(value -> new PollCandidateEvidence(
                        value.candidateId(), value.availabilityState(),
                        value.availabilityReason(),
                        activeRoomHolds.contains(value.candidateId())))
                .toList();
        List<PollHold> holdDtos = holds.stream()
                .map(value -> new PollHold(
                        value.holdId(), poll.pollId(), value.candidateId(), value.kind(),
                        value.state(), value.personPublicId(), value.resourceId(),
                        value.startsAt(), value.endsAt(), value.expiresAt(),
                        value.fencingToken()))
                .toList();
        return new PollDetails(schedulingPoll, availability, holdDtos);
    }

    CalendarSchedulingPollRepository.PollRow expireIfDue(
            CalendarSchedulingPollAccess.Actor actor,
            CalendarSchedulingPollRepository.PollRow value,
            OffsetDateTime now) {
        if (value.expiresAt().isAfter(now)
                || (value.state() != PollState.DRAFT
                    && value.state() != PollState.OPEN
                    && value.state() != PollState.AGREEMENT_REACHED)) {
            return value;
        }
        CalendarSchedulingPollRepository.PollRow locked = polls.lockPoll(
                        actor.tenantId(), value.pollId())
                .orElseThrow(() -> notFound(actor));
        requireVisible(actor, locked);
        if (locked.expiresAt().isAfter(now)
                || (locked.state() != PollState.DRAFT
                    && locked.state() != PollState.OPEN
                    && locked.state() != PollState.AGREEMENT_REACHED)) {
            return locked;
        }
        polls.releaseHolds(actor.tenantId(), locked.pollId(), actor.userId(), now);
        if (polls.transition(actor.tenantId(), locked.pollId(), locked.state(),
                PollState.EXPIRED, locked.version(), actor.userId(),
                now.plus(retention), now) != 1) {
            throw changed(actor);
        }
        polls.audit(actor.tenantId(), locked.pollId(), actor.userId(),
                "calendar.poll.expired", Map.of("state", locked.state().name()),
                Map.of("state", "EXPIRED"), actor.correlationId(), now);
        metrics.expired(1);
        return requiredPoll(actor, locked.pollId());
    }

    CalendarSchedulingPollRepository.PollRow visibleLocked(
            CalendarSchedulingPollAccess.Actor actor, UUID pollId) {
        CalendarSchedulingPollRepository.PollRow row = polls.lockPoll(
                        actor.tenantId(), requireId(actor, pollId))
                .orElseThrow(() -> notFound(actor));
        requireVisible(actor, row);
        return row;
    }

    CalendarSchedulingPollRepository.PollRow organizerLocked(
            CalendarSchedulingPollAccess.Actor actor, UUID pollId) {
        CalendarSchedulingPollRepository.PollRow row = visibleLocked(actor, pollId);
        if (row.organizerUserId() != actor.userId()
                || !row.organizerPersonPublicId().equals(actor.personPublicId())) {
            throw notFound(actor);
        }
        return row;
    }

    void requireVisible(
            CalendarSchedulingPollAccess.Actor actor,
            CalendarSchedulingPollRepository.PollRow row) {
        boolean organizer = row.organizerUserId() == actor.userId()
                && row.organizerPersonPublicId().equals(actor.personPublicId());
        boolean participant = polls.participant(
                actor.tenantId(), row.pollId(), actor.userId(), actor.personPublicId())
                .isPresent();
        if (!organizer && !participant) throw notFound(actor);
    }

    CalendarSchedulingPollRepository.PollRow requiredPoll(
            CalendarSchedulingPollAccess.Actor actor, UUID pollId) {
        return polls.poll(actor.tenantId(), pollId).orElseThrow(() -> notFound(actor));
    }

    void requireIdentity(CalendarSchedulingPollAccess.Actor actor) {
        if (actor == null || !polls.identityMatches(
                actor.tenantId(), actor.userId(), actor.personPublicId())) {
            throw CalendarSchedulingPollAccess.denied(actor != null && actor.korean());
        }
    }

    CalendarSchedulingPollRepository.IdentityProfile requireProfile(
            CalendarSchedulingPollAccess.Actor actor, long userId, UUID personPublicId) {
        return polls.identityProfile(actor.tenantId(), userId, personPublicId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.INVALID_STATE,
                        message(actor, "활성 개인 메일 프로필이 필요합니다.",
                                "An active personal mail profile is required.")));
    }

    void validateResources(
            CalendarSchedulingPollAccess.Actor actor,
            List<PollCandidateInput> candidates) {
        Set<UUID> resourceIds = candidates.stream().map(PollCandidateInput::resourceId)
                .filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
        for (UUID resourceId : resourceIds) {
            CalendarRepository.ResourceRow resource = calendar.resource(
                            actor.tenantId(), resourceId, actor.korean())
                    .orElseThrow(() -> notFound(actor));
            if (resource.type() != ResourceType.ROOM
                    || resource.state() != ResourceState.AVAILABLE
                    || (calendar.isWorkplaceManagedResource(actor.tenantId(), resourceId)
                        && !calendar.isWorkplaceResourceBookable(
                                actor.tenantId(), resourceId))) {
                throw conflict(actor, "선택한 회의실은 예약할 수 없습니다.",
                        "The selected Calendar poll room is not bookable.");
            }
            roomAccess.requireBook(
                    actor.tenantId(), actor.userId(), actor.groupRefs(), resourceId);
        }
    }

    List<CalendarDtos.AttendeeInput> attendeeInputs(
            CalendarSchedulingPollAccess.Actor actor,
            List<CalendarSchedulingPollRepository.ParticipantRow> participants) {
        List<CalendarDtos.AttendeeInput> result = new ArrayList<>();
        for (CalendarSchedulingPollRepository.ParticipantRow participant : participants) {
            if (participant.userId() == null || participant.personPublicId() == null) {
                throw new BaseException(ErrorCode.INVALID_STATE,
                        message(actor, "외부 참여자 확정은 비활성화되어 있습니다.",
                                "External Calendar poll confirmation is disabled."));
            }
            CalendarSchedulingPollRepository.IdentityProfile profile = requireProfile(
                    actor, participant.userId(), participant.personPublicId());
            result.add(new CalendarDtos.AttendeeInput(
                    profile.userId(), profile.personPublicId(), profile.email(),
                    participant.displayName(), participant.required()
                            ? AttendeeType.REQUIRED : AttendeeType.OPTIONAL));
        }
        return List.copyOf(result);
    }

    List<PollParticipant> participantDtos(
            List<CalendarSchedulingPollRepository.ParticipantRow> values) {
        return values.stream().map(value -> new PollParticipant(
                value.participantId(), value.personPublicId(), value.externalEmail(),
                value.displayName(), value.required(), value.externalVotingEnabled()))
                .toList();
    }

    List<PollVote> voteDtos(
            List<CalendarSchedulingPollRepository.VoteRow> values) {
        return values.stream().map(value -> new PollVote(
                value.participantId(), value.candidateId(), value.choice())).toList();
    }

    int count(
            List<CalendarSchedulingPollRepository.VoteRow> values, VoteChoice choice) {
        return (int) values.stream().filter(value -> value.choice() == choice).count();
    }

    void requireReceipt(
            CalendarSchedulingPollAccess.Actor actor,
            CalendarSchedulingPollRepository.CommandReceiptRow receipt,
            UUID pollId,
            String command,
            String requestFingerprint) {
        if (!receipt.pollId().equals(pollId)
                || !receipt.commandType().equals(command)
                || !receipt.fingerprint().equals(requestFingerprint)) {
            throw conflict(actor, "동일한 멱등 키가 다른 명령에 사용되었습니다.",
                    "The Calendar poll idempotency key was reused with another command.");
        }
    }

    Object canonicalVoteRequest(CastVotesRequest request) {
        List<Map<String, String>> votes = request.votes() == null ? List.of()
                : request.votes().stream()
                .sorted(Comparator.comparing(value -> value.candidateId().toString()))
                .map(value -> Map.of(
                        "candidateId", value.candidateId().toString(),
                        "choice", value.choice().name()))
                .toList();
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("votes", votes);
        canonical.put("comment", request.comment());
        return canonical;
    }

    String fingerprint(Object value) {
        try {
            byte[] encoded = json.writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(encoded));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Calendar poll request hashing failed.", exception);
        }
    }

    OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    OffsetDateTime minimum(OffsetDateTime left, OffsetDateTime right) {
        return left.isBefore(right) ? left : right;
    }

    String defaultAgenda(String value) {
        return value == null || value.isBlank()
                ? "Scheduled from a Calendar poll." : value;
    }

    UUID requireId(CalendarSchedulingPollAccess.Actor actor, UUID value) {
        if (value == null) {
            throw invalid(actor, "투표 식별자가 필요합니다.",
                    "A Calendar poll identifier is required.");
        }
        return value;
    }

    BaseException notFound(CalendarSchedulingPollAccess.Actor actor) {
        return new BaseException(ErrorCode.NOT_FOUND,
                message(actor, "일정 투표를 찾을 수 없습니다.",
                        "The Calendar scheduling poll was not found."));
    }

    BaseException invalid(
            CalendarSchedulingPollAccess.Actor actor, String ko, String en) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message(actor, ko, en));
    }

    BaseException conflict(
            CalendarSchedulingPollAccess.Actor actor, String ko, String en) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message(actor, ko, en));
    }

    BaseException changed(CalendarSchedulingPollAccess.Actor actor) {
        return conflict(actor, "투표가 변경되었습니다. 새로고침 후 다시 시도해 주세요.",
                "The Calendar poll changed. Refresh and try again.");
    }

    String message(
            CalendarSchedulingPollAccess.Actor actor, String ko, String en) {
        return actor != null && actor.korean() ? ko : en;
    }
}
