package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingIntelligenceDtos;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ContentNotice;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ContentPlan;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ConsentEvidence;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.IntelligenceReport;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.IntelligenceRun;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.RunState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.SourceArtifact;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.StoredRun;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.LifecycleState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.TenantPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHashesMatch;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.correlation;

@Service
public class MeetingIntelligenceRunTransactions {

    private static final String PENDING_PROVIDER = "PENDING";

    private final VideoMeetingRepository meetings;
    private final VideoMeetingContentRepository content;
    private final VideoMeetingIntelligenceRepository intelligence;
    private final MeetingContentAccessPolicy access;
    private final MeetingContentDependencies dependencies;
    private final MeetingIntelligenceRetentionService retention;
    private final com.dwp.services.meeting.videomeeting.provider.MeetingTranscriptSource transcripts;
    private final com.dwp.services.meeting.videomeeting.provider.MeetingIntelligencePayloadProtector
            protector;
    private final VideoMeetingAuditRecorder audit;

    public MeetingIntelligenceRunTransactions(
            VideoMeetingRepository meetings,
            VideoMeetingContentRepository content,
            VideoMeetingIntelligenceRepository intelligence,
            MeetingContentAccessPolicy access,
            MeetingContentDependencies dependencies,
            MeetingIntelligenceRetentionService retention,
            com.dwp.services.meeting.videomeeting.provider.MeetingTranscriptSource transcripts,
            com.dwp.services.meeting.videomeeting.provider.MeetingIntelligencePayloadProtector
                    protector,
            VideoMeetingAuditRecorder audit) {
        this.meetings = meetings;
        this.content = content;
        this.intelligence = intelligence;
        this.access = access;
        this.dependencies = dependencies;
        this.retention = retention;
        this.transcripts = transcripts;
        this.protector = protector;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PreparedExecution prepare(
            MeetingRequestContext.Subject subject,
            UUID meetingId,
            VideoMeetingIntelligenceDtos.CreateRunCommand request,
            String idempotencyKey,
            String requestSha256,
            UUID proposedRunId,
            OffsetDateTime now) {
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        Participant host = member(subject, meeting);
        if (!access.canRequest(host)) {
            throw new BaseException(ErrorCode.FORBIDDEN, "A meeting host role is required.");
        }
        Optional<StoredRun> prior = intelligence.byIdempotency(
                subject.tenantId(), meetingId, subject.userId(), idempotencyKey);
        IntelligenceRun expired = null;
        if (prior.isPresent()) {
            PreparedExecution replay = replay(prior.get(), requestSha256);
            if (replay.run().state() != RunState.RUNNING
                    || replay.run().leaseExpiresAt().isAfter(now)) {
                return replay;
            }
            expired = replay.run();
        }

        TenantPolicy policy = meetings.ensurePolicy(subject.tenantId(), subject.userId());
        ContentPlan plan = content.plan(subject.tenantId(), meetingId)
                .orElseThrow(() -> conflict("The meeting content plan is unavailable."));
        SourceArtifact source = intelligence.sourceTranscript(
                        subject.tenantId(), meetingId, request.sourceArtifactId())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND, "The transcript artifact was not found."));
        ContentNotice notice = validateGovernance(
                meeting, policy, plan, source, request.expectedContentPlanVersion(), now);
        ConsentEvidence consent = intelligence.consentEvidence(
                subject.tenantId(), meetingId, notice.noticeId());
        validateConsent(source, consent);

        OffsetDateTime retentionUntil = source.retentionUntil().isBefore(
                now.plusDays(policy.artifactRetentionDays()))
                ? source.retentionUntil() : now.plusDays(policy.artifactRetentionDays());
        if (expired != null) {
            Optional<IntelligenceRun> reclaimed = intelligence.reclaimExpired(
                    expired, UUID.randomUUID(), now, now.plusMinutes(2));
            if (reclaimed.isPresent()) {
                return new PreparedExecution(
                        meeting, reclaimed.get(), source, retentionUntil, true);
            }
            StoredRun winner = intelligence.byIdempotency(
                            subject.tenantId(), meetingId, subject.userId(), idempotencyKey)
                    .orElseThrow(() -> conflict(
                            "The reclaimed intelligence command could not be resolved."));
            return replay(winner, requestSha256);
        }

        Optional<IntelligenceRun> active = intelligence.activeForSource(
                subject.tenantId(), meetingId, source.artifactId(), source.sha256(),
                VideoMeetingIntelligenceModels.PROFILE, notice.noticeId());
        if (active.isPresent()) {
            return resumeActive(meeting, active.get(), source, retentionUntil, now);
        }

        IntelligenceRun candidate = new IntelligenceRun(
                proposedRunId, subject.tenantId(), meetingId, source.artifactId(),
                source.sha256(), notice.noticeId(), consent.snapshotSha256(),
                VideoMeetingIntelligenceModels.PROFILE, request.outputLanguage(),
                source.processingRegion(), UUID.randomUUID(), now.plusMinutes(2), 1,
                RunState.RUNNING, PENDING_PROVIDER, PENDING_PROVIDER,
                VideoMeetingIntelligenceModels.PROMPT_VERSION,
                VideoMeetingIntelligenceModels.SCHEMA_VERSION,
                idempotencyKey, requestSha256, now, subject.userId(), now, null, null, 0);
        Optional<IntelligenceRun> inserted = intelligence.tryCreateRunning(candidate);
        if (inserted.isPresent()) {
            return new PreparedExecution(
                    meeting, inserted.get(), source, retentionUntil, true);
        }
        Optional<StoredRun> concurrent = intelligence.byIdempotency(
                subject.tenantId(), meetingId, subject.userId(), idempotencyKey);
        if (concurrent.isPresent()) {
            PreparedExecution replay = replay(concurrent.get(), requestSha256);
            return resumeActive(meeting, replay.run(), source, retentionUntil, now);
        }
        IntelligenceRun sourceWinner = intelligence.activeForSource(
                        subject.tenantId(), meetingId, source.artifactId(), source.sha256(),
                        VideoMeetingIntelligenceModels.PROFILE, notice.noticeId())
                .orElseThrow(() -> conflict(
                        "The intelligence command could not be resolved."));
        return resumeActive(meeting, sourceWinner, source, retentionUntil, now);
    }

    public void ensureExecutionReadiness() {
        MeetingContentDependencies.Status status = dependencies.status();
        if (!status.storageAvailable() || !status.kmsAvailable()
                || !status.languageModelAvailable() || !status.auditAvailable()
                || !transcripts.available() || !protector.available() || !retention.ready()) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Meeting intelligence dependencies are not ready.");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FinalizedExecution succeed(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            String correlationId,
            IntelligenceRun prepared,
            String providerCode,
            String providerModel,
            UUID reportId,
            String encryptedPayload,
            String payloadSha256,
            OffsetDateTime retentionUntil,
            long actorUserId,
            OffsetDateTime completedAt) {
        IntelligenceRun current = currentPrepared(prepared);
        if (current.state() != RunState.RUNNING) {
            return new FinalizedExecution(
                    current,
                    intelligence.reportForRun(
                            current.tenantId(), current.meetingId(), current.runId()).orElse(null));
        }
        IntelligenceReport report = intelligence.createDraft(
                reportId, current, encryptedPayload, payloadSha256,
                retentionUntil, actorUserId, completedAt);
        IntelligenceRun succeeded = intelligence.succeed(
                current, providerCode, providerModel, completedAt);
        auditTerminal(subject, meeting, succeeded, report, correlationId);
        return new FinalizedExecution(succeeded, report);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FinalizedExecution fail(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            String correlationId,
            IntelligenceRun prepared,
            String failureCode,
            OffsetDateTime completedAt) {
        IntelligenceRun current = currentPrepared(prepared);
        if (current.state() != RunState.RUNNING) {
            return new FinalizedExecution(
                    current,
                    intelligence.reportForRun(
                            current.tenantId(), current.meetingId(), current.runId()).orElse(null));
        }
        IntelligenceRun failed = intelligence.fail(current, failureCode, completedAt);
        auditTerminal(subject, meeting, failed, null, correlationId);
        return new FinalizedExecution(failed, null);
    }

    private IntelligenceRun currentPrepared(IntelligenceRun prepared) {
        IntelligenceRun current = intelligence.run(
                        prepared.tenantId(), prepared.meetingId(), prepared.runId())
                .orElseThrow(() -> conflict("The prepared intelligence run is unavailable."));
        if (!current.executionFence().equals(prepared.executionFence())
                || (current.state() == RunState.RUNNING
                    && current.version() != prepared.version())) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The intelligence execution fence changed.");
        }
        return current;
    }

    private PreparedExecution replay(StoredRun stored, String requestSha256) {
        if (!requestHashesMatch(stored.requestSha256(), requestSha256)) {
            throw conflict("The idempotency key was used for a different intelligence request.");
        }
        return new PreparedExecution(null, stored.run(), null, null, false);
    }

    private ContentNotice validateGovernance(
            Meeting meeting,
            TenantPolicy policy,
            ContentPlan plan,
            SourceArtifact source,
            long expectedPlanVersion,
            OffsetDateTime now) {
        if (meeting.lifecycleState() != LifecycleState.ENDED) {
            throw conflict("Meeting intelligence is available only after the meeting ends.");
        }
        if (!policy.meetingsEnabled() || "NEVER".equals(policy.recordingPolicy())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The tenant content-processing policy does not allow analysis.");
        }
        if (plan.version() != expectedPlanVersion) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The meeting content plan changed. Refresh and retry.");
        }
        if (!plan.transcriptionRequested() || !plan.aiSummaryRequested()
                || plan.e2eeEnabled() || plan.currentNoticeId() == null) {
            throw conflict("The meeting content plan does not permit intelligence processing.");
        }
        if (!"AVAILABLE".equals(source.artifactState())
                || !source.serverSideProcessingAllowed()
                || source.sha256() == null
                || !source.sha256().matches("^[0-9a-f]{64}$")
                || source.retentionUntil() == null
                || !source.retentionUntil().isAfter(now)
                || source.processingRegion() == null
                || !source.processingRegion().matches("^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$")) {
            throw conflict("The transcript lacks trusted processing or retention evidence.");
        }
        if (!plan.currentNoticeId().equals(source.contentNoticeId())) {
            throw conflict("The transcript was captured under a different processing notice.");
        }
        ContentNotice notice = content.currentNotice(plan.tenantId(), plan.meetingId())
                .filter(candidate -> candidate.noticeId().equals(source.contentNoticeId()))
                .orElseThrow(() -> conflict("The transcript processing notice is not current."));
        if (!notice.transcriptionDisclosed() || !notice.aiSummaryDisclosed()) {
            throw conflict("The current notice does not disclose intelligence processing.");
        }
        return notice;
    }

    private void validateConsent(SourceArtifact source, ConsentEvidence evidence) {
        if (!evidence.complete() || source.consentSnapshotSha256() == null
                || !requestHashesMatch(
                        source.consentSnapshotSha256(), evidence.snapshotSha256())) {
            throw conflict("Participant consent is incomplete or changed after transcript capture.");
        }
    }

    private PreparedExecution resumeActive(
            Meeting meeting,
            IntelligenceRun active,
            SourceArtifact source,
            OffsetDateTime retentionUntil,
            OffsetDateTime now) {
        if (active.state() != RunState.RUNNING || active.leaseExpiresAt().isAfter(now)) {
            return new PreparedExecution(meeting, active, null, null, false);
        }
        Optional<IntelligenceRun> reclaimed = intelligence.reclaimExpired(
                active, UUID.randomUUID(), now, now.plusMinutes(2));
        return reclaimed
                .map(run -> new PreparedExecution(
                        meeting, run, source, retentionUntil, true))
                .orElseGet(() -> new PreparedExecution(meeting,
                        intelligence.run(active.tenantId(), active.meetingId(), active.runId())
                                .orElseThrow(() -> conflict(
                                        "The intelligence recovery winner is unavailable.")),
                        null, null, false));
    }

    private Participant member(MeetingRequestContext.Subject subject, Meeting meeting) {
        return meetings.participant(subject.tenantId(), meeting.meetingId(), subject.userId())
                .filter(candidate -> candidate.attendanceState()
                        != VideoMeetingModels.AttendanceState.DENIED)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND, "The meeting was not found."));
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private void auditTerminal(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            IntelligenceRun run,
            IntelligenceReport report,
            String correlationId) {
        audit.collaboration(
                subject, meeting,
                run.state() == RunState.SUCCEEDED
                        ? "meeting.intelligence.completed" : "meeting.intelligence.failed",
                "MEETING_INTELLIGENCE_RUN", run.runId().toString(),
                correlation(correlationId), true,
                Map.of("runState", run.state().name(),
                        "failureCode", Optional.ofNullable(run.failureCode()).orElse("NONE"),
                        "reportId", report == null ? "NONE" : report.reportId().toString(),
                        "sourceArtifactId", run.sourceArtifactId().toString(),
                        "schemaVersion", run.schemaVersion()));
    }

    public record PreparedExecution(
            Meeting meeting,
            IntelligenceRun run,
            SourceArtifact source,
            OffsetDateTime retentionUntil,
            boolean execute) {
    }

    public record FinalizedExecution(
            IntelligenceRun run,
            IntelligenceReport report) {
    }
}
