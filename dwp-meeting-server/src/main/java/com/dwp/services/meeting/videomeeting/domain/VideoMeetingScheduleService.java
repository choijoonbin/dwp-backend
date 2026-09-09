package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingDtos;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingScheduleDtos.*;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.LifecycleState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.*;

@Service
public class VideoMeetingScheduleService {
    private final VideoMeetingService meetings;
    private final VideoMeetingRepository meetingRepository;
    private final VideoMeetingScheduleRepository schedules;
    private final VideoMeetingAuditRecorder audit;
    private final Clock clock;

    @Autowired
    public VideoMeetingScheduleService(VideoMeetingService meetings,
            VideoMeetingRepository meetingRepository,
            VideoMeetingScheduleRepository schedules,
            VideoMeetingAuditRecorder audit) {
        this(meetings, meetingRepository, schedules, audit, Clock.systemUTC());
    }

    VideoMeetingScheduleService(VideoMeetingService meetings,
            VideoMeetingRepository meetingRepository,
            VideoMeetingScheduleRepository schedules,
            VideoMeetingAuditRecorder audit, Clock clock) {
        this.meetings = meetings;
        this.meetingRepository = meetingRepository;
        this.schedules = schedules;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public VideoMeetingDtos.MeetingCreatedResponse createSeries(
            CreateSeriesRequest request, String idempotencyKey, String correlationId) {
        var subject = subject("APP.MEETINGS:CREATE");
        if (request == null || request.meeting() == null || request.recurrence() == null)
            throw invalid("A recurrence rule and meeting are required.");
        var recurrence = request.recurrence();
        validateRecurrence(recurrence);
        var meeting = request.meeting();
        VideoMeetingEntryPolicy.requireSupportedCreation(
                meeting.accessScope(), meeting.guestAccessEnabled(),
                meeting.allowJoinBeforeHost(), meeting.guestInvitees());
        ZoneId zone = zone(meeting.timeZone());
        SeriesPreviewResponse preview = previewSeries(
                new SeriesPreviewRequest(meeting, recurrence));
        if (request.previewFingerprint() == null
                || !requestHashesMatch(preview.previewFingerprint(), request.previewFingerprint()))
            throw conflict("The recurrence preview changed. Review it again before creating the series.");
        String key = commandKey(idempotencyKey);
        String digest = seriesDigest(meeting, recurrence);
        schedules.lockSeriesKey(subject.tenantId(), subject.userId(), key);
        var existing = schedules.seriesReplay(subject.tenantId(), subject.userId(), key).orElse(null);
        if (existing != null) {
            if (!requestHashesMatch(existing.requestDigest(), digest))
                throw conflict("The series idempotency key was reused for different input.");
            return created(existing.firstMeetingId());
        }

        ZonedDateTime anchor = validatedStart(meeting.startsAt(), zone);
        UUID seriesId = UUID.randomUUID();
        schedules.createSeries(seriesId, subject.tenantId(), subject.userId(),
                recurrence.frequency(), recurrence.interval(), recurrence.occurrenceCount(),
                anchor.toLocalDateTime(), zone.getId(), meeting.durationMinutes(), key, digest);

        VideoMeetingDtos.MeetingCreatedResponse first = null;
        for (int index = 0; index < recurrence.occurrenceCount(); index++) {
            OccurrenceProjection projection = projectOccurrence(
                    anchor.toLocalDateTime(), recurrence, index, zone, anchor.getOffset());
            LocalDateTime local = projection.localStart();
            ZonedDateTime occurrence = projection.start();
            if (!occurrence.toInstant().isAfter(clock.instant()))
                throw invalid("Every generated meeting occurrence must be in the future.");
            VideoMeetingDtos.ScheduleMeetingRequest occurrenceRequest = occurrenceRequest(
                    meeting, occurrence.toOffsetDateTime());
            String occurrenceKey = UUID.nameUUIDFromBytes(
                    ("dwp-meeting-series-v1|" + subject.tenantId() + "|" + subject.userId()
                            + "|" + key + "|" + (index + 1)).getBytes(StandardCharsets.UTF_8))
                    .toString();
            var result = meetings.schedule(occurrenceRequest, occurrenceKey, correlationId);
            schedules.linkOccurrence(subject.tenantId(), seriesId, index + 1,
                    result.meeting().meetingId(), local);
            if (first == null) first = result;
        }
        if (first == null) throw new IllegalStateException("A validated series has no occurrences.");
        audit.workspaceChanged(subject, "meeting.series.created", "MEETING_SERIES",
                seriesId.toString(), correlation(correlationId), Map.of(
                        "frequency", recurrence.frequency(),
                        "interval", recurrence.interval(),
                        "occurrenceCount", recurrence.occurrenceCount()));
        return first;
    }

    @Transactional(readOnly = true)
    public SeriesPreviewResponse previewSeries(SeriesPreviewRequest request) {
        subject("APP.MEETINGS:CREATE");
        if (request == null || request.meeting() == null || request.recurrence() == null)
            throw invalid("A recurrence rule and meeting are required.");
        validateRecurrence(request.recurrence());
        var meeting = request.meeting();
        VideoMeetingEntryPolicy.requireSupportedCreation(
                meeting.accessScope(), meeting.guestAccessEnabled(),
                meeting.allowJoinBeforeHost(), meeting.guestInvitees());
        ZoneId zone = zone(meeting.timeZone());
        if (meeting.startsAt() == null || !meeting.startsAt().toInstant().isAfter(clock.instant()))
            throw invalid("The recurring meeting must start in the future.");
        ZonedDateTime anchor = validatedStart(meeting.startsAt(), zone);
        List<OccurrenceProjection> projected = java.util.stream.IntStream
                .range(0, request.recurrence().occurrenceCount())
                .mapToObj(index -> projectOccurrence(anchor.toLocalDateTime(), request.recurrence(),
                        index, zone, anchor.getOffset()))
                .toList();
        if (projected.stream().anyMatch(item -> !item.start().toInstant().isAfter(clock.instant())))
            throw invalid("Every generated meeting occurrence must be in the future.");
        String fingerprint = requestHash(seriesDigest(meeting, request.recurrence()),
                projected.stream().map(item -> List.of(item.index(), item.start().toOffsetDateTime(),
                        item.localStart(), item.adjustment())).toList());
        return new SeriesPreviewResponse(fingerprint,
                projected.stream().anyMatch(item -> !"NONE".equals(item.adjustment())),
                projected.stream().map(item -> new OccurrencePreview(
                        item.index(), item.start().toOffsetDateTime(), item.localStart().toString(),
                        item.start().getOffset().toString(), item.adjustment())).toList());
    }

    @Transactional(readOnly = true)
    public ScheduleStateResponse read(UUID meetingId) {
        var subject = subject("APP.MEETINGS:VIEW");
        return schedules.state(accessible(subject, meetingId, false));
    }

    @Transactional(readOnly = true)
    public SeriesPreviewResponse previewReschedule(UUID meetingId, RescheduleRequest request) {
        var subject = subject("APP.MEETINGS:UPDATE");
        if (request == null) throw invalid("A schedule update is required.");
        Meeting meeting = accessible(subject, meetingId, false);
        requireHost(subject, meeting);
        requirePreparatory(meeting);
        validateExpected(request.expectedVersion(), meeting.version());
        return reschedulePreview(meeting, request, false);
    }

    @Transactional(readOnly = true)
    public CancellationPreviewResponse previewCancellation(
            UUID meetingId, CancelPreviewRequest request) {
        var subject = subject("APP.MEETINGS:UPDATE");
        if (request == null) throw invalid("A cancellation preview is required.");
        Meeting meeting = accessible(subject, meetingId, false);
        requireHost(subject, meeting);
        requirePreparatory(meeting);
        validateExpected(request.expectedVersion(), meeting.version());
        return cancellationPreview(meeting, request.scope(), request.expectedSeriesVersion(), false);
    }

    @Transactional
    public ScheduleStateResponse reschedule(UUID meetingId, RescheduleRequest request,
            String idempotencyKey, String correlationId) {
        var subject = subject("APP.MEETINGS:UPDATE");
        if (request == null) throw invalid("A schedule update is required.");
        Meeting meeting = accessible(subject, meetingId, true);
        requireHost(subject, meeting);
        ZoneId zone = zone(request.timeZone());
        String key = commandKey(idempotencyKey);
        String digest = requestHash(request.startsAt(), request.durationMinutes(), zone.getId(),
                request.scope(), request.expectedSeriesVersion(), request.expectedVersion(),
                request.calendarFingerprint());
        ScheduleStateResponse replay = schedules.replay(
                meeting, subject.userId(), "RESCHEDULE", key, digest).orElse(null);
        if (replay != null) return replay;
        requirePreparatory(meeting);
        validateExpected(request.expectedVersion(), meeting.version());
        ZonedDateTime requestedStart = validatedStart(request.startsAt(), zone);

        SeriesPreviewResponse preview = reschedulePreview(meeting, request, true);
        if (request.calendarFingerprint() == null || !requestHashesMatch(
                preview.previewFingerprint(), request.calendarFingerprint()))
            throw conflict("The occurrence preview changed. Review it again before rescheduling.");
        if ("THIS_AND_FUTURE".equals(request.scope())) {
            meeting = rescheduleFuture(subject, meeting, request, requestedStart, correlationId);
        } else if ("THIS_ONLY".equals(request.scope()) && request.expectedSeriesVersion() == null) {
            Meeting updated = updateOne(subject, meeting, requestedStart,
                    request.durationMinutes(), zone, request.expectedVersion(), correlationId);
            schedules.markOccurrenceException(updated, "RESCHEDULED");
            meeting = updated;
        } else {
            throw invalid("The schedule mutation scope is inconsistent.");
        }
        ScheduleStateResponse result = schedules.state(meeting);
        schedules.complete(meeting, subject.userId(), "RESCHEDULE", key, digest, result);
        return result;
    }

    @Transactional
    public ScheduleStateResponse cancel(UUID meetingId, CancelRequest request,
            String idempotencyKey, String correlationId) {
        var subject = subject("APP.MEETINGS:UPDATE");
        if (request == null) throw invalid("A cancellation request is required.");
        Meeting meeting = accessible(subject, meetingId, true);
        requireHost(subject, meeting);
        String key = commandKey(idempotencyKey);
        String digest = requestHash(request.scope(), request.expectedSeriesVersion(),
                request.expectedVersion(), request.impactFingerprint());
        ScheduleStateResponse replay = schedules.replay(
                meeting, subject.userId(), "CANCEL", key, digest).orElse(null);
        if (replay != null) return replay;
        requirePreparatory(meeting);
        validateExpected(request.expectedVersion(), meeting.version());
        CancellationPreviewResponse impact = cancellationPreview(
                meeting, request.scope(), request.expectedSeriesVersion(), true);
        if (request.impactFingerprint() == null || !requestHashesMatch(
                impact.impactFingerprint(), request.impactFingerprint()))
            throw conflict("The cancellation impact changed. Review it again before cancelling.");

        if ("THIS_AND_FUTURE".equals(request.scope())) {
            meeting = cancelFuture(subject, meeting, request, correlationId);
        } else if ("THIS_ONLY".equals(request.scope()) && request.expectedSeriesVersion() == null) {
            meeting = cancelOne(subject, meeting, request.expectedVersion(), correlationId);
        } else {
            throw invalid("The cancellation scope is inconsistent.");
        }
        ScheduleStateResponse result = schedules.state(meeting);
        schedules.complete(meeting, subject.userId(), "CANCEL", key, digest, result);
        return result;
    }

    private Meeting rescheduleFuture(MeetingRequestContext.Subject subject, Meeting target,
            RescheduleRequest request, ZonedDateTime requestedStart, String correlationId) {
        var series = requiredSeries(target, request.expectedSeriesVersion());
        LocalDateTime firstAnchor = shift(requestedStart.toLocalDateTime(), series.frequency(),
                series.interval(), -(series.occurrenceIndex() - 1));
        schedules.updateSeriesAnchor(subject.tenantId(), series, series.version(), firstAnchor,
                requestedStart.getZone().getId(), request.durationMinutes(), subject.userId());
        List<Meeting> future = meetingRepository.lockSeriesMeetings(
                subject.tenantId(), series.seriesId(), series.occurrenceIndex());
        Meeting updatedTarget = null;
        for (int offset = 0; offset < future.size(); offset++) {
            Meeting occurrence = future.get(offset);
            if (occurrence.terminal() || occurrence.lifecycleState() == LifecycleState.LIVE) continue;
            long expected = occurrence.meetingId().equals(target.meetingId())
                    ? request.expectedVersion() : occurrence.version();
            ZonedDateTime start = projectLocal(
                    shift(requestedStart.toLocalDateTime(), series.frequency(), series.interval(), offset),
                    requestedStart.getZone(), requestedStart.getOffset()).start();
            Meeting updated = updateOne(subject, occurrence, start,
                    request.durationMinutes(), requestedStart.getZone(), expected, correlationId);
            schedules.markOccurrenceException(updated, "RESCHEDULED");
            if (updated.meetingId().equals(target.meetingId())) updatedTarget = updated;
        }
        if (updatedTarget == null) throw conflict("The selected occurrence could not be rescheduled.");
        return updatedTarget;
    }

    private SeriesPreviewResponse reschedulePreview(
            Meeting meeting, RescheduleRequest request, boolean lockSeries) {
        if (request.startsAt() == null || request.durationMinutes() < 5
                || request.durationMinutes() > 1440 || request.scope() == null)
            throw invalid("The schedule preview input is invalid.");
        ZoneId requestedZone = zone(request.timeZone());
        ZonedDateTime requestedStart = validatedStart(request.startsAt(), requestedZone);
        if ("THIS_ONLY".equals(request.scope())) {
            if (request.expectedSeriesVersion() != null)
                throw invalid("A single-occurrence preview cannot include a series version.");
            var state = schedules.state(meeting);
            OccurrencePreview occurrence = new OccurrencePreview(1,
                    requestedStart.toOffsetDateTime(), requestedStart.toLocalDateTime().toString(),
                    requestedStart.getOffset().toString(), "NONE");
            return new SeriesPreviewResponse(requestHash("RESCHEDULE_PREVIEW_V1",
                    meeting.meetingId(), request.expectedVersion(), request.startsAt(),
                    request.durationMinutes(), requestedZone.getId(), request.scope(),
                    meeting.lifecycleState(), state.invitationRevision()),
                    false, List.of(occurrence));
        }
        if (!"THIS_AND_FUTURE".equals(request.scope()))
            throw invalid("The schedule mutation scope is invalid.");
        var series = (lockSeries ? schedules.seriesContext(meeting)
                : schedules.readSeriesContext(meeting))
                .orElseThrow(() -> invalid("This meeting is not part of a series."));
        if (request.expectedSeriesVersion() == null
                || request.expectedSeriesVersion() != series.version())
            throw conflict("The meeting series changed. Refresh and retry.");
        RecurrenceRequest recurrence = new RecurrenceRequest(
                series.frequency(), series.interval(), series.occurrenceCount());
        var candidates = schedules.mutationCandidates(
                meeting, series.seriesId(), series.occurrenceIndex(), lockSeries);
        List<OccurrenceProjection> projected = candidates.stream()
                .filter(item -> mutable(item.lifecycleState()))
                .map(candidate -> {
                    int offset = candidate.occurrenceIndex() - series.occurrenceIndex();
                    OccurrenceProjection item = projectOccurrence(
                            requestedStart.toLocalDateTime(), recurrence, offset,
                            requestedZone, requestedStart.getOffset());
                    return new OccurrenceProjection(candidate.occurrenceIndex(),
                            item.start(), item.localStart(), item.adjustment());
                }).toList();
        if (projected.isEmpty())
            throw conflict("No mutable occurrence remains in the selected scope.");
        String fingerprint = requestHash("RESCHEDULE_FUTURE_PREVIEW_V1", meeting.meetingId(),
                request.expectedVersion(), series.seriesId(), series.version(), request.startsAt(),
                request.durationMinutes(), requestedZone.getId(), request.scope(),
                candidates.stream().map(item -> List.of(item.occurrenceIndex(), item.meetingId(),
                        item.lifecycleState(), item.version(), item.invitationRevision())).toList(),
                projected.stream().map(item -> List.of(item.index(), item.start().toOffsetDateTime(),
                        item.localStart(), item.adjustment())).toList());
        return new SeriesPreviewResponse(fingerprint,
                projected.stream().anyMatch(item -> !"NONE".equals(item.adjustment())),
                projected.stream().map(item -> new OccurrencePreview(item.index(),
                        item.start().toOffsetDateTime(), item.localStart().toString(),
                        item.start().getOffset().toString(), item.adjustment())).toList());
    }

    private CancellationPreviewResponse cancellationPreview(Meeting meeting, String scope,
            Long expectedSeriesVersion, boolean lock) {
        if ("THIS_ONLY".equals(scope)) {
            if (expectedSeriesVersion != null)
                throw invalid("A single-occurrence cancellation cannot include a series version.");
            var state = schedules.state(meeting);
            String fingerprint = requestHash("CANCEL_IMPACT_V1", meeting.meetingId(),
                    meeting.version(), meeting.lifecycleState(), state.invitationRevision());
            return new CancellationPreviewResponse(
                    fingerprint, scope, 1, 0, state.invitationRevision(), null);
        }
        if (!"THIS_AND_FUTURE".equals(scope))
            throw invalid("The cancellation scope is invalid.");
        var series = (lock ? schedules.seriesContext(meeting)
                : schedules.readSeriesContext(meeting))
                .orElseThrow(() -> invalid("This meeting is not part of a series."));
        if (expectedSeriesVersion == null || expectedSeriesVersion != series.version())
            throw conflict("The meeting series changed. Refresh and retry.");
        var candidates = schedules.mutationCandidates(
                meeting, series.seriesId(), series.occurrenceIndex(), lock);
        int affected = (int) candidates.stream()
                .filter(item -> mutable(item.lifecycleState())).count();
        if (affected < 1) throw conflict("No mutable occurrence remains in the selected scope.");
        int skipped = candidates.size() - affected;
        long invitationRevision = candidates.stream()
                .filter(item -> item.meetingId().equals(meeting.meetingId()))
                .findFirst().map(VideoMeetingScheduleRepository.ScheduleMutationCandidate::invitationRevision)
                .orElseThrow(() -> conflict("The selected occurrence is no longer in the series."));
        String fingerprint = requestHash("CANCEL_IMPACT_V1", meeting.meetingId(),
                series.seriesId(), series.version(), scope,
                candidates.stream().map(item -> List.of(item.occurrenceIndex(), item.meetingId(),
                        item.lifecycleState(), item.version(), item.invitationRevision())).toList());
        return new CancellationPreviewResponse(
                fingerprint, scope, affected, skipped, invitationRevision, series.version());
    }

    private Meeting cancelFuture(MeetingRequestContext.Subject subject, Meeting target,
            CancelRequest request, String correlationId) {
        var series = requiredSeries(target, request.expectedSeriesVersion());
        schedules.bumpSeriesVersion(subject.tenantId(), series, series.version(), subject.userId());
        Meeting updatedTarget = null;
        for (Meeting occurrence : meetingRepository.lockSeriesMeetings(
                subject.tenantId(), series.seriesId(), series.occurrenceIndex())) {
            if (occurrence.terminal() || occurrence.lifecycleState() == LifecycleState.LIVE) continue;
            long expected = occurrence.meetingId().equals(target.meetingId())
                    ? request.expectedVersion() : occurrence.version();
            Meeting updated = cancelOne(subject, occurrence, expected, correlationId);
            if (updated.meetingId().equals(target.meetingId())) updatedTarget = updated;
        }
        if (updatedTarget == null) throw conflict("The selected occurrence could not be cancelled.");
        return updatedTarget;
    }

    private Meeting updateOne(MeetingRequestContext.Subject subject, Meeting meeting,
            ZonedDateTime start, int durationMinutes, ZoneId zone,
            long expectedVersion, String correlationId) {
        if (durationMinutes < 5 || durationMinutes > 1440)
            throw invalid("The meeting duration is invalid.");
        Meeting updated = meetingRepository.reschedule(meeting, start.toOffsetDateTime(),
                start.plusMinutes(durationMinutes).toOffsetDateTime(), zone.getId(),
                subject.userId(), expectedVersion);
        schedules.recordInvitationEvent(updated, "MEETING_RESCHEDULED", updated.version());
        audit.meetingLifecycle(subject, updated, "meeting.rescheduled", correlation(correlationId),
                Map.of("meetingVersion", updated.version(), "scope", "SCHEDULE"));
        return updated;
    }

    private Meeting cancelOne(MeetingRequestContext.Subject subject, Meeting meeting,
            long expectedVersion, String correlationId) {
        Meeting updated = meetingRepository.cancel(meeting, subject.userId(), expectedVersion);
        schedules.markOccurrenceException(updated, "CANCELLED");
        schedules.recordInvitationEvent(updated, "MEETING_CANCELLED", updated.version());
        audit.meetingLifecycle(subject, updated, "meeting.cancelled", correlation(correlationId),
                Map.of("meetingVersion", updated.version(), "scope", "SCHEDULE"));
        return updated;
    }

    private VideoMeetingScheduleRepository.SeriesContext requiredSeries(
            Meeting meeting, Long expectedSeriesVersion) {
        var series = schedules.seriesContext(meeting)
                .orElseThrow(() -> invalid("This meeting is not part of a series."));
        if (expectedSeriesVersion == null || expectedSeriesVersion != series.version())
            throw conflict("The meeting series changed. Refresh and retry.");
        return series;
    }

    private VideoMeetingDtos.MeetingCreatedResponse created(UUID meetingId) {
        VideoMeetingDtos.MeetingDetailResponse detail = meetings.detail(meetingId);
        return new VideoMeetingDtos.MeetingCreatedResponse(detail, detail.meetingCode());
    }

    private MeetingRequestContext.Subject subject(String permission) {
        var subject = MeetingRequestContext.get();
        if (!subject.permissions().contains(permission)
                && !subject.permissions().contains("APP.MEETINGS:MANAGE")) throw forbidden();
        return subject;
    }

    private Meeting accessible(MeetingRequestContext.Subject subject, UUID meetingId, boolean lock) {
        return (lock
                ? meetingRepository.lockAccessibleMeeting(subject.tenantId(), meetingId, subject.userId())
                : meetingRepository.accessibleMeeting(subject.tenantId(), meetingId, subject.userId()))
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND,
                        "The meeting schedule was not found."));
    }

    private void requireHost(MeetingRequestContext.Subject subject, Meeting meeting) {
        Participant participant = meetingRepository.participant(
                subject.tenantId(), meeting.meetingId(), subject.userId()).orElse(null);
        if (meeting.organizerUserId() != subject.userId()
                && (participant == null || !participant.canHost())) throw forbidden();
    }

    private void requirePreparatory(Meeting meeting) {
        if (!mutable(meeting.lifecycleState()))
            throw conflict("Only an upcoming meeting can be changed.");
    }

    private boolean mutable(LifecycleState state) {
        return Set.of(LifecycleState.DRAFT, LifecycleState.SCHEDULED, LifecycleState.LOBBY)
                .contains(state);
    }

    private void validateExpected(Long expected, long current) {
        if (expected == null || expected < 0 || expected != current)
            throw conflict("The meeting changed. Refresh and retry.");
    }

    private ZonedDateTime validatedStart(OffsetDateTime start, ZoneId zone) {
        if (start == null || !start.toInstant().isAfter(clock.instant()))
            throw invalid("The meeting start must be in the future.");
        LocalDateTime local = start.toLocalDateTime();
        if (!zone.getRules().getValidOffsets(local).contains(start.getOffset()))
            throw invalid("The meeting offset does not match the selected time zone.");
        return ZonedDateTime.ofLocal(local, zone, start.getOffset());
    }

    static OccurrenceProjection projectLocal(LocalDateTime local, ZoneId zone, ZoneOffset preferred) {
        List<ZoneOffset> valid = zone.getRules().getValidOffsets(local);
        if (!valid.isEmpty()) {
            ZoneOffset selected = valid.contains(preferred) ? preferred : valid.getFirst();
            return new OccurrenceProjection(0, ZonedDateTime.ofLocal(local, zone, selected), local,
                    valid.size() > 1 ? "DST_OVERLAP_EXPLICIT_OFFSET" : "NONE");
        }
        ZoneOffsetTransition transition = zone.getRules().getTransition(local);
        if (transition == null) throw invalid("The recurring local time cannot be resolved.");
        LocalDateTime adjusted = local.plusSeconds(transition.getDuration().getSeconds());
        return new OccurrenceProjection(0, adjusted.atZone(zone), local,
                "DST_GAP_SHIFT_FORWARD");
    }

    static OccurrenceProjection projectOccurrence(LocalDateTime anchor,
            RecurrenceRequest recurrence, int zeroBasedIndex, ZoneId zone, ZoneOffset preferred) {
        LocalDateTime local = shift(anchor, recurrence.frequency(), recurrence.interval(), zeroBasedIndex);
        OccurrenceProjection time = projectLocal(local, zone, preferred);
        String adjustment = time.adjustment();
        if ("MONTHLY".equals(recurrence.frequency()) && local.getDayOfMonth() != anchor.getDayOfMonth())
            adjustment = "MONTH_END_CLAMPED";
        return new OccurrenceProjection(zeroBasedIndex + 1, time.start(), local, adjustment);
    }

    private static LocalDateTime shift(LocalDateTime value, String frequency, int interval, int index) {
        long amount = Math.multiplyExact((long) interval, index);
        return "WEEKLY".equals(frequency) ? value.plusWeeks(amount) : value.plusMonths(amount);
    }

    private ZoneId zone(String value) {
        try {
            return ZoneId.of(value == null ? "" : value.trim());
        } catch (DateTimeException exception) {
            throw invalid("The time zone is invalid.");
        }
    }

    private String seriesDigest(VideoMeetingDtos.ScheduleMeetingRequest meeting,
            RecurrenceRequest recurrence) {
        return requestHash("SERIES_V1", meeting.title(), meeting.description(), meeting.agenda(),
                meeting.startsAt(), meeting.durationMinutes(), meeting.timeZone(), meeting.accessScope(),
                meeting.waitingRoomEnabled(), meeting.guestAccessEnabled(), meeting.allowJoinBeforeHost(),
                meeting.defaultMicrophoneEnabled(), meeting.defaultCameraEnabled(),
                canonicalUserIds(meeting.participantUserIds()), canonicalGuests(meeting.guestInvitees()),
                meeting.agendaItems(), meeting.sourceTemplateId(), meeting.sourceTemplateVersion(),
                recurrence.frequency(), recurrence.interval(), recurrence.occurrenceCount());
    }

    private void validateRecurrence(RecurrenceRequest recurrence) {
        if (recurrence == null || !Set.of("WEEKLY", "MONTHLY").contains(recurrence.frequency())
                || recurrence.interval() < 1 || recurrence.interval() > 12
                || recurrence.occurrenceCount() < 2 || recurrence.occurrenceCount() > 52)
            throw invalid("The recurrence rule is invalid.");
    }

    private VideoMeetingDtos.ScheduleMeetingRequest occurrenceRequest(
            VideoMeetingDtos.ScheduleMeetingRequest source, OffsetDateTime startsAt) {
        return new VideoMeetingDtos.ScheduleMeetingRequest(
                source.title(), source.description(), source.agenda(), startsAt,
                source.durationMinutes(), source.timeZone(), source.accessScope(),
                source.waitingRoomEnabled(), source.guestAccessEnabled(),
                source.allowJoinBeforeHost(), source.defaultMicrophoneEnabled(),
                source.defaultCameraEnabled(), source.participantUserIds(), source.guestInvitees(),
                source.agendaItems(), source.sourceTemplateId(), source.sourceTemplateVersion());
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
    }

    private static BaseException forbidden() {
        return new BaseException(ErrorCode.FORBIDDEN, "Meeting schedule authority is required.");
    }

    record OccurrenceProjection(
            int index, ZonedDateTime start, LocalDateTime localStart, String adjustment) { }
}
