package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.workplace.WorkplaceRoomAccessPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

/** Transactional internal-user boundary for scheduling polls and their temporary holds. */
@Service
public class CalendarSchedulingPollService extends CalendarSchedulingPollServiceSupport {

    @Autowired
    CalendarSchedulingPollService(
            CalendarSchedulingPollRepository polls,
            CalendarRepository calendar,
            CalendarService calendarService,
            WorkplaceRoomAccessPort roomAccess,
            PlatformAuditService audit,
            CalendarSchedulingPollMetrics metrics,
            ObjectMapper json,
            @Value("${dwp.platform.calendar.scheduling-polls.room-hold-ttl-seconds:900}")
                    int roomHoldTtlSeconds,
            @Value("${dwp.platform.calendar.scheduling-polls.retention-days:365}")
                    int retentionDays) {
        this(polls, calendar, calendarService, roomAccess, audit, metrics, json,
                Clock.systemUTC(), Duration.ofSeconds(roomHoldTtlSeconds),
                Duration.ofDays(retentionDays));
    }

    CalendarSchedulingPollService(
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
        super(polls, calendar, calendarService, roomAccess, audit, metrics, json,
                clock, roomHoldTtl, retention);
    }

    @Transactional
    public PollPage list(
            CalendarSchedulingPollAccess.Actor actor,
            OffsetDateTime afterUpdatedAt,
            UUID afterPollId,
            Integer requestedLimit) {
        requireIdentity(actor);
        if ((afterUpdatedAt == null) != (afterPollId == null)) {
            throw invalid(actor, "페이지 커서가 올바르지 않습니다.",
                    "Both Calendar poll cursor fields must be supplied together.");
        }
        int limit = requestedLimit == null ? 25 : requestedLimit;
        if (limit < 1 || limit > 50) {
            throw invalid(actor, "페이지 크기는 1~50이어야 합니다.",
                    "Calendar poll page size must be between 1 and 50.");
        }
        List<CalendarSchedulingPollRepository.PollRow> rows = polls.polls(
                actor.tenantId(), actor.userId(), actor.personPublicId(),
                afterUpdatedAt, afterPollId, limit + 1);
        OffsetDateTime now = now();
        List<CalendarSchedulingPollRepository.PollRow> visible = new ArrayList<>();
        boolean reordered = false;
        for (CalendarSchedulingPollRepository.PollRow row : rows) {
            CalendarSchedulingPollRepository.PollRow refreshed = expireIfDue(actor, row, now);
            visible.add(refreshed);
            reordered |= refreshed.version() != row.version();
        }
        if (reordered) {
            visible = new ArrayList<>(polls.polls(
                    actor.tenantId(), actor.userId(), actor.personPublicId(),
                    afterUpdatedAt, afterPollId, limit + 1));
        }
        boolean hasMore = visible.size() > limit;
        List<CalendarSchedulingPollRepository.PollRow> pageRows = hasMore
                ? List.copyOf(visible.subList(0, limit)) : List.copyOf(visible);
        List<PollDetails> items = pageRows.stream()
                .map(row -> details(actor, row, now)).toList();
        CalendarSchedulingPollRepository.PollRow last = pageRows.isEmpty()
                ? null : pageRows.get(pageRows.size() - 1);
        metrics.read(items.size());
        return new PollPage(
                items,
                hasMore ? last.updatedAt() : null,
                hasMore ? last.pollId() : null,
                hasMore);
    }

    @Transactional
    public PollDetails detail(CalendarSchedulingPollAccess.Actor actor, UUID pollId) {
        requireIdentity(actor);
        CalendarSchedulingPollRepository.PollRow row = visibleLocked(actor, pollId);
        row = expireIfDue(actor, row, now());
        metrics.read(1);
        return details(actor, row, now());
    }

    @Transactional
    public PollDetails create(
            CalendarSchedulingPollAccess.Actor actor, CreatePollRequest request) {
        requireIdentity(actor);
        CreatePollRequest value = CalendarSchedulingPollContract.validateCreate(request, clock);
        String fingerprint = fingerprint(value);
        polls.lockIdempotency(actor.tenantId(), actor.userId(), value.idempotencyKey());
        CalendarSchedulingPollRepository.PollRow existing = polls.pollByCreateIdempotency(
                actor.tenantId(), actor.userId(), value.idempotencyKey()).orElse(null);
        if (existing != null) {
            if (!fingerprint.equals(existing.createFingerprint())) {
                throw conflict(actor, "동일한 멱등 키가 다른 요청에 사용되었습니다.",
                        "The Calendar poll idempotency key was reused with another request.");
            }
            requireVisible(actor, existing);
            return details(actor, existing, now());
        }

        requireProfile(actor, actor.userId(), actor.personPublicId());
        validateResources(actor, value.candidates());
        Map<UUID, CalendarSchedulingPollRepository.IdentityRow> identities =
                new LinkedHashMap<>();
        for (PollParticipantInput participant : value.participants()) {
            CalendarSchedulingPollRepository.IdentityRow identity = polls.identityByPerson(
                            actor.tenantId(), participant.personPublicId())
                    .orElseThrow(() -> invalid(actor,
                            "참여자 신원을 현재 테넌트에서 확인할 수 없습니다.",
                            "A Calendar poll participant is not registered in this tenant."));
            requireProfile(actor, identity.userId(), identity.personPublicId());
            identities.put(participant.personPublicId(), identity);
        }

        UUID pollId = UUID.randomUUID();
        OffsetDateTime now = now();
        polls.insertPoll(pollId, actor.tenantId(), actor.userId(),
                actor.personPublicId(), value, fingerprint, now);
        polls.insertCandidates(actor.tenantId(), pollId, value.candidates());
        for (PollParticipantInput participant : value.participants()) {
            polls.insertParticipant(
                    actor.tenantId(), pollId, UUID.randomUUID(),
                    identities.get(participant.personPublicId()), participant, false);
        }
        polls.audit(actor.tenantId(), pollId, actor.userId(), "calendar.poll.created",
                Map.of(), Map.of("state", PollState.DRAFT.name()),
                actor.correlationId(), now);
        CalendarSchedulingPollRepository.PollRow created = polls.poll(
                        actor.tenantId(), pollId)
                .orElseThrow(() -> new IllegalStateException("Calendar poll was not persisted."));
        PollDetails result = details(actor, created, now);
        audit.success(actor.tenantId(), actor.userId(), "calendar.poll.created",
                TARGET, pollId.toString(), actor.correlationId(), null, result.poll());
        metrics.created();
        return result;
    }

    @Transactional
    public PollDetails open(
            CalendarSchedulingPollAccess.Actor actor,
            UUID pollId,
            ChangePollStateRequest request) {
        requireIdentity(actor);
        if (request == null || request.state() != PollState.OPEN || request.version() == null) {
            throw invalid(actor, "투표 열기 요청이 올바르지 않습니다.",
                    "Calendar poll open request is invalid.");
        }
        CalendarSchedulingPollRepository.PollRow before = organizerLocked(actor, pollId);
        if (before.version() != request.version()) throw changed(actor);
        if (!before.expiresAt().isAfter(now())) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    message(actor, "만료된 투표는 열 수 없습니다.",
                            "An expired Calendar poll cannot be opened."));
        }
        CalendarSchedulingPollContract.requireTransition(before.state(), PollState.OPEN);
        OffsetDateTime now = now();
        if (polls.transition(actor.tenantId(), pollId, before.state(), PollState.OPEN,
                before.version(), actor.userId(), null, now) != 1) {
            throw changed(actor);
        }
        List<CalendarSchedulingPollRepository.CandidateRow> candidates =
                polls.candidates(actor.tenantId(), pollId);
        List<CalendarSchedulingPollRepository.ParticipantRow> participants =
                polls.participants(actor.tenantId(), pollId);
        for (CalendarSchedulingPollRepository.CandidateRow candidate : candidates) {
            for (CalendarSchedulingPollRepository.ParticipantRow participant : participants) {
                polls.insertPeopleHold(UUID.randomUUID(), actor.tenantId(), pollId,
                        candidate, participant, before.expiresAt(), actor.userId(), now);
            }
        }
        polls.audit(actor.tenantId(), pollId, actor.userId(), "calendar.poll.opened",
                Map.of("state", before.state().name()), Map.of("state", "OPEN"),
                actor.correlationId(), now);
        CalendarSchedulingPollRepository.PollRow after = requiredPoll(actor, pollId);
        PollDetails result = details(actor, after, now);
        audit.success(actor.tenantId(), actor.userId(), "calendar.poll.opened",
                TARGET, pollId.toString(), actor.correlationId(), before.state(), result.poll());
        metrics.opened();
        return result;
    }

    @Transactional
    public PollDetails vote(
            CalendarSchedulingPollAccess.Actor actor,
            UUID pollId,
            CastVotesRequest request) {
        requireIdentity(actor);
        if (request == null || request.idempotencyKey() == null) {
            throw invalid(actor, "투표 요청이 올바르지 않습니다.",
                    "Calendar poll vote request is invalid.");
        }
        if (request.votes() == null || request.votes().isEmpty()
                || request.votes().size() > 20
                || request.votes().stream().anyMatch(value -> value == null
                    || value.candidateId() == null || value.choice() == null)
                || (request.comment() != null && request.comment().length() > 1000)) {
            throw invalid(actor, "투표 항목이 올바르지 않습니다.",
                    "Calendar poll vote entries are invalid.");
        }
        String requestFingerprint = fingerprint(canonicalVoteRequest(request));
        polls.lockIdempotency(actor.tenantId(), actor.userId(), request.idempotencyKey());
        CalendarSchedulingPollRepository.CommandReceiptRow replay = polls.receipt(
                actor.tenantId(), actor.userId(), request.idempotencyKey()).orElse(null);
        if (replay != null) {
            requireReceipt(actor, replay, pollId, "VOTE", requestFingerprint);
            return details(actor, visibleLocked(actor, pollId), now());
        }

        CalendarSchedulingPollRepository.PollRow before = visibleLocked(actor, pollId);
        if (!before.expiresAt().isAfter(now())) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    message(actor, "만료된 투표에는 응답할 수 없습니다.",
                            "An expired Calendar poll cannot accept votes."));
        }
        if (before.state() != PollState.OPEN
                && before.state() != PollState.AGREEMENT_REACHED) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    message(actor, "현재 상태에서는 투표할 수 없습니다.",
                            "The Calendar poll is not open for voting."));
        }
        CalendarSchedulingPollRepository.ParticipantRow participant = polls.participant(
                        actor.tenantId(), pollId, actor.userId(), actor.personPublicId())
                .orElseThrow(() -> notFound(actor));
        List<CalendarSchedulingPollRepository.CandidateRow> candidates =
                polls.candidates(actor.tenantId(), pollId);
        Set<UUID> candidateIds = candidates.stream()
                .map(CalendarSchedulingPollRepository.CandidateRow::candidateId)
                .collect(Collectors.toUnmodifiableSet());
        CastVotesRequest value = CalendarSchedulingPollContract.validateVotes(
                request, candidateIds);
        OffsetDateTime now = now();
        for (VoteInput vote : value.votes()) {
            polls.upsertVote(actor.tenantId(), pollId, participant.participantId(), vote,
                    value.comment(), actor.userId(), now);
        }
        polls.expireHolds(actor.tenantId(), pollId, now, actor.userId());

        List<CalendarSchedulingPollRepository.ParticipantRow> participants =
                polls.participants(actor.tenantId(), pollId);
        List<CalendarSchedulingPollRepository.VoteRow> storedVotes =
                polls.votes(actor.tenantId(), pollId);
        Set<UUID> voteEligible = CalendarSchedulingPollContract.agreementCandidates(
                participantDtos(participants), voteDtos(storedVotes), candidateIds);
        Set<UUID> holdEligible = reconcileRoomHolds(
                actor, before, candidates, voteEligible, now);
        PollState nextState = holdEligible.isEmpty()
                ? PollState.OPEN : PollState.AGREEMENT_REACHED;
        if (nextState == PollState.OPEN) {
            polls.releaseRoomHolds(actor.tenantId(), pollId, actor.userId(), now);
        }
        int changed = before.state() == nextState
                ? polls.advanceVersion(actor.tenantId(), pollId, before.state(),
                        before.version(), actor.userId(), now)
                : polls.transition(actor.tenantId(), pollId, before.state(), nextState,
                        before.version(), actor.userId(), null, now);
        if (changed != 1) throw changed(actor);

        CalendarSchedulingPollRepository.PollRow after = requiredPoll(actor, pollId);
        polls.insertReceipt(actor.tenantId(), actor.userId(), actor.personPublicId(),
                value.idempotencyKey(), pollId, "VOTE", requestFingerprint,
                after.state(), after.version(), null, now);
        polls.audit(actor.tenantId(), pollId, actor.userId(), "calendar.poll.voted",
                Map.of("state", before.state().name()),
                Map.of("state", after.state().name(), "eligibleCandidates", holdEligible.size()),
                actor.correlationId(), now);
        PollDetails result = details(actor, after, now);
        audit.success(actor.tenantId(), actor.userId(), "calendar.poll.voted",
                TARGET, pollId.toString(), actor.correlationId(), before.state(), result.poll());
        metrics.voted();
        return result;
    }

    @Transactional
    public PollDetails cancel(
            CalendarSchedulingPollAccess.Actor actor,
            UUID pollId,
            ChangePollStateRequest request) {
        requireIdentity(actor);
        if (request == null || request.state() != PollState.CANCELLED
                || request.version() == null) {
            throw invalid(actor, "투표 취소 요청이 올바르지 않습니다.",
                    "Calendar poll cancellation request is invalid.");
        }
        CalendarSchedulingPollRepository.PollRow before = organizerLocked(actor, pollId);
        if (before.version() != request.version()) throw changed(actor);
        CalendarSchedulingPollContract.requireTransition(before.state(), PollState.CANCELLED);
        OffsetDateTime now = now();
        polls.releaseHolds(actor.tenantId(), pollId, actor.userId(), now);
        if (polls.transition(actor.tenantId(), pollId, before.state(), PollState.CANCELLED,
                before.version(), actor.userId(), now.plus(retention), now) != 1) {
            throw changed(actor);
        }
        polls.audit(actor.tenantId(), pollId, actor.userId(), "calendar.poll.cancelled",
                Map.of("state", before.state().name()), Map.of("state", "CANCELLED"),
                actor.correlationId(), now);
        CalendarSchedulingPollRepository.PollRow after = requiredPoll(actor, pollId);
        PollDetails result = details(actor, after, now);
        audit.success(actor.tenantId(), actor.userId(), "calendar.poll.cancelled",
                TARGET, pollId.toString(), actor.correlationId(), before.state(), result.poll());
        metrics.cancelled();
        return result;
    }

    @Transactional
    public PollDetails confirm(
            CalendarSchedulingPollAccess.Actor actor,
            UUID pollId,
            ConfirmPollRequest request) {
        requireIdentity(actor);
        if (request == null || request.idempotencyKey() == null
                || request.candidateId() == null || request.calendarId() == null
                || request.version() == null || request.version() < 0) {
            throw invalid(actor, "투표 확정 요청이 올바르지 않습니다.",
                    "Calendar poll confirmation request is invalid.");
        }
        String requestFingerprint = fingerprint(request);
        polls.lockIdempotency(actor.tenantId(), actor.userId(), request.idempotencyKey());
        CalendarSchedulingPollRepository.CommandReceiptRow replay = polls.receipt(
                actor.tenantId(), actor.userId(), request.idempotencyKey()).orElse(null);
        if (replay != null) {
            requireReceipt(actor, replay, pollId, "CONFIRM", requestFingerprint);
            return details(actor, visibleLocked(actor, pollId), now());
        }

        CalendarSchedulingPollRepository.PollRow before = organizerLocked(actor, pollId);
        if (before.version() != request.version()) throw changed(actor);
        OffsetDateTime now = now();
        if (!before.expiresAt().isAfter(now)) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    message(actor, "만료된 투표는 확정할 수 없습니다.",
                            "An expired Calendar poll cannot be confirmed."));
        }
        List<CalendarSchedulingPollRepository.CandidateRow> candidates =
                polls.candidates(actor.tenantId(), pollId);
        CalendarSchedulingPollRepository.CandidateRow chosen = candidates.stream()
                .filter(value -> value.candidateId().equals(request.candidateId()))
                .findFirst().orElseThrow(() -> invalid(actor,
                        "선택한 후보 시간이 존재하지 않습니다.",
                        "The selected Calendar poll candidate does not exist."));
        List<CalendarSchedulingPollRepository.ParticipantRow> participants =
                polls.participants(actor.tenantId(), pollId);
        Set<UUID> candidateIds = candidates.stream()
                .map(CalendarSchedulingPollRepository.CandidateRow::candidateId)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> eligible = CalendarSchedulingPollContract.agreementCandidates(
                participantDtos(participants),
                voteDtos(polls.votes(actor.tenantId(), pollId)), candidateIds);
        CalendarSchedulingPollContract.requireConfirmable(
                before.state(), request.candidateId(), eligible);
        if (polls.activePeopleHoldCount(
                actor.tenantId(), pollId, chosen.candidateId(), now)
                != participants.size()) {
            throw conflict(actor, "참여자 임시 일정 근거가 만료되었습니다.",
                    "The participant scheduling evidence is no longer fresh.");
        }

        if (chosen.resourceId() != null) {
            polls.lockRoom(actor.tenantId(), chosen.resourceId());
            polls.expireRoomHolds(
                    actor.tenantId(), chosen.resourceId(), now, actor.userId());
            if (!polls.activeRoomHold(
                    actor.tenantId(), pollId, chosen.candidateId(), now)) {
                return reopenAfterRoomFailure(
                        actor, before, chosen, "HOLD_EXPIRED", HOLD_EXPIRED, now);
            }
            String unavailable = roomUnavailabilityReason(
                    actor.tenantId(), pollId, chosen, now);
            if (unavailable != null) {
                return reopenAfterRoomFailure(
                        actor, before, chosen, "ROOM_UNAVAILABLE", unavailable, now);
            }
        }

        CalendarSchedulingPollRepository.IdentityProfile organizer = requireProfile(
                actor, actor.userId(), actor.personPublicId());
        List<CalendarDtos.AttendeeInput> attendees = attendeeInputs(actor, participants);
        polls.consumeCandidateHolds(
                actor.tenantId(), pollId, chosen.candidateId(), actor.userId(), now);
        polls.releaseOtherHolds(
                actor.tenantId(), pollId, chosen.candidateId(), actor.userId(), now);

        UUID eventCommandKey = UUID.nameUUIDFromBytes(
                ("calendar-poll-event:" + pollId + ":" + request.idempotencyKey())
                        .getBytes(StandardCharsets.UTF_8));
        CalendarDtos.CreateEventRequest eventRequest = new CalendarDtos.CreateEventRequest(
                before.title(), defaultAgenda(before.description()), EventType.MEETING,
                chosen.startsAt(), chosen.endsAt(), before.timeZone(), false,
                null, null, EventVisibility.DEFAULT, RecurrencePattern.NONE,
                1, null, true, attendees, chosen.resourceId(), eventCommandKey,
                request.calendarId(), EventImportance.NORMAL);
        CalendarDtos.EventSummary event = calendarService.create(
                actor.tenantId(), actor.userId(), actor.personPublicId(),
                organizer.displayName(), actor.locale(), actor.correlationId(),
                actor.groupRefs(), eventRequest);
        if (polls.confirm(actor.tenantId(), pollId, before.version(),
                chosen.candidateId(), event.eventId(), actor.userId(),
                now.plus(retention), now) != 1) {
            throw changed(actor);
        }
        CalendarSchedulingPollRepository.PollRow after = requiredPoll(actor, pollId);
        polls.insertReceipt(actor.tenantId(), actor.userId(), actor.personPublicId(),
                request.idempotencyKey(), pollId, "CONFIRM", requestFingerprint,
                after.state(), after.version(), event.eventId(), now);
        polls.audit(actor.tenantId(), pollId, actor.userId(), "calendar.poll.confirmed",
                Map.of("state", before.state().name()),
                Map.of("state", after.state().name(), "eventId", event.eventId().toString()),
                actor.correlationId(), now);
        PollDetails result = details(actor, after, now);
        audit.success(actor.tenantId(), actor.userId(), "calendar.poll.confirmed",
                TARGET, pollId.toString(), actor.correlationId(), before.state(), result.poll());
        metrics.confirmed();
        return result;
    }

}
