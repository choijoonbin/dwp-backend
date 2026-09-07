package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.MeetingIntelligenceAutoRequestModels.AutoRequest;
import com.dwp.services.meeting.videomeeting.domain.MeetingIntelligenceAutoRequestModels.RequestState;
import com.dwp.services.meeting.videomeeting.domain.MeetingIntelligenceAutoRequestModels.RunBinding;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHash;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHashesMatch;

@Service
class MeetingIntelligenceAutoRequestTransactions {

    private final MeetingIntelligenceAutoRequestRepository requests;
    private final VideoMeetingRepository meetings;
    private final MeetingIntelligenceAutoRequestProperties properties;
    private final VideoMeetingAuditRecorder audit;
    private final Clock clock;

    @Autowired
    MeetingIntelligenceAutoRequestTransactions(
            MeetingIntelligenceAutoRequestRepository requests,
            VideoMeetingRepository meetings,
            MeetingIntelligenceAutoRequestProperties properties,
            VideoMeetingAuditRecorder audit) {
        this(requests, meetings, properties, audit, Clock.systemUTC());
    }

    MeetingIntelligenceAutoRequestTransactions(
            MeetingIntelligenceAutoRequestRepository requests,
            VideoMeetingRepository meetings,
            MeetingIntelligenceAutoRequestProperties properties,
            VideoMeetingAuditRecorder audit,
            Clock clock) {
        this.requests = requests;
        this.meetings = meetings;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AutoRequest claim() {
        requireConfiguration();
        OffsetDateTime now = now();
        return requests.claim(now, now.plus(properties.getLeaseDuration()), UUID.randomUUID())
                .orElse(null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AutoRequest retry(AutoRequest claimed, String failureCode) {
        validateFailureCode(failureCode);
        OffsetDateTime now = now();
        AutoRequest current = current(claimed, now);
        return requests.retry(
                current, failureCode, now, now.plus(properties.getRetryDelay()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AutoRequest succeed(
            MeetingRequestContext.Subject subject,
            AutoRequest claimed,
            UUID runId) {
        OffsetDateTime completedAt = now();
        AutoRequest current = current(claimed, completedAt);
        Meeting meeting = meetingAndHost(current);
        RunBinding run = run(current, runId, "SUCCEEDED");
        if (run.reportId() == null) {
            throw conflict("The automatic intelligence report is unavailable.");
        }
        AutoRequest completed = requests.succeed(current, runId, completedAt);
        auditTerminal(subject, meeting, completed, "meeting.intelligence.auto-request.completed");
        return completed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AutoRequest fail(
            MeetingRequestContext.Subject subject,
            AutoRequest claimed,
            UUID runId,
            String failureCode) {
        validateFailureCode(failureCode);
        OffsetDateTime completedAt = now();
        AutoRequest current = current(claimed, completedAt);
        Meeting meeting = meetingAndHost(current);
        run(current, runId, "FAILED");
        AutoRequest failed = requests.fail(current, runId, failureCode, completedAt);
        auditTerminal(subject, meeting, failed, "meeting.intelligence.auto-request.failed");
        return failed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AutoRequest failWithoutRun(
            MeetingRequestContext.Subject subject,
            AutoRequest claimed,
            String failureCode) {
        validateFailureCode(failureCode);
        OffsetDateTime completedAt = now();
        AutoRequest current = current(claimed, completedAt);
        Meeting meeting = meetingAndHost(current);
        AutoRequest failed = requests.fail(current, null, failureCode, completedAt);
        auditTerminal(subject, meeting, failed, "meeting.intelligence.auto-request.failed");
        return failed;
    }

    private AutoRequest current(AutoRequest claimed, OffsetDateTime now) {
        if (claimed == null) throw conflict("The automatic intelligence claim is missing.");
        AutoRequest current = requests.lock(claimed.requestId())
                .orElseThrow(() -> conflict(
                        "The automatic intelligence request is unavailable."));
        if (current.state() != RequestState.RUNNING
                || current.executionFence() == null
                || !current.executionFence().equals(claimed.executionFence())
                || current.leaseExpiresAt() == null
                || !current.leaseExpiresAt().isAfter(now)
                || current.version() != claimed.version()
                || !sameIdentity(current, claimed)) {
            throw conflict("The automatic intelligence execution fence changed or expired.");
        }
        return current;
    }

    private Meeting meetingAndHost(AutoRequest request) {
        Meeting meeting = meetings.lockMeeting(request.tenantId(), request.meetingId());
        if (meeting.organizerUserId() != request.requestedBy()) {
            throw conflict("The automatic intelligence host binding changed.");
        }
        return meeting;
    }

    private RunBinding run(AutoRequest request, UUID runId, String requiredState) {
        if (runId == null) throw conflict("The automatic intelligence run is missing.");
        RunBinding run = requests.runBinding(
                        request.tenantId(), request.meetingId(), runId)
                .orElseThrow(() -> conflict(
                        "The automatic intelligence run is unavailable."));
        String expectedHash = requestHash(
                request.meetingId(), request.sourceArtifactId(), request.outputLanguage(),
                request.expectedContentPlanVersion(), VideoMeetingIntelligenceModels.PROFILE,
                VideoMeetingIntelligenceModels.SCHEMA_VERSION);
        if (!run.sourceArtifactId().equals(request.sourceArtifactId())
                || !run.sourceSha256().equals(request.sourceSha256())
                || !run.contentNoticeId().equals(request.contentNoticeId())
                || !run.outputLanguage().equals(request.outputLanguage())
                || !run.idempotencyKey().equals(request.intelligenceIdempotencyKey())
                || !requestHashesMatch(run.requestSha256(), expectedHash)
                || run.requestedBy() != request.requestedBy()
                || !requiredState.equals(run.state())) {
            throw conflict("The automatic intelligence run binding changed.");
        }
        return run;
    }

    private boolean sameIdentity(AutoRequest left, AutoRequest right) {
        return left.tenantId() == right.tenantId()
                && left.meetingId().equals(right.meetingId())
                && left.sourceArtifactId().equals(right.sourceArtifactId())
                && left.sourceSha256().equals(right.sourceSha256())
                && left.contentNoticeId().equals(right.contentNoticeId())
                && left.expectedContentPlanVersion() == right.expectedContentPlanVersion()
                && left.requestedBy() == right.requestedBy()
                && left.outputLanguage().equals(right.outputLanguage())
                && left.processingRegion().equals(right.processingRegion())
                && left.intelligenceIdempotencyKey().equals(
                        right.intelligenceIdempotencyKey());
    }

    private void auditTerminal(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            AutoRequest request,
            String action) {
        Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        evidence.put("requestState", request.state().name());
        evidence.put("attempt", request.attemptCount());
        evidence.put("executionGeneration", request.executionGeneration());
        if (request.runId() != null) evidence.put("runId", request.runId().toString());
        if (request.lastFailureCode() != null) {
            evidence.put("failureCode", request.lastFailureCode());
        }
        audit.collaboration(
                subject, meeting, action, "MEETING_INTELLIGENCE_AUTO_REQUEST",
                request.requestId().toString(), "meeting-auto-intelligence:" + request.requestId(),
                false, Map.copyOf(evidence));
    }

    private void requireConfiguration() {
        if (!properties.isEnabled() || !properties.valid()) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Automatic meeting intelligence is not configured safely.");
        }
    }

    private void validateFailureCode(String code) {
        if (code == null || !code.matches("^[A-Z][A-Z0-9_]{2,47}$")) {
            throw new IllegalArgumentException("Automatic intelligence failure code is invalid.");
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
    }
}
