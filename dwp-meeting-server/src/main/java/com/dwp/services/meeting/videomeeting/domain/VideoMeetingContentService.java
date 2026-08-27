package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingContentDtos;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.BlockerCode;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ConsentCounts;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ContentNotice;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ContentPlan;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.NoticeAcknowledgement;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.PlanState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.RecordingSession;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.RecordingState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.StoredCommand;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AttendanceState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.TenantPolicy;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.commandKey;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.correlation;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHash;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHashesMatch;

@Service
public class VideoMeetingContentService {

    private static final String PLAN_UPDATE = "PLAN_UPDATE";
    private static final String NOTICE_ACK = "NOTICE_ACK";
    private static final String RECORDING_REQUEST = "RECORDING_REQUEST";
    private static final String RECORDING_STOP = "RECORDING_STOP";

    private final VideoMeetingRepository meetings;
    private final VideoMeetingContentRepository content;
    private final MeetingMediaProvider mediaProvider;
    private final MeetingContentDependencies dependencies;
    private final VideoMeetingAuditRecorder audit;
    private final Clock clock;

    @Autowired
    public VideoMeetingContentService(
            VideoMeetingRepository meetings,
            VideoMeetingContentRepository content,
            MeetingMediaProvider mediaProvider,
            MeetingContentDependencies dependencies,
            VideoMeetingAuditRecorder audit) {
        this(meetings, content, mediaProvider, dependencies, audit, Clock.systemUTC());
    }

    VideoMeetingContentService(
            VideoMeetingRepository meetings,
            VideoMeetingContentRepository content,
            MeetingMediaProvider mediaProvider,
            MeetingContentDependencies dependencies,
            VideoMeetingAuditRecorder audit,
            Clock clock) {
        this.meetings = meetings;
        this.content = content;
        this.mediaProvider = mediaProvider;
        this.dependencies = dependencies;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public VideoMeetingContentDtos.ContentPlanResponse contentPlan(UUID meetingId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = meetings.accessibleMeeting(
                        subject.tenantId(), meetingId, subject.userId())
                .orElseThrow(this::notFound);
        Participant viewer = requireMember(subject, meeting);
        TenantPolicy policy = meetings.ensurePolicy(subject.tenantId(), subject.userId());
        ContentPlan plan = content.ensurePlan(
                subject.tenantId(), meetingId, subject.userId());
        return response(meeting, viewer, policy, plan);
    }

    @Transactional
    public VideoMeetingContentDtos.ContentPlanResponse updateContentPlan(
            UUID meetingId,
            VideoMeetingContentDtos.UpdateContentPlanCommand request,
            String idempotencyKey,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        Participant host = requireHost(subject, meeting);
        TenantPolicy policy = meetings.ensurePolicy(subject.tenantId(), subject.userId());
        ContentPlan current = content.ensurePlan(
                subject.tenantId(), meetingId, subject.userId());
        String key = commandKey(idempotencyKey);
        String hash = requestHash(
                meetingId, request.recordingRequested(), request.transcriptionRequested(),
                request.aiSummaryRequested(), request.e2eeEnabled(), request.expectedVersion());
        Optional<StoredCommand> prior = priorCommand(subject, meetingId, PLAN_UPDATE, key, hash);
        if (prior.isPresent()) return response(meeting, host, policy,
                content.plan(subject.tenantId(), meetingId).orElse(current));
        if (current.version() != request.expectedVersion()) throw versionConflict();

        boolean changed = changed(current, request);
        if (changed && content.activeSession(subject.tenantId(), meetingId).isPresent()) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Stop the active recording request before changing the content plan.");
        }
        ContentPlan updated = current;
        if (changed) {
            boolean processingRequested = request.recordingRequested()
                    || request.transcriptionRequested() || request.aiSummaryRequested();
            MeetingContentDependencies.Status dependencyStatus = dependencies.status();
            ContentPlan candidate = candidate(current, request);
            List<BlockerCode> staticBlockers = blockers(
                    meeting, policy, candidate, dependencyStatus, false, false);
            PlanState state = processingRequested
                    ? staticBlockers.isEmpty() ? PlanState.READY : PlanState.BLOCKED
                    : PlanState.DISABLED;
            UUID noticeId = processingRequested ? UUID.randomUUID() : null;
            int noticeRevision = current.noticeRevision() + (processingRequested ? 1 : 0);
            updated = content.updatePlan(
                    current, request.recordingRequested(), request.transcriptionRequested(),
                    request.aiSummaryRequested(), request.e2eeEnabled(), state,
                    noticeId, noticeRevision, subject.userId(), now());
            audit.collaboration(
                    subject, meeting, "meeting.content-plan.updated", "MEETING_CONTENT_PLAN",
                    updated.planId().toString(), correlation(correlationId), true,
                    Map.of(
                            "planVersion", updated.version(),
                            "planState", updated.state().name(),
                            "recordingRequested", updated.recordingRequested(),
                            "transcriptionRequested", updated.transcriptionRequested(),
                            "aiSummaryRequested", updated.aiSummaryRequested(),
                            "e2eeEnabled", updated.e2eeEnabled(),
                            "noticeRevision", updated.noticeRevision()));
        }
        content.saveCommand(
                subject.tenantId(), meetingId, subject.userId(), PLAN_UPDATE, key, hash,
                true, 200, List.of(), updated.planId(), updated.version());
        return response(meeting, host, policy, updated);
    }

    @Transactional
    public VideoMeetingContentDtos.NoticeAcknowledgementResponse acknowledgeNotice(
            UUID meetingId,
            UUID noticeId,
            String idempotencyKey,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        Participant participant = requireMember(subject, meeting);
        ContentPlan plan = content.ensurePlan(
                subject.tenantId(), meetingId, subject.userId());
        String key = commandKey(idempotencyKey);
        String hash = requestHash(meetingId, noticeId, participant.participantId());
        Optional<StoredCommand> prior = priorCommand(subject, meetingId, NOTICE_ACK, key, hash);
        if (prior.isPresent()) {
            NoticeAcknowledgement stored = content.acknowledgement(
                            subject.tenantId(), meetingId, prior.get().resultResourceId())
                    .orElseThrow(() -> new BaseException(
                            ErrorCode.INVALID_STATE,
                            "The stored notice acknowledgement is unavailable."));
            ContentNotice storedNotice = content.notice(
                            subject.tenantId(), meetingId, stored.noticeId())
                    .orElseThrow(() -> new BaseException(
                            ErrorCode.INVALID_STATE,
                            "The stored processing notice is unavailable."));
            return acknowledgementResponse(stored, storedNotice);
        }
        ContentNotice notice = currentNotice(subject, plan);
        if (!notice.noticeId().equals(noticeId)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The processing notice changed. Refresh and acknowledge the current notice.");
        }
        OffsetDateTime acknowledgedAt = now();
        NoticeAcknowledgement acknowledgement = content.acknowledge(
                subject.tenantId(), meetingId, noticeId, participant.participantId(),
                subject.userId(), acknowledgedAt);
        content.saveCommand(
                subject.tenantId(), meetingId, subject.userId(), NOTICE_ACK, key, hash,
                true, 200, List.of(), acknowledgement.acknowledgementId(), plan.version());
        audit.collaboration(
                subject, meeting, "meeting.content-notice.acknowledged",
                "MEETING_CONTENT_NOTICE", noticeId.toString(),
                correlation(correlationId), false,
                Map.of(
                        "noticeRevision", notice.revision(),
                        "participantId", participant.participantId().toString()));
        return acknowledgementResponse(acknowledgement, notice);
    }

    @Transactional
    public VideoMeetingContentDtos.RecordingCommandResult requestRecording(
            UUID meetingId,
            VideoMeetingContentDtos.RequestRecordingCommand request,
            String idempotencyKey,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        requireHost(subject, meeting);
        TenantPolicy policy = meetings.ensurePolicy(subject.tenantId(), subject.userId());
        ContentPlan plan = content.ensurePlan(
                subject.tenantId(), meetingId, subject.userId());
        String key = commandKey(idempotencyKey);
        String hash = requestHash(meetingId, request.expectedPlanVersion());
        Optional<StoredCommand> prior = priorCommand(
                subject, meetingId, RECORDING_REQUEST, key, hash);
        if (prior.isPresent()) return storedResult(subject, plan, prior.get());
        if (plan.version() != request.expectedPlanVersion()) throw versionConflict();

        MeetingContentDependencies.Status dependencyStatus = dependencies.status();
        List<BlockerCode> blockers = blockers(
                meeting, policy, plan, dependencyStatus, true, true);
        Optional<RecordingSession> active = content.activeSession(
                subject.tenantId(), meetingId);
        if (!blockers.isEmpty()) {
            return blockedCommand(
                    subject, meeting, plan, RECORDING_REQUEST, key, hash, blockers,
                    active.orElse(null), "meeting.recording.request-blocked", correlationId);
        }
        RecordingSession session = active.orElseGet(() -> content.requestRecording(
                plan, currentNotice(subject, plan), subject.userId(), now()));
        content.saveCommand(
                subject.tenantId(), meetingId, subject.userId(), RECORDING_REQUEST,
                key, hash, true, 200, List.of(),
                session.recordingSessionId(), plan.version());
        audit.collaboration(
                subject, meeting, "meeting.recording.requested", "MEETING_RECORDING_SESSION",
                session.recordingSessionId().toString(), correlation(correlationId), true,
                Map.of(
                        "planVersion", plan.version(),
                        "recordingState", session.state().name(),
                        "noticeRevision", plan.noticeRevision()));
        return result(200, true, session.state().name(), List.of(), session, plan.version());
    }

    @Transactional
    public VideoMeetingContentDtos.RecordingCommandResult stopRecording(
            UUID meetingId,
            VideoMeetingContentDtos.StopRecordingCommand request,
            String idempotencyKey,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        requireHost(subject, meeting);
        ContentPlan plan = content.ensurePlan(
                subject.tenantId(), meetingId, subject.userId());
        String key = commandKey(idempotencyKey);
        String hash = requestHash(meetingId, request.expectedSessionVersion());
        Optional<StoredCommand> prior = priorCommand(
                subject, meetingId, RECORDING_STOP, key, hash);
        if (prior.isPresent()) return storedResult(subject, plan, prior.get());

        Optional<RecordingSession> active = content.activeSession(
                subject.tenantId(), meetingId);
        if (active.isEmpty()) {
            return blockedCommand(
                    subject, meeting, plan, RECORDING_STOP, key, hash,
                    List.of(BlockerCode.RECORDING_NOT_ACTIVE), null,
                    "meeting.recording.stop-blocked", correlationId);
        }
        RecordingSession current = active.get();
        if (current.version() != request.expectedSessionVersion()) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The recording session changed. Refresh and retry.");
        }
        RecordingSession stopped = current.state() == RecordingState.STOP_REQUESTED
                ? current : content.requestStop(current, subject.userId(), now());
        MeetingContentDependencies.Status status = dependencies.status();
        List<BlockerCode> blockers = stopBlockers(status);
        if (!blockers.isEmpty()) {
            return blockedCommand(
                    subject, meeting, plan, RECORDING_STOP, key, hash, blockers, stopped,
                    "meeting.recording.stop-blocked", correlationId);
        }
        content.saveCommand(
                subject.tenantId(), meetingId, subject.userId(), RECORDING_STOP,
                key, hash, true, 200, List.of(),
                stopped.recordingSessionId(), plan.version());
        audit.collaboration(
                subject, meeting, "meeting.recording.stop-requested",
                "MEETING_RECORDING_SESSION", stopped.recordingSessionId().toString(),
                correlation(correlationId), true,
                Map.of("recordingState", stopped.state().name()));
        return result(200, true, stopped.state().name(), List.of(), stopped, plan.version());
    }

    private VideoMeetingContentDtos.ContentPlanResponse response(
            Meeting meeting,
            Participant viewer,
            TenantPolicy policy,
            ContentPlan plan) {
        MeetingContentDependencies.Status status = dependencies.status();
        ContentNotice notice = content.currentNotice(plan.tenantId(), plan.meetingId())
                .orElse(null);
        ConsentCounts consent = notice == null ? new ConsentCounts(0, 0)
                : content.consentCounts(plan.tenantId(), plan.meetingId(), notice.noticeId());
        boolean acknowledged = notice != null && content.acknowledgedBy(
                plan.tenantId(), plan.meetingId(), notice.noticeId(), viewer.participantId());
        List<BlockerCode> blockers = blockers(
                meeting, policy, plan, status, false, meeting.live());
        PlanState effectiveState = !plan.processingRequested() ? PlanState.DISABLED
                : blockers.isEmpty() ? PlanState.READY : PlanState.BLOCKED;
        ContentPlan effectivePlan = withState(plan, effectiveState);
        RecordingSession session = content.activeSession(plan.tenantId(), plan.meetingId())
                .orElse(null);
        return VideoMeetingContentDtos.ContentPlanResponse.from(
                effectivePlan, blockers, status, notice, acknowledged, consent, session);
    }

    private List<BlockerCode> blockers(
            Meeting meeting,
            TenantPolicy policy,
            ContentPlan plan,
            MeetingContentDependencies.Status status,
            boolean requireLiveRecording,
            boolean requireConsent) {
        if (!plan.processingRequested()) return List.of();
        LinkedHashSet<BlockerCode> blockers = new LinkedHashSet<>();
        if (!policy.meetingsEnabled()) blockers.add(BlockerCode.MEETINGS_DISABLED);
        if ("NEVER".equals(policy.recordingPolicy())) blockers.add(BlockerCode.POLICY_NEVER);
        if (plan.e2eeEnabled()) blockers.add(BlockerCode.E2EE);
        if (!mediaProvider.capability().available()) blockers.add(BlockerCode.MEDIA_PROVIDER);
        if (!status.auditAvailable()) blockers.add(BlockerCode.AUDIT);
        if (plan.recordingRequested() || plan.transcriptionRequested()) {
            if (!status.egressAvailable()) blockers.add(BlockerCode.EGRESS);
            if (!status.storageAvailable()) blockers.add(BlockerCode.STORAGE);
            if (!status.kmsAvailable()) blockers.add(BlockerCode.KMS);
        }
        if (plan.transcriptionRequested() && !status.speechToTextAvailable()) {
            blockers.add(BlockerCode.STT);
        }
        if (plan.aiSummaryRequested() && !status.languageModelAvailable()) {
            blockers.add(BlockerCode.LLM);
        }
        if (requireLiveRecording && !plan.recordingRequested()) {
            blockers.add(BlockerCode.PLAN_RECORDING_DISABLED);
        }
        if (requireLiveRecording && !meeting.live()) blockers.add(BlockerCode.MEETING_NOT_LIVE);
        if (requireConsent && plan.processingRequested() && !consentComplete(plan)) {
            blockers.add(BlockerCode.CONSENT);
        }
        return List.copyOf(blockers);
    }

    private boolean consentComplete(ContentPlan plan) {
        if (plan.currentNoticeId() == null) return false;
        return content.consentCounts(
                plan.tenantId(), plan.meetingId(), plan.currentNoticeId()).complete();
    }

    private List<BlockerCode> stopBlockers(MeetingContentDependencies.Status status) {
        LinkedHashSet<BlockerCode> blockers = new LinkedHashSet<>();
        if (!status.egressAvailable()) blockers.add(BlockerCode.EGRESS);
        if (!status.auditAvailable()) blockers.add(BlockerCode.AUDIT);
        return List.copyOf(blockers);
    }

    private VideoMeetingContentDtos.RecordingCommandResult blockedCommand(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            ContentPlan plan,
            String commandType,
            String key,
            String hash,
            List<BlockerCode> blockers,
            RecordingSession session,
            String auditAction,
            String correlationId) {
        int status = blockers.stream().anyMatch(BlockerCode::infrastructure) ? 503 : 409;
        content.saveCommand(
                subject.tenantId(), meeting.meetingId(), subject.userId(), commandType,
                key, hash, false, status, blockers,
                session == null ? null : session.recordingSessionId(), plan.version());
        audit.collaboration(
                subject, meeting, auditAction,
                session == null ? "MEETING_CONTENT_PLAN" : "MEETING_RECORDING_SESSION",
                session == null ? plan.planId().toString()
                        : session.recordingSessionId().toString(),
                correlation(correlationId), true,
                Map.of(
                        "planVersion", plan.version(),
                        "blockerCodes", blockers.stream().map(Enum::name).toList(),
                        "commandOutcome", "BLOCKED"));
        return result(status, false,
                session == null ? "BLOCKED" : session.state().name(),
                blockers, session, plan.version());
    }

    private VideoMeetingContentDtos.RecordingCommandResult storedResult(
            MeetingRequestContext.Subject subject,
            ContentPlan plan,
            StoredCommand stored) {
        RecordingSession session = stored.resultResourceId() == null ? null
                : content.recordingSession(
                        subject.tenantId(), plan.meetingId(), stored.resultResourceId())
                        .orElse(null);
        String state = stored.accepted() && session != null
                ? session.state().name() : session == null ? "BLOCKED" : session.state().name();
        return result(
                stored.httpStatus(), stored.accepted(), state,
                stored.blockers(), session, stored.resultVersion());
    }

    private VideoMeetingContentDtos.RecordingCommandResult result(
            int httpStatus,
            boolean accepted,
            String commandState,
            List<BlockerCode> blockers,
            RecordingSession session,
            long planVersion) {
        return new VideoMeetingContentDtos.RecordingCommandResult(
                httpStatus,
                new VideoMeetingContentDtos.RecordingCommandResponse(
                        accepted, commandState,
                        blockers.stream().map(
                                VideoMeetingContentDtos.BlockerResponse::from).toList(),
                        VideoMeetingContentDtos.RecordingSessionResponse.from(session),
                        planVersion));
    }

    private VideoMeetingContentDtos.NoticeAcknowledgementResponse acknowledgementResponse(
            NoticeAcknowledgement acknowledgement, ContentNotice notice) {
        return new VideoMeetingContentDtos.NoticeAcknowledgementResponse(
                acknowledgement.acknowledgementId(), acknowledgement.noticeId(),
                notice.revision(), acknowledgement.participantId(),
                acknowledgement.acknowledgedAt());
    }

    private Optional<StoredCommand> priorCommand(
            MeetingRequestContext.Subject subject,
            UUID meetingId,
            String commandType,
            String key,
            String hash) {
        Optional<StoredCommand> prior = content.command(
                subject.tenantId(), meetingId, subject.userId(), commandType, key);
        if (prior.isPresent() && !requestHashesMatch(prior.get().requestHash(), hash)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The idempotency key was already used for a different content command.");
        }
        return prior;
    }

    private ContentNotice currentNotice(
            MeetingRequestContext.Subject subject, ContentPlan plan) {
        if (plan.currentNoticeId() == null) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The meeting does not have a current processing notice.");
        }
        return content.currentNotice(subject.tenantId(), plan.meetingId())
                .filter(notice -> notice.noticeId().equals(plan.currentNoticeId()))
                .orElseThrow(() -> new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The current processing notice is unavailable."));
    }

    private Participant requireMember(
            MeetingRequestContext.Subject subject, Meeting meeting) {
        Participant participant = meetings.participant(
                        subject.tenantId(), meeting.meetingId(), subject.userId())
                .orElseThrow(this::notFound);
        if (participant.attendanceState() == AttendanceState.DENIED) throw notFound();
        return participant;
    }

    private Participant requireHost(
            MeetingRequestContext.Subject subject, Meeting meeting) {
        Participant participant = requireMember(subject, meeting);
        if (!participant.canHost()) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN, "A meeting host role is required.");
        }
        return participant;
    }

    private boolean changed(
            ContentPlan current,
            VideoMeetingContentDtos.UpdateContentPlanCommand request) {
        return current.recordingRequested() != request.recordingRequested()
                || current.transcriptionRequested() != request.transcriptionRequested()
                || current.aiSummaryRequested() != request.aiSummaryRequested()
                || current.e2eeEnabled() != request.e2eeEnabled();
    }

    private ContentPlan candidate(
            ContentPlan current,
            VideoMeetingContentDtos.UpdateContentPlanCommand request) {
        return new ContentPlan(
                current.planId(), current.tenantId(), current.meetingId(),
                request.recordingRequested(), request.transcriptionRequested(),
                request.aiSummaryRequested(), request.e2eeEnabled(), current.state(),
                current.currentNoticeId(), current.noticeRevision(), current.version(),
                current.updatedAt());
    }

    private ContentPlan withState(ContentPlan plan, PlanState state) {
        return new ContentPlan(
                plan.planId(), plan.tenantId(), plan.meetingId(),
                plan.recordingRequested(), plan.transcriptionRequested(),
                plan.aiSummaryRequested(), plan.e2eeEnabled(), state,
                plan.currentNoticeId(), plan.noticeRevision(), plan.version(), plan.updatedAt());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private BaseException notFound() {
        return new BaseException(ErrorCode.ENTITY_NOT_FOUND, "The meeting was not found.");
    }

    private BaseException versionConflict() {
        return new BaseException(
                ErrorCode.OBJECT_VERSION_CONFLICT,
                "The meeting content plan changed. Refresh and retry.");
    }
}
