package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingContentDtos;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingCommandModels.CommandState;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingCommandModels.CommandType;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingCommandModels.Preparation;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingCommandModels.ProviderCommand;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.BlockerCode;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ContentNotice;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ContentPlan;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.PlanState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.RecordingSession;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.RecordingState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.StoredCommand;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AttendanceState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.TenantPolicy;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ConsentEvidence;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import com.dwp.services.meeting.videomeeting.provider.MeetingRecordingHttpProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingRecordingProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHashesMatch;

@Service
class MeetingRecordingCommandTransactions {

    private static final String RECORDING_REQUEST = "RECORDING_REQUEST";
    private static final String RECORDING_STOP = "RECORDING_STOP";

    private final VideoMeetingRepository meetings;
    private final VideoMeetingContentRepository content;
    private final VideoMeetingIntelligenceRepository intelligence;
    private final MeetingRecordingCommandRepository commands;
    private final MeetingRecordingHttpProperties properties;
    private final VideoMeetingAuditRecorder audit;
    private final Clock clock;

    @Autowired
    MeetingRecordingCommandTransactions(
            VideoMeetingRepository meetings,
            VideoMeetingContentRepository content,
            VideoMeetingIntelligenceRepository intelligence,
            MeetingRecordingCommandRepository commands,
            MeetingRecordingHttpProperties properties,
            VideoMeetingAuditRecorder audit) {
        this(meetings, content, intelligence, commands, properties, audit, Clock.systemUTC());
    }

    MeetingRecordingCommandTransactions(
            VideoMeetingRepository meetings,
            VideoMeetingContentRepository content,
            VideoMeetingIntelligenceRepository intelligence,
            MeetingRecordingCommandRepository commands,
            MeetingRecordingHttpProperties properties,
            VideoMeetingAuditRecorder audit,
            Clock clock) {
        this.meetings = meetings;
        this.content = content;
        this.intelligence = intelligence;
        this.commands = commands;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Preparation prepareStart(
            MeetingRequestContext.Subject subject,
            UUID meetingId,
            long expectedPlanVersion,
            String idempotencyKey,
            String requestSha256,
            String correlationId,
            MeetingContentDependencies.Status dependencyStatus,
            MeetingMediaProvider.Capability mediaCapability,
            MeetingRecordingProvider.Capability recordingCapability) {
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        Participant host = requireHost(subject, meeting);
        TenantPolicy policy = meetings.ensurePolicy(subject.tenantId(), subject.userId());
        ContentPlan plan = content.ensurePlan(subject.tenantId(), meetingId, subject.userId());
        if (plan.version() != expectedPlanVersion) throw versionConflict();
        List<BlockerCode> blockers = startBlockers(
                meeting, policy, plan, dependencyStatus, mediaCapability,
                recordingCapability);
        plan = reconcilePlan(subject, plan, staticBlockers(
                policy, plan, dependencyStatus, mediaCapability,
                recordingCapability));

        Optional<StoredCommand> prior = prior(
                subject, meetingId, RECORDING_REQUEST, idempotencyKey, requestSha256);
        if (prior.isPresent()) {
            return resumeOrReplay(
                    subject, meeting, plan, prior.get(), CommandType.START,
                    requestSha256, blockers, recordingCapability);
        }
        if (!blockers.isEmpty()) {
            return Preparation.replay(blocked(
                    subject, meeting, plan, RECORDING_REQUEST, idempotencyKey,
                    requestSha256, blockers, null, correlationId));
        }
        if (content.activeSession(subject.tenantId(), meetingId).isPresent()) {
            throw conflict("Another recording session is already active.");
        }
        ContentNotice notice = currentNotice(subject, plan);
        OffsetDateTime now = now();
        RecordingSession session = content.startRecordingCommand(
                plan, notice, policy.artifactRetentionDays(),
                recordingCapability.providerCode(), recordingCapability.processingRegion(),
                subject.userId(), now);
        content.saveCommand(
                subject.tenantId(), meetingId, subject.userId(), RECORDING_REQUEST,
                idempotencyKey, requestSha256, true, 200, List.of(),
                session.recordingSessionId(), plan.version());
        ProviderCommand command = insertCommand(
                subject, meeting, session, CommandType.START,
                idempotencyKey, requestSha256, correlationId,
                recordingCapability.providerCode(), now);
        audit.collaboration(
                subject, meeting, "meeting.recording.start-prepared",
                "MEETING_RECORDING_SESSION", session.recordingSessionId().toString(),
                correlationId, true,
                Map.of("planVersion", plan.version(), "recordingState", session.state().name(),
                        "provider", recordingCapability.providerCode(),
                        "attempt", command.attemptCount()));
        return Preparation.execute(meeting, plan, session, command);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Preparation prepareStop(
            MeetingRequestContext.Subject subject,
            UUID meetingId,
            long expectedSessionVersion,
            String idempotencyKey,
            String requestSha256,
            String correlationId,
            MeetingContentDependencies.Status dependencyStatus,
            MeetingRecordingProvider.Capability recordingCapability) {
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        requireHost(subject, meeting);
        ContentPlan plan = content.ensurePlan(subject.tenantId(), meetingId, subject.userId());
        Optional<StoredCommand> prior = prior(
                subject, meetingId, RECORDING_STOP, idempotencyKey, requestSha256);
        List<BlockerCode> blockers = stopBlockers(dependencyStatus, recordingCapability);
        if (prior.isPresent()) {
            return resumeOrReplay(
                    subject, meeting, plan, prior.get(), CommandType.STOP,
                    requestSha256, blockers, recordingCapability);
        }
        RecordingSession current = content.activeSession(subject.tenantId(), meetingId)
                .orElse(null);
        if (current == null) {
            return Preparation.replay(blocked(
                    subject, meeting, plan, RECORDING_STOP, idempotencyKey,
                    requestSha256, List.of(BlockerCode.RECORDING_NOT_ACTIVE),
                    null, correlationId));
        }
        if (current.version() != expectedSessionVersion) throw versionConflict();
        if (!sameProviderSnapshot(current, recordingCapability)) {
            return Preparation.replay(blocked(
                    subject, meeting, plan, RECORDING_STOP, idempotencyKey,
                    requestSha256, List.of(BlockerCode.EGRESS), current, correlationId));
        }
        if (!blockers.isEmpty()) {
            return Preparation.replay(blocked(
                    subject, meeting, plan, RECORDING_STOP, idempotencyKey,
                    requestSha256, blockers, current, correlationId));
        }
        ConsentEvidence consent = intelligence.consentEvidence(
                subject.tenantId(), meetingId, current.noticeId());
        if (!consent.complete() || consent.snapshotSha256() == null
                || !consent.snapshotSha256().matches("^[0-9a-f]{64}$")) {
            return Preparation.replay(blocked(
                    subject, meeting, plan, RECORDING_STOP, idempotencyKey,
                    requestSha256, List.of(BlockerCode.CONSENT), current, correlationId));
        }
        OffsetDateTime now = now();
        RecordingSession stopRequested = current.state() == RecordingState.STOP_REQUESTED
                ? current : content.requestStop(
                        current, consent.snapshotSha256(), subject.userId(), now);
        content.saveCommand(
                subject.tenantId(), meetingId, subject.userId(), RECORDING_STOP,
                idempotencyKey, requestSha256, true, 200, List.of(),
                stopRequested.recordingSessionId(), plan.version());
        ProviderCommand command = insertCommand(
                subject, meeting, stopRequested, CommandType.STOP,
                idempotencyKey, requestSha256, correlationId,
                recordingCapability.providerCode(), now);
        audit.collaboration(
                subject, meeting, "meeting.recording.stop-prepared",
                "MEETING_RECORDING_SESSION", stopRequested.recordingSessionId().toString(),
                correlationId, true,
                Map.of("recordingState", stopRequested.state().name(),
                        "provider", recordingCapability.providerCode(),
                        "attempt", command.attemptCount()));
        return Preparation.execute(meeting, plan, stopRequested, command);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VideoMeetingContentDtos.RecordingCommandResult succeed(
            MeetingRequestContext.Subject subject,
            Preparation prepared,
            String providerCommandId) {
        Meeting meeting = meetings.lockMeeting(
                prepared.command().tenantId(), prepared.command().meetingId());
        ProviderCommand command = current(prepared.command());
        RecordingSession session = content.recordingSession(
                        command.tenantId(), command.meetingId(), command.recordingSessionId())
                .orElseThrow(() -> conflict("The recording session is unavailable."));
        OffsetDateTime completedAt = now();
        RecordingSession completed = command.commandType() == CommandType.START
                ? content.markRecording(session, completedAt)
                : content.markStopped(session, completedAt);
        commands.succeed(command, providerCommandId, completedAt);
        audit.collaboration(
                subject, meeting,
                command.commandType() == CommandType.START
                        ? "meeting.recording.started" : "meeting.recording.stopped",
                "MEETING_RECORDING_SESSION", completed.recordingSessionId().toString(),
                command.correlationId(), true,
                Map.of("recordingState", completed.state().name(),
                        "provider", command.providerCode(),
                        "attempt", command.attemptCount()));
        return result(200, true, completed.state().name(), List.of(), completed,
                prepared.plan().version());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(
            MeetingRequestContext.Subject subject,
            Preparation prepared,
            String failureCode) {
        Meeting meeting = meetings.lockMeeting(
                prepared.command().tenantId(), prepared.command().meetingId());
        ProviderCommand command = current(prepared.command());
        RecordingSession session = content.recordingSession(
                        command.tenantId(), command.meetingId(), command.recordingSessionId())
                .orElseThrow(() -> conflict("The recording session is unavailable."));
        OffsetDateTime failedAt = now();
        if (command.commandType() == CommandType.START
                && session.state() == RecordingState.STARTING) {
            content.failRecordingStart(session, failureCode, failedAt);
        }
        commands.fail(command, failureCode, failedAt);
        audit.collaboration(
                subject, meeting, "meeting.recording.provider-failed",
                "MEETING_RECORDING_SESSION", session.recordingSessionId().toString(),
                command.correlationId(), true,
                Map.of("commandType", command.commandType().name(),
                        "failureCode", failureCode,
                        "provider", command.providerCode(),
                        "attempt", command.attemptCount()));
    }

    private Preparation resumeOrReplay(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            ContentPlan plan,
            StoredCommand stored,
            CommandType type,
            String requestSha256,
            List<BlockerCode> blockers,
            MeetingRecordingProvider.Capability capability) {
        if (!stored.accepted()) return Preparation.replay(storedResult(subject, plan, stored));
        RecordingSession session = content.recordingSession(
                        subject.tenantId(), meeting.meetingId(), stored.resultResourceId())
                .orElseThrow(() -> conflict("The recording command session is unavailable."));
        ProviderCommand command = commands.commandForUpdate(
                        subject.tenantId(), meeting.meetingId(), session.recordingSessionId(), type)
                .orElseThrow(() -> conflict(
                        "The recording command predates the governed provider boundary."));
        if (!requestHashesMatch(command.requestSha256(), requestSha256)) {
            throw conflict("The recording provider command request does not match.");
        }
        if (command.commandState() == CommandState.SUCCEEDED) {
            return Preparation.replay(result(
                    200, true, session.state().name(), List.of(), session, plan.version()));
        }
        OffsetDateTime now = now();
        if (command.commandState() == CommandState.RUNNING
                && command.leaseExpiresAt().isAfter(now)) {
            return Preparation.replay(result(
                    200, true, session.state().name(), List.of(), session, plan.version()));
        }
        if (!blockers.isEmpty() || !sameProviderSnapshot(session, capability)
                || !command.providerCode().equals(capability.providerCode())) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "The governed recording provider is not ready for command recovery.");
        }
        ProviderCommand reclaimed = commands.reclaim(
                        command, UUID.randomUUID(), now, now.plus(commandLease()))
                .orElseThrow(() -> conflict(
                        "Another worker reclaimed the recording command."));
        RecordingSession executable = type == CommandType.START
                && session.state() == RecordingState.FAILED
                ? content.resumeRecordingStart(session, now) : session;
        return Preparation.execute(meeting, plan, executable, reclaimed);
    }

    private ProviderCommand insertCommand(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            RecordingSession session,
            CommandType type,
            String idempotencyKey,
            String requestSha256,
            String correlationId,
            String providerCode,
            OffsetDateTime now) {
        ProviderCommand candidate = new ProviderCommand(
                UUID.randomUUID(), subject.tenantId(), meeting.meetingId(),
                session.recordingSessionId(), type, CommandState.RUNNING,
                subject.userId(), idempotencyKey, requestSha256, correlationId,
                UUID.randomUUID(), now.plus(commandLease()), 1, providerCode,
                null, null);
        return commands.insert(candidate, now)
                .orElseThrow(() -> conflict(
                        "Another governed recording command already exists."));
    }

    private ProviderCommand current(ProviderCommand prepared) {
        ProviderCommand current = commands.commandForUpdate(
                        prepared.tenantId(), prepared.meetingId(),
                        prepared.recordingSessionId(), prepared.commandType())
                .orElseThrow(() -> conflict("The recording provider command is unavailable."));
        OffsetDateTime now = now();
        if (current.commandState() != CommandState.RUNNING
                || !current.executionFence().equals(prepared.executionFence())
                || !current.leaseExpiresAt().isAfter(now)) {
            throw conflict("The recording provider command lease changed or expired.");
        }
        return current;
    }

    private ContentPlan reconcilePlan(
            MeetingRequestContext.Subject subject,
            ContentPlan plan,
            List<BlockerCode> staticBlockers) {
        PlanState target = !plan.processingRequested() ? PlanState.DISABLED
                : staticBlockers.isEmpty() ? PlanState.READY : PlanState.BLOCKED;
        return content.reconcilePlanState(plan, target, subject.userId(), now());
    }

    private List<BlockerCode> startBlockers(
            Meeting meeting,
            TenantPolicy policy,
            ContentPlan plan,
            MeetingContentDependencies.Status status,
            MeetingMediaProvider.Capability media,
            MeetingRecordingProvider.Capability recording) {
        LinkedHashSet<BlockerCode> blockers = new LinkedHashSet<>(
                staticBlockers(policy, plan, status, media, recording));
        if (!plan.recordingRequested()) blockers.add(BlockerCode.PLAN_RECORDING_DISABLED);
        if (!meeting.live()) blockers.add(BlockerCode.MEETING_NOT_LIVE);
        if (!consentComplete(plan)) blockers.add(BlockerCode.CONSENT);
        return List.copyOf(blockers);
    }

    private List<BlockerCode> staticBlockers(
            TenantPolicy policy,
            ContentPlan plan,
            MeetingContentDependencies.Status status,
            MeetingMediaProvider.Capability media,
            MeetingRecordingProvider.Capability recording) {
        if (!plan.processingRequested()) return List.of();
        LinkedHashSet<BlockerCode> blockers = new LinkedHashSet<>();
        if (!policy.meetingsEnabled()) blockers.add(BlockerCode.MEETINGS_DISABLED);
        if ("NEVER".equals(policy.recordingPolicy())) blockers.add(BlockerCode.POLICY_NEVER);
        if (plan.e2eeEnabled()) blockers.add(BlockerCode.E2EE);
        if (!media.available()) blockers.add(BlockerCode.MEDIA_PROVIDER);
        if (!status.auditAvailable()) blockers.add(BlockerCode.AUDIT);
        if (!status.egressAvailable()) blockers.add(BlockerCode.EGRESS);
        if (!status.storageAvailable()) blockers.add(BlockerCode.STORAGE);
        if (!safeProviderSnapshot(recording)) blockers.add(BlockerCode.EGRESS);
        if (!status.kmsAvailable()) blockers.add(BlockerCode.KMS);
        if (plan.transcriptionRequested() && !status.speechToTextAvailable()) {
            blockers.add(BlockerCode.STT);
        }
        if (plan.aiSummaryRequested() && !status.languageModelAvailable()) {
            blockers.add(BlockerCode.LLM);
        }
        return List.copyOf(blockers);
    }

    private boolean sameProviderSnapshot(
            RecordingSession session,
            MeetingRecordingProvider.Capability capability) {
        return safeProviderSnapshot(capability)
                && capability.providerCode().equals(session.recordingProviderCode())
                && capability.processingRegion().equals(session.recordingProcessingRegion());
    }

    private boolean safeProviderSnapshot(MeetingRecordingProvider.Capability capability) {
        return capability != null && capability.available()
                && capability.providerCode() != null
                && capability.providerCode().matches("^[A-Z][A-Z0-9_-]{2,47}$")
                && capability.processingRegion() != null
                && capability.processingRegion().matches(
                        "^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$");
    }

    private List<BlockerCode> stopBlockers(
            MeetingContentDependencies.Status status,
            MeetingRecordingProvider.Capability recording) {
        LinkedHashSet<BlockerCode> blockers = new LinkedHashSet<>();
        if (!recording.available() || !recording.egressAvailable()) {
            blockers.add(BlockerCode.EGRESS);
        }
        if (!status.auditAvailable()) blockers.add(BlockerCode.AUDIT);
        return List.copyOf(blockers);
    }

    private boolean consentComplete(ContentPlan plan) {
        return plan.currentNoticeId() != null && content.consentCounts(
                plan.tenantId(), plan.meetingId(), plan.currentNoticeId()).complete();
    }

    private Optional<StoredCommand> prior(
            MeetingRequestContext.Subject subject,
            UUID meetingId,
            String commandType,
            String key,
            String hash) {
        Optional<StoredCommand> prior = content.command(
                subject.tenantId(), meetingId, subject.userId(), commandType, key);
        if (prior.isPresent() && !requestHashesMatch(prior.get().requestHash(), hash)) {
            throw conflict("The idempotency key was used for a different recording command.");
        }
        return prior;
    }

    private VideoMeetingContentDtos.RecordingCommandResult blocked(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            ContentPlan plan,
            String commandType,
            String key,
            String hash,
            List<BlockerCode> blockers,
            RecordingSession session,
            String correlationId) {
        int status = blockers.stream().anyMatch(BlockerCode::infrastructure) ? 503 : 409;
        content.saveCommand(
                subject.tenantId(), meeting.meetingId(), subject.userId(), commandType,
                key, hash, false, status, blockers,
                session == null ? null : session.recordingSessionId(), plan.version());
        audit.collaboration(
                subject, meeting, "meeting.recording.command-blocked",
                session == null ? "MEETING_CONTENT_PLAN" : "MEETING_RECORDING_SESSION",
                session == null ? plan.planId().toString()
                        : session.recordingSessionId().toString(),
                correlationId, true,
                Map.of("planVersion", plan.version(),
                        "blockerCodes", blockers.stream().map(Enum::name).toList(),
                        "commandType", commandType));
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
        return result(
                stored.httpStatus(), stored.accepted(),
                session == null ? "BLOCKED" : session.state().name(),
                stored.blockers(), session, stored.resultVersion());
    }

    private VideoMeetingContentDtos.RecordingCommandResult result(
            int status,
            boolean accepted,
            String state,
            List<BlockerCode> blockers,
            RecordingSession session,
            long planVersion) {
        return new VideoMeetingContentDtos.RecordingCommandResult(
                status,
                new VideoMeetingContentDtos.RecordingCommandResponse(
                        accepted, state,
                        blockers.stream().map(VideoMeetingContentDtos.BlockerResponse::from)
                                .toList(),
                        VideoMeetingContentDtos.RecordingSessionResponse.from(session),
                        planVersion));
    }

    private ContentNotice currentNotice(
            MeetingRequestContext.Subject subject, ContentPlan plan) {
        if (plan.currentNoticeId() == null) {
            throw conflict("The meeting does not have a current processing notice.");
        }
        return content.currentNotice(subject.tenantId(), plan.meetingId())
                .filter(notice -> notice.noticeId().equals(plan.currentNoticeId()))
                .orElseThrow(() -> conflict(
                        "The current processing notice is unavailable."));
    }

    private Participant requireHost(
            MeetingRequestContext.Subject subject, Meeting meeting) {
        Participant participant = meetings.participant(
                        subject.tenantId(), meeting.meetingId(), subject.userId())
                .filter(candidate -> candidate.attendanceState() != AttendanceState.DENIED)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND, "The meeting was not found."));
        if (!participant.canHost()) {
            throw new BaseException(ErrorCode.FORBIDDEN, "A meeting host role is required.");
        }
        return participant;
    }

    private Duration commandLease() {
        Duration lease = properties.getCommandLease();
        if (lease == null || lease.compareTo(Duration.ofSeconds(30)) < 0
                || lease.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "The recording command lease is not configured safely.");
        }
        return lease;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private BaseException versionConflict() {
        return new BaseException(
                ErrorCode.OBJECT_VERSION_CONFLICT,
                "The governed recording state changed. Refresh and retry.");
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
