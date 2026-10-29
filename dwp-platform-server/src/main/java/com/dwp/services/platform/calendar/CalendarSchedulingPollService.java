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
public class CalendarSchedulingPollService {

    private static final String TARGET = "CALENDAR_SCHEDULING_POLL";
    private static final String ROOM_CONFLICT = "ACTIVE_ROOM_CONFLICT";
    private static final String ROOM_CLOSED = "ROOM_NOT_BOOKABLE";
    private static final String HOLD_EXPIRED = "ROOM_HOLD_EXPIRED";

    private final CalendarSchedulingPollRepository polls;
    private final CalendarRepository calendar;
    private final CalendarService calendarService;
    private final WorkplaceRoomAccessPort roomAccess;
    private final PlatformAuditService audit;
    private final CalendarSchedulingPollMetrics metrics;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration roomHoldTtl;
    private final Duration retention;

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

    private Set<UUID> reconcileRoomHolds(
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

    private String roomUnavailabilityReason(
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

    private PollDetails reopenAfterRoomFailure(
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

    private PollDetails details(
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

    private CalendarSchedulingPollRepository.PollRow expireIfDue(
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

    private CalendarSchedulingPollRepository.PollRow visibleLocked(
            CalendarSchedulingPollAccess.Actor actor, UUID pollId) {
        CalendarSchedulingPollRepository.PollRow row = polls.lockPoll(
                        actor.tenantId(), requireId(actor, pollId))
                .orElseThrow(() -> notFound(actor));
        requireVisible(actor, row);
        return row;
    }

    private CalendarSchedulingPollRepository.PollRow organizerLocked(
            CalendarSchedulingPollAccess.Actor actor, UUID pollId) {
        CalendarSchedulingPollRepository.PollRow row = visibleLocked(actor, pollId);
        if (row.organizerUserId() != actor.userId()
                || !row.organizerPersonPublicId().equals(actor.personPublicId())) {
            throw notFound(actor);
        }
        return row;
    }

    private void requireVisible(
            CalendarSchedulingPollAccess.Actor actor,
            CalendarSchedulingPollRepository.PollRow row) {
        boolean organizer = row.organizerUserId() == actor.userId()
                && row.organizerPersonPublicId().equals(actor.personPublicId());
        boolean participant = polls.participant(
                actor.tenantId(), row.pollId(), actor.userId(), actor.personPublicId())
                .isPresent();
        if (!organizer && !participant) throw notFound(actor);
    }

    private CalendarSchedulingPollRepository.PollRow requiredPoll(
            CalendarSchedulingPollAccess.Actor actor, UUID pollId) {
        return polls.poll(actor.tenantId(), pollId).orElseThrow(() -> notFound(actor));
    }

    private void requireIdentity(CalendarSchedulingPollAccess.Actor actor) {
        if (actor == null || !polls.identityMatches(
                actor.tenantId(), actor.userId(), actor.personPublicId())) {
            throw CalendarSchedulingPollAccess.denied(actor != null && actor.korean());
        }
    }

    private CalendarSchedulingPollRepository.IdentityProfile requireProfile(
            CalendarSchedulingPollAccess.Actor actor, long userId, UUID personPublicId) {
        return polls.identityProfile(actor.tenantId(), userId, personPublicId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.INVALID_STATE,
                        message(actor, "활성 개인 메일 프로필이 필요합니다.",
                                "An active personal mail profile is required.")));
    }

    private void validateResources(
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

    private List<CalendarDtos.AttendeeInput> attendeeInputs(
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

    private List<PollParticipant> participantDtos(
            List<CalendarSchedulingPollRepository.ParticipantRow> values) {
        return values.stream().map(value -> new PollParticipant(
                value.participantId(), value.personPublicId(), value.externalEmail(),
                value.displayName(), value.required(), value.externalVotingEnabled()))
                .toList();
    }

    private List<PollVote> voteDtos(
            List<CalendarSchedulingPollRepository.VoteRow> values) {
        return values.stream().map(value -> new PollVote(
                value.participantId(), value.candidateId(), value.choice())).toList();
    }

    private int count(
            List<CalendarSchedulingPollRepository.VoteRow> values, VoteChoice choice) {
        return (int) values.stream().filter(value -> value.choice() == choice).count();
    }

    private void requireReceipt(
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

    private Object canonicalVoteRequest(CastVotesRequest request) {
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

    private String fingerprint(Object value) {
        try {
            byte[] encoded = json.writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(encoded));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Calendar poll request hashing failed.", exception);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private OffsetDateTime minimum(OffsetDateTime left, OffsetDateTime right) {
        return left.isBefore(right) ? left : right;
    }

    private String defaultAgenda(String value) {
        return value == null || value.isBlank()
                ? "Scheduled from a Calendar poll." : value;
    }

    private UUID requireId(CalendarSchedulingPollAccess.Actor actor, UUID value) {
        if (value == null) {
            throw invalid(actor, "투표 식별자가 필요합니다.",
                    "A Calendar poll identifier is required.");
        }
        return value;
    }

    private BaseException notFound(CalendarSchedulingPollAccess.Actor actor) {
        return new BaseException(ErrorCode.NOT_FOUND,
                message(actor, "일정 투표를 찾을 수 없습니다.",
                        "The Calendar scheduling poll was not found."));
    }

    private BaseException invalid(
            CalendarSchedulingPollAccess.Actor actor, String ko, String en) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message(actor, ko, en));
    }

    private BaseException conflict(
            CalendarSchedulingPollAccess.Actor actor, String ko, String en) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message(actor, ko, en));
    }

    private BaseException changed(CalendarSchedulingPollAccess.Actor actor) {
        return conflict(actor, "투표가 변경되었습니다. 새로고침 후 다시 시도해 주세요.",
                "The Calendar poll changed. Refresh and try again.");
    }

    private String message(
            CalendarSchedulingPollAccess.Actor actor, String ko, String en) {
        return actor != null && actor.korean() ? ko : en;
    }
}
