package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.WorkplaceRoomAccessPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.dwp.services.platform.calendar.CalendarSchedulingV2Dtos.*;
import static com.dwp.services.platform.calendar.CalendarTypes.ResourceState;

@Service
public class CalendarSchedulingV2Service {

    static final String ORGANIZER_REF = "organizer";
    private static final Duration MAX_LOCAL_SEARCH_WINDOW = Duration.ofDays(14);
    private static final Duration EVALUATION_TTL = Duration.ofSeconds(25);
    private static final Duration HARD_RECHECK_TTL = Duration.ofSeconds(25);
    private static final Duration RETENTION_GRACE = Duration.ofMinutes(5);
    // 14 local days can contain 337 hours across a fall-back transition.
    private static final int MAX_SCANNED_CANDIDATES = 676;
    private static final int MAX_SUGGESTIONS = 8;
    private static final int MAX_ROOMS = 30;

    private final CalendarSchedulingV2Repository repository;
    private final WorkplaceRoomAccessPort roomAccess;
    private final Clock clock;
    private final SecureRandom random;

    @Autowired
    public CalendarSchedulingV2Service(
            CalendarSchedulingV2Repository repository,
            WorkplaceRoomAccessPort roomAccess) {
        this(repository, roomAccess, Clock.systemUTC(), new SecureRandom());
    }

    CalendarSchedulingV2Service(
            CalendarSchedulingV2Repository repository,
            WorkplaceRoomAccessPort roomAccess,
            Clock clock,
            SecureRandom random) {
        this.repository = repository;
        this.roomAccess = roomAccess;
        this.clock = clock;
        this.random = random;
    }

    @Transactional
    public EvaluationResponse evaluate(
            long tenantId,
            long actorUserId,
            UUID actorPersonPublicId,
            String verifiedGroupRefs,
            String locale,
            EvaluationRequest request) {
        requireActor(tenantId, actorUserId, actorPersonPublicId);
        OffsetDateTime generatedAt = now();
        repository.purgeExpired(tenantId, generatedAt.minus(RETENTION_GRACE), 100);
        CalendarSchedulingV2Repository.PolicyRow policy = validate(
                request, actorPersonPublicId, repository.policy(tenantId));
        List<ParticipantAvailability> participants = participants(
                tenantId, actorPersonPublicId, verifiedGroupRefs, request, policy, generatedAt);
        Coverage completeness = completeness(participants);
        String criteriaHash = CalendarSchedulingV2Hashes.criteria(request);
        String participantHash = CalendarSchedulingV2Hashes.evaluationParticipants(
                actorPersonPublicId, request.participants());
        List<SchedulingCandidate> suggestions = suggestions(request, participants, criteriaHash);
        List<SourceEvidence> sources = sources(request, participants, generatedAt);
        List<RoomRecommendation> rooms = rooms(
                tenantId, actorUserId, verifiedGroupRefs, locale, request, policy);
        String sourceVersion = repository.sourceVersion(tenantId);
        UUID evaluationId = UUID.randomUUID();
        String challengeToken = CalendarSchedulingV2Hashes.token(random);
        OffsetDateTime validUntil = generatedAt.plus(EVALUATION_TTL);
        repository.insertEvaluation(new CalendarSchedulingV2Repository.EvaluationEvidence(
                evaluationId, tenantId, actorUserId, actorPersonPublicId,
                criteriaHash, participantHash, repository.json(request),
                repository.json(sources), repository.json(rooms), sourceVersion,
                CalendarSchedulingV2Hashes.sha256(challengeToken), generatedAt, validUntil));
        return new EvaluationResponse(
                evaluationId, criteriaHash, challengeToken, completeness,
                participants, suggestions, sources, rooms, generatedAt, validUntil);
    }

    @Transactional
    public HardRecheckResponse hardRecheck(
            long tenantId,
            long actorUserId,
            UUID actorPersonPublicId,
            String verifiedGroupRefs,
            String locale,
            UUID evaluationId,
            HardRecheckRequest request) {
        requireActor(tenantId, actorUserId, actorPersonPublicId);
        OffsetDateTime checkedAt = now();
        repository.purgeExpired(tenantId, checkedAt.minus(RETENTION_GRACE), 100);
        CalendarSchedulingV2Repository.EvaluationEvidence evidence = repository.consumeChallenge(
                        tenantId, evaluationId, actorUserId, actorPersonPublicId,
                        request.criteriaHash(), CalendarSchedulingV2Hashes.sha256(
                                request.challengeToken()), checkedAt)
                .orElseThrow(this::invalidEvidence);
        EvaluationRequest criteria = repository.read(
                evidence.criteriaPayload(), EvaluationRequest.class);
        CalendarSchedulingV2Repository.PolicyRow policy = validate(
                criteria, actorPersonPublicId, repository.policy(tenantId));
        validateSelection(criteria, request.selection());
        List<ParticipantAvailability> participants = participants(
                tenantId, actorPersonPublicId, verifiedGroupRefs,
                criteria, policy, checkedAt);
        List<HardConflict> conflicts = hardConflicts(participants, request.selection());
        String sourceVersion = repository.sourceVersion(tenantId);
        boolean sourceChanged = !Objects.equals(evidence.sourceVersion(), sourceVersion);
        boolean incomplete = sourceChanged || conflicts.stream().anyMatch(value ->
                value.state() == IntervalState.UNKNOWN);
        boolean blocked = conflicts.stream().anyMatch(HardConflict::blocking);

        RoomRecommendation selectedRoom = null;
        if (request.roomId() != null) {
            RoomCheck roomCheck = hardRoom(
                    tenantId, actorUserId, verifiedGroupRefs, locale,
                    criteria, request.selection(), request.roomId(), policy,
                    evidence.roomEvidence());
            selectedRoom = roomCheck.room();
            blocked = blocked || !roomCheck.pass();
        }

        HardRecheckStatus status = incomplete
                ? HardRecheckStatus.INCOMPLETE
                : blocked ? HardRecheckStatus.BLOCKED : HardRecheckStatus.PASS;
        String selectionHash = CalendarSchedulingV2Hashes.selection(
                request.selection().startsAt(), request.selection().endsAt(), request.roomId(),
                criteria.searchTimeZone());
        String hardToken = status == HardRecheckStatus.PASS
                ? CalendarSchedulingV2Hashes.token(random) : null;
        OffsetDateTime validUntil = checkedAt.plus(HARD_RECHECK_TTL);
        Map<String, Object> persisted = new LinkedHashMap<>();
        persisted.put("status", status.name());
        persisted.put("selectionHash", selectionHash);
        persisted.put("conflicts", conflicts);
        persisted.put("room", selectedRoom);
        persisted.put("sourceVersion", sourceVersion);
        repository.insertRecheck(new CalendarSchedulingV2Repository.RecheckEvidence(
                UUID.randomUUID(), tenantId, evaluationId, actorUserId,
                actorPersonPublicId, evidence.criteriaHash(), evidence.participantHash(),
                selectionHash, request.roomId(), sourceVersion,
                selectedRoom == null ? null : selectedRoom.resourceVersion(),
                policy.version(), status.name(),
                hardToken == null ? null : CalendarSchedulingV2Hashes.sha256(hardToken),
                repository.json(persisted), checkedAt, validUntil));
        return new HardRecheckResponse(
                evaluationId, evidence.criteriaHash(), status, hardToken,
                selectionHash, conflicts, selectedRoom, checkedAt, validUntil);
    }

    private void requireActor(long tenantId, long actorUserId, UUID actorPersonPublicId) {
        if (actorPersonPublicId == null
                || !repository.identityMatches(tenantId, actorUserId, actorPersonPublicId)) {
            throw new BaseException(ErrorCode.FORBIDDEN, "Calendar scheduling access is denied.");
        }
    }

    private CalendarSchedulingV2Repository.PolicyRow validate(
            EvaluationRequest request,
            UUID actorPersonPublicId,
            CalendarSchedulingV2Repository.PolicyRow policy) {
        if (request == null || request.searchWindow() == null
                || request.candidateSlot() == null || request.participants() == null) {
            throw invalid("Scheduling criteria are required.");
        }
        ZoneId searchZone;
        try {
            searchZone = ZoneId.of(request.searchTimeZone());
        } catch (RuntimeException exception) {
            throw invalid("The search time zone is invalid.");
        }
        OffsetDateTime from = request.searchWindow().from();
        OffsetDateTime to = request.searchWindow().to();
        OffsetDateTime candidateStart = request.candidateSlot().startsAt();
        OffsetDateTime candidateEnd = request.candidateSlot().endsAt();
        if (from == null || to == null || !to.isAfter(from)) {
            throw invalid("The search window must be positive.");
        }
        Duration localWindow = Duration.between(
                from.atZoneSameInstant(searchZone).toLocalDateTime(),
                to.atZoneSameInstant(searchZone).toLocalDateTime());
        if (localWindow.isNegative() || localWindow.isZero()
                || localWindow.compareTo(MAX_LOCAL_SEARCH_WINDOW) > 0) {
            throw invalid("The search window must be no longer than 14 local days.");
        }
        if (candidateStart == null || candidateEnd == null || !candidateEnd.isAfter(candidateStart)
                || candidateStart.isBefore(from) || candidateEnd.isAfter(to)
                || !minuteAligned(candidateStart) || !minuteAligned(candidateEnd)) {
            throw invalid("The candidate slot must be inside the search window.");
        }
        Duration exactDuration = Duration.between(candidateStart, candidateEnd);
        long duration = exactDuration.toMinutes();
        if (request.durationMinutes() == null
                || !exactDuration.equals(Duration.ofMinutes(request.durationMinutes()))) {
            throw invalid("The duration must exactly match the candidate slot in whole minutes.");
        }
        if (duration < policy.minimumEventMinutes()
                || duration > policy.maximumEventMinutes()) {
            throw invalid("The candidate duration is outside the Calendar policy.");
        }
        if (request.participants().size() > 19) {
            throw invalid("At most 19 participants can be added to the organizer.");
        }
        Set<String> refs = new HashSet<>();
        Set<UUID> people = new HashSet<>();
        people.add(actorPersonPublicId);
        for (ParticipantRequest participant : request.participants()) {
            if (participant == null || participant.clientRef() == null
                    || !participant.clientRef().matches("[A-Za-z0-9_-]{1,80}")
                    || participant.role() == null
                    || participant.role() == ParticipantRole.ORGANIZER) {
                throw invalid("Participants must use REQUIRED or OPTIONAL roles.");
            }
            String ref = participant.clientRef().trim().toLowerCase(Locale.ROOT);
            if (ORGANIZER_REF.equals(ref) || !refs.add(ref)) {
                throw invalid("Participant client references must be unique.");
            }
            if (participant.personPublicId() != null
                    && !people.add(participant.personPublicId())) {
                throw invalid("Participant identities must be unique.");
            }
        }
        return policy;
    }

    private void validateSelection(EvaluationRequest criteria, CandidateSlot selection) {
        if (selection == null || selection.startsAt() == null || selection.endsAt() == null
                || !selection.endsAt().isAfter(selection.startsAt())
                || selection.startsAt().isBefore(criteria.searchWindow().from())
                || selection.endsAt().isAfter(criteria.searchWindow().to())
                || !minuteAligned(selection.startsAt()) || !minuteAligned(selection.endsAt())
                || !Duration.between(selection.startsAt(), selection.endsAt())
                    .equals(Duration.ofMinutes(criteria.durationMinutes()))) {
            throw invalidEvidence();
        }
    }

    private List<ParticipantAvailability> participants(
            long tenantId,
            UUID actorPersonPublicId,
            String verifiedGroupRefs,
            EvaluationRequest request,
            CalendarSchedulingV2Repository.PolicyRow policy,
            OffsetDateTime observedAt) {
        List<ParticipantSeed> seeds = new ArrayList<>();
        seeds.add(new ParticipantSeed(
                ORGANIZER_REF, actorPersonPublicId, ParticipantRole.ORGANIZER, true));
        Set<UUID> authorized = repository.authorizedPeople(
                tenantId, actorPersonPublicId, verifiedGroupRefs, requestedPeople(request));
        request.participants().forEach(value -> seeds.add(new ParticipantSeed(
                value.clientRef().trim(), value.personPublicId(), value.role(),
                value.personPublicId() != null && authorized.contains(value.personPublicId()))));
        List<UUID> coveredPeople = seeds.stream().filter(ParticipantSeed::covered)
                .map(ParticipantSeed::personPublicId).distinct().toList();
        Map<UUID, List<AvailabilityInterval>> intervals = new HashMap<>();
        repository.intervals(
                tenantId, coveredPeople,
                request.searchWindow().from(), request.searchWindow().to()).forEach(value ->
                intervals.computeIfAbsent(value.personPublicId(), ignored -> new ArrayList<>())
                        .add(new AvailabilityInterval(
                                value.startsAt(), value.endsAt(), value.state(), value.sourceType())));
        return seeds.stream().map(seed -> {
            WorkingScheduleResult working = workingSchedule(
                    tenantId, seed, request.searchTimeZone(), policy, observedAt);
            Coverage coverage = seed.covered() && working.valid()
                    ? Coverage.COMPLETE : Coverage.UNKNOWN;
            List<AvailabilityInterval> values = coverage == Coverage.COMPLETE
                    ? List.copyOf(intervals.getOrDefault(seed.personPublicId(), List.of()))
                    : List.of(new AvailabilityInterval(
                            request.searchWindow().from(), request.searchWindow().to(),
                            IntervalState.UNKNOWN, "UNAVAILABLE"));
            return new ParticipantAvailability(
                    seed.clientRef(), seed.personPublicId(), seed.role(),
                    coverage, working.schedule(), values);
        }).toList();
    }

    private WorkingScheduleResult workingSchedule(
            long tenantId,
            ParticipantSeed seed,
            String fallbackTimeZone,
            CalendarSchedulingV2Repository.PolicyRow policy,
            OffsetDateTime observedAt) {
        CalendarSchedulingV2Repository.WorkingSettingsRow settings = seed.covered()
                ? repository.workingSettings(
                        tenantId, seed.personPublicId(), fallbackTimeZone).orElse(null)
                : null;
        int mask = settings == null ? 31 : settings.workingDaysMask();
        java.time.LocalTime configuredStart = settings == null
                ? policy.workingDayStart() : settings.workingDayStart();
        java.time.LocalTime configuredEnd = settings == null
                ? policy.workingDayEnd() : settings.workingDayEnd();
        String configuredZone = settings == null ? fallbackTimeZone : settings.timeZone();
        boolean valid = mask >= 0 && mask <= 127
                && configuredStart != null && configuredEnd != null
                && configuredEnd.isAfter(configuredStart) && validZone(configuredZone);
        java.time.LocalTime start = valid ? configuredStart : java.time.LocalTime.of(9, 0);
        java.time.LocalTime end = valid ? configuredEnd : java.time.LocalTime.of(18, 0);
        String timeZone = valid ? configuredZone : fallbackTimeZone;
        List<WorkingDay> days = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            if ((mask & (1 << (day.getValue() - 1))) != 0) {
                days.add(new WorkingDay(day, List.of(new WorkingWindow(start, end))));
            }
        }
        return new WorkingScheduleResult(new WorkingSchedule(
                timeZone,
                List.copyOf(days),
                valid ? settings == null ? "TENANT_POLICY" : settings.sourceType()
                        : "INVALID_SETTINGS",
                settings == null ? policy.version() : settings.version(), observedAt), valid);
    }

    private Coverage completeness(List<ParticipantAvailability> participants) {
        long complete = participants.stream()
                .filter(value -> value.coverage() == Coverage.COMPLETE).count();
        if (complete == participants.size()) return Coverage.COMPLETE;
        return complete == 0 ? Coverage.UNKNOWN : Coverage.PARTIAL;
    }

    private List<SourceEvidence> sources(
            EvaluationRequest request,
            List<ParticipantAvailability> participants,
            OffsetDateTime observedAt) {
        List<String> affected = participants.stream()
                .filter(value -> value.coverage() != Coverage.COMPLETE)
                .map(ParticipantAvailability::clientRef).toList();
        SourceStatus status = affected.isEmpty() ? SourceStatus.HEALTHY
                : affected.size() == participants.size()
                        ? SourceStatus.UNAVAILABLE : SourceStatus.PARTIAL;
        return List.of(new SourceEvidence(
                "DWP_NATIVE", status,
                request.searchWindow().from(), request.searchWindow().to(),
                affected.isEmpty() ? observedAt : null, observedAt,
                affected.isEmpty() ? "NATIVE_CURRENT" : "PARTICIPANT_COVERAGE_GAP",
                affected));
    }

    private List<SchedulingCandidate> suggestions(
            EvaluationRequest request,
            List<ParticipantAvailability> participants,
            String criteriaHash) {
        long duration = request.durationMinutes();
        List<SchedulingCandidate> result = new ArrayList<>();
        OffsetDateTime cursor = request.searchWindow().from();
        int scanned = 0;
        while (!cursor.plusMinutes(duration).isAfter(request.searchWindow().to())
                && scanned++ < MAX_SCANNED_CANDIDATES && result.size() < MAX_SUGGESTIONS) {
            OffsetDateTime end = cursor.plusMinutes(duration);
            List<String> required = new ArrayList<>();
            List<String> optional = new ArrayList<>();
            boolean unresolved = false;
            for (ParticipantAvailability participant : participants) {
                if (participant.coverage() != Coverage.COMPLETE) {
                    unresolved = true;
                    if (participant.role() == ParticipantRole.OPTIONAL) {
                        optional.add(participant.clientRef());
                    } else {
                        required.add(participant.clientRef());
                    }
                    continue;
                }
                boolean conflict = !withinWorkingSchedule(participant.workingSchedule(), cursor, end)
                        || overlaps(participant.intervals(), cursor, end);
                if (!conflict) continue;
                if (participant.role() == ParticipantRole.OPTIONAL) {
                    optional.add(participant.clientRef());
                } else {
                    required.add(participant.clientRef());
                }
            }
            if (!unresolved && required.isEmpty()) {
                List<String> reasons = new ArrayList<>();
                reasons.add("ALL_REQUIRED_AVAILABLE");
                if (!optional.isEmpty()) reasons.add("OPTIONAL_CONFLICT");
                result.add(new SchedulingCandidate(
                        UUID.nameUUIDFromBytes((criteriaHash + '|' + cursor.toInstant()
                                + '|' + end.toInstant()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        cursor, end, Coverage.COMPLETE,
                        Math.max(0, 100 - optional.size() * 15), List.copyOf(reasons),
                        List.of(), List.copyOf(optional), true));
            }
            cursor = cursor.plusMinutes(30);
        }
        return List.copyOf(result);
    }

    private boolean overlaps(
            List<AvailabilityInterval> intervals,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt) {
        return intervals.stream().anyMatch(value ->
                value.startsAt().isBefore(endsAt) && value.endsAt().isAfter(startsAt));
    }

    private boolean withinWorkingSchedule(
            WorkingSchedule schedule,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt) {
        try {
            ZoneId zone = ZoneId.of(schedule.timeZone());
            ZonedDateTime localStart = startsAt.atZoneSameInstant(zone);
            ZonedDateTime localEnd = endsAt.atZoneSameInstant(zone);
            if (!localStart.toLocalDate().equals(localEnd.toLocalDate())) return false;
            return schedule.days().stream()
                    .filter(day -> day.dayOfWeek() == localStart.getDayOfWeek())
                    .flatMap(day -> day.windows().stream())
                    .anyMatch(window -> !localStart.toLocalTime().isBefore(window.startsAt())
                            && !localEnd.toLocalTime().isAfter(window.endsAt()));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private List<RoomRecommendation> rooms(
            long tenantId,
            long actorUserId,
            String verifiedGroupRefs,
            String locale,
            EvaluationRequest request,
            CalendarSchedulingV2Repository.PolicyRow policy) {
        List<CalendarSchedulingV2Repository.RoomRow> rows = repository.rooms(
                tenantId, request.candidateSlot().startsAt(), request.candidateSlot().endsAt());
        Set<UUID> viewable = roomAccess.viewableResourceIds(
                tenantId, actorUserId, verifiedGroupRefs,
                rows.stream().map(CalendarSchedulingV2Repository.RoomRow::resourceId).toList());
        List<RoomRecommendation> ranked = rows.stream()
                .filter(value -> viewable.contains(value.resourceId()))
                .map(value -> roomRecommendation(
                        value, request.roomCriteria(), request.participants().size() + 1,
                        policy, locale, 0))
                .sorted(Comparator.comparing(RoomRecommendation::eligible).reversed()
                        .thenComparing(RoomRecommendation::score, Comparator.reverseOrder())
                        .thenComparing(value -> value.room().code()))
                .limit(MAX_ROOMS).toList();
        List<RoomRecommendation> result = new ArrayList<>();
        for (int index = 0; index < ranked.size(); index++) {
            RoomRecommendation value = ranked.get(index);
            result.add(new RoomRecommendation(
                    value.room(), index + 1, value.score(), value.eligible(),
                    value.eligibilityReason(), value.reasonCodes(), value.resourceVersion(),
                    value.policyVersion(), value.hardRecheckRequired()));
        }
        return List.copyOf(result);
    }

    private RoomRecommendation roomRecommendation(
            CalendarSchedulingV2Repository.RoomRow row,
            RoomCriteria criteria,
            int partySize,
            CalendarSchedulingV2Repository.PolicyRow policy,
            String locale,
            int rank) {
        RoomCriteria effective = criteria == null
                ? new RoomCriteria(null, null, null, List.of(), List.of(), ApprovalPreference.ANY)
                : criteria;
        List<String> reasons = new ArrayList<>();
        if (row.state() != ResourceState.AVAILABLE) reasons.add("RESOURCE_UNAVAILABLE");
        if (row.conflicted()) reasons.add("RESOURCE_CONFLICT");
        if (notEqual(effective.site(), row.site())) reasons.add("SITE_MISMATCH");
        if (notEqual(effective.floor(), row.floor())) reasons.add("FLOOR_MISMATCH");
        int minimumCapacity = Math.max(
                partySize, effective.minimumCapacity() == null ? 0 : effective.minimumCapacity());
        if (row.capacity() < minimumCapacity) reasons.add("CAPACITY_TOO_SMALL");
        Set<String> actual = normalizedFeatures(row.features());
        Set<String> required = normalizedFeatures(effective.requiredFeatures());
        if (!actual.containsAll(required)) reasons.add("REQUIRED_FEATURE_MISSING");
        ApprovalPreference approval = effective.approvalPreference() == null
                ? ApprovalPreference.ANY : effective.approvalPreference();
        if ((approval == ApprovalPreference.REQUIRE_NO_APPROVAL && row.approvalRequired())
                || (approval == ApprovalPreference.REQUIRE_APPROVAL && !row.approvalRequired())) {
            reasons.add("APPROVAL_PREFERENCE_MISMATCH");
        }
        Set<String> preferred = normalizedFeatures(effective.preferredFeatures());
        long preferredMatches = preferred.stream().filter(actual::contains).count();
        int score = 100 + (int) preferredMatches * 5;
        if (approval == ApprovalPreference.PREFER_NO_APPROVAL && row.approvalRequired()) {
            score -= 10;
            reasons.add("APPROVAL_REQUIRED_PENALTY");
        }
        if (!preferred.isEmpty() && preferredMatches < preferred.size()) {
            reasons.add("PREFERRED_FEATURE_MISSING");
        }
        boolean eligible = reasons.stream().noneMatch(this::blockingRoomReason);
        RoomEligibilityReason eligibility = eligible ? RoomEligibilityReason.ELIGIBLE
                : RoomEligibilityReason.valueOf(reasons.stream()
                        .filter(this::blockingRoomReason).findFirst()
                        .orElse("RESOURCE_UNAVAILABLE"));
        CalendarDtos.ResourceSummary summary = new CalendarDtos.ResourceSummary(
                row.resourceId(), row.code(), korean(locale) ? row.nameKo() : row.nameEn(),
                row.nameKo(), row.nameEn(), row.type(), row.site(), row.floor(), row.capacity(),
                row.features(), row.timeZone(), row.approvalRequired(), row.state(),
                row.state() == ResourceState.AVAILABLE && !row.conflicted(), row.version());
        return new RoomRecommendation(
                summary, rank, Math.max(0, score), eligible, eligibility,
                List.copyOf(reasons), row.version(), policy.version(), true);
    }

    private RoomCheck hardRoom(
            long tenantId,
            long actorUserId,
            String verifiedGroupRefs,
            String locale,
            EvaluationRequest criteria,
            CandidateSlot selection,
            UUID roomId,
            CalendarSchedulingV2Repository.PolicyRow policy,
            String roomEvidence) {
        RoomRecommendation expected = java.util.Arrays.stream(repository.read(
                        roomEvidence, RoomRecommendation[].class))
                .filter(value -> value.room().resourceId().equals(roomId))
                .findFirst().orElse(null);
        CalendarSchedulingV2Repository.RoomRow current = repository.room(
                        tenantId, roomId, selection.startsAt(), selection.endsAt())
                .orElse(null);
        boolean viewable = current != null && roomAccess.canView(
                tenantId, actorUserId, verifiedGroupRefs, roomId);
        if (expected == null || current == null || !viewable) {
            return new RoomCheck(false, null);
        }
        RoomRecommendation evaluated = roomRecommendation(
                current, criteria.roomCriteria(), criteria.participants().size() + 1,
                policy, locale, expected.rank());
        List<String> reasons = new ArrayList<>(evaluated.reasonCodes());
        if (current.version() != expected.resourceVersion()) {
            reasons.add("RESOURCE_VERSION_CHANGED");
        }
        if (policy.version() != expected.policyVersion()) {
            reasons.add("POLICY_VERSION_CHANGED");
        }
        boolean pass = evaluated.eligible()
                && current.version() == expected.resourceVersion()
                && policy.version() == expected.policyVersion();
        RoomEligibilityReason reason = pass ? RoomEligibilityReason.ELIGIBLE
                : reasons.contains("RESOURCE_VERSION_CHANGED")
                        ? RoomEligibilityReason.RESOURCE_VERSION_CHANGED
                        : reasons.contains("POLICY_VERSION_CHANGED")
                                ? RoomEligibilityReason.POLICY_VERSION_CHANGED
                                : evaluated.eligibilityReason();
        return new RoomCheck(pass, new RoomRecommendation(
                evaluated.room(), expected.rank(), evaluated.score(), pass, reason,
                List.copyOf(reasons), current.version(), policy.version(), true));
    }

    private List<HardConflict> hardConflicts(
            List<ParticipantAvailability> participants, CandidateSlot selection) {
        List<HardConflict> result = new ArrayList<>();
        for (ParticipantAvailability participant : participants) {
            if (participant.coverage() != Coverage.COMPLETE) {
                result.add(new HardConflict(
                        participant.clientRef(), IntervalState.UNKNOWN,
                        selection.startsAt(), selection.endsAt(), true));
                continue;
            }
            boolean blocking = participant.role() != ParticipantRole.OPTIONAL;
            if (!withinWorkingSchedule(
                    participant.workingSchedule(), selection.startsAt(), selection.endsAt())) {
                result.add(new HardConflict(
                        participant.clientRef(), IntervalState.OUT_OF_OFFICE,
                        selection.startsAt(), selection.endsAt(), blocking));
                continue;
            }
            participant.intervals().stream()
                    .filter(value -> value.startsAt().isBefore(selection.endsAt())
                            && value.endsAt().isAfter(selection.startsAt()))
                    .map(value -> new HardConflict(
                            participant.clientRef(), value.state(),
                            value.startsAt(), value.endsAt(), blocking))
                    .forEach(result::add);
        }
        return List.copyOf(result);
    }

    private List<UUID> requestedPeople(EvaluationRequest request) {
        return request.participants().stream().map(ParticipantRequest::personPublicId)
                .filter(Objects::nonNull).distinct().toList();
    }

    private boolean notEqual(String expected, String actual) {
        return expected != null && !expected.isBlank()
                && !expected.trim().equalsIgnoreCase(Objects.toString(actual, ""));
    }

    private Set<String> normalizedFeatures(List<String> values) {
        if (values == null) return Set.of();
        return values.stream().filter(Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank()).collect(Collectors.toSet());
    }

    private boolean blockingRoomReason(String reason) {
        return Set.of(
                "RESOURCE_UNAVAILABLE", "RESOURCE_CONFLICT", "SITE_MISMATCH",
                "FLOOR_MISMATCH", "CAPACITY_TOO_SMALL", "REQUIRED_FEATURE_MISSING",
                "APPROVAL_PREFERENCE_MISMATCH", "RESOURCE_VERSION_CHANGED",
                "POLICY_VERSION_CHANGED", "ROOM_NOT_VISIBLE").contains(reason);
    }

    private boolean korean(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ko");
    }

    private boolean validZone(String value) {
        try {
            ZoneId.of(value);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean minuteAligned(OffsetDateTime value) {
        return value.getSecond() == 0 && value.getNano() == 0;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException invalidEvidence() {
        return new BaseException(
                ErrorCode.FORBIDDEN, "The scheduling evidence is invalid or expired.");
    }

    private record ParticipantSeed(
            String clientRef, UUID personPublicId, ParticipantRole role, boolean covered) {
    }

    private record WorkingScheduleResult(WorkingSchedule schedule, boolean valid) {
    }

    private record RoomCheck(boolean pass, RoomRecommendation room) {
    }
}
