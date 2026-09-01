package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.MeetingTranscriptArtifactDtos;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptArtifactRepository.TranscriptArtifact;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ContentNotice;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ContentPlan;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ConsentEvidence;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.LifecycleState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.TenantPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.commandKey;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.correlation;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHash;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHashesMatch;

@Service
public class MeetingTranscriptArtifactFinalizationService {

    private final VideoMeetingRepository meetings;
    private final VideoMeetingContentRepository content;
    private final VideoMeetingIntelligenceRepository intelligence;
    private final MeetingTranscriptArtifactRepository artifacts;
    private final MeetingTranscriptFinalizationAssertionVerifier assertionVerifier;
    private final VideoMeetingAuditRecorder audit;
    private final Clock clock;

    @Autowired
    public MeetingTranscriptArtifactFinalizationService(
            VideoMeetingRepository meetings,
            VideoMeetingContentRepository content,
            VideoMeetingIntelligenceRepository intelligence,
            MeetingTranscriptArtifactRepository artifacts,
            MeetingTranscriptFinalizationAssertionVerifier assertionVerifier,
            VideoMeetingAuditRecorder audit) {
        this(meetings, content, intelligence, artifacts,
                assertionVerifier, audit, Clock.systemUTC());
    }

    MeetingTranscriptArtifactFinalizationService(
            VideoMeetingRepository meetings,
            VideoMeetingContentRepository content,
            VideoMeetingIntelligenceRepository intelligence,
            MeetingTranscriptArtifactRepository artifacts,
            MeetingTranscriptFinalizationAssertionVerifier assertionVerifier,
            VideoMeetingAuditRecorder audit,
            Clock clock) {
        this.meetings = meetings;
        this.content = content;
        this.intelligence = intelligence;
        this.artifacts = artifacts;
        this.assertionVerifier = assertionVerifier;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Registers content-free provenance before a trusted producer may publish a transcript
     * object. Signature verification happens before any mutation; the artifact registration,
     * replay consumption and audit evidence commit atomically.
     */
    @Transactional
    public MeetingTranscriptArtifactDtos.TranscriptArtifactResponse registerTranscript(
            UUID meetingId,
            MeetingTranscriptArtifactDtos.RegisterTranscriptCommand request,
            String idempotencyKey,
            String correlationId,
            String producerToken,
            String producerAssertion) {
        MeetingRequestContext.Subject subject = trustedProducer();
        validateRegistration(request);
        String key = commandKey(idempotencyKey);
        String hash = requestHash(
                meetingId, request.artifactId(), request.expectedContentPlanVersion(),
                request.contentNoticeId(), request.consentSnapshotSha256(),
                request.sourceSha256(), request.processingRegion());
        var verifiedAssertion = assertionVerifier.verifyRegistration(
                producerToken, producerAssertion, subject.tenantId(), meetingId,
                request.artifactId(), hash);
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        TranscriptArtifact current = artifacts.lockTranscript(subject.tenantId(), meetingId)
                .orElse(null);
        if (current != null && current.registrationIdempotencyKey() != null) {
            consumeAssertion(verifiedAssertion, subject, meetingId, request.artifactId());
            return registrationReplay(current, request, key, hash);
        }
        if (current != null && !current.artifactId().equals(request.artifactId())) {
            throw conflict("A transcript artifact is already reserved for the meeting.");
        }
        TenantPolicy policy = meetings.ensurePolicy(subject.tenantId(), subject.userId());
        ContentPlan plan = content.plan(subject.tenantId(), meetingId)
                .orElseThrow(() -> conflict("The meeting content plan is unavailable."));
        ContentNotice notice = validateRegistrationGovernance(meeting, policy, plan, request);
        ConsentEvidence consent = intelligence.consentEvidence(
                subject.tenantId(), meetingId, notice.noticeId());
        if (!consent.complete() || !requestHashesMatch(
                consent.snapshotSha256(), request.consentSnapshotSha256())) {
            throw conflict("Participant consent evidence is incomplete or does not match.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        TranscriptArtifact registered = artifacts.registerProcessing(
                current, request.artifactId(), subject.tenantId(), meetingId,
                request.sourceSha256(), now.plusDays(policy.artifactRetentionDays()),
                request.processingRegion(), notice.noticeId(), consent.snapshotSha256(),
                key, hash, subject.userId(), now);
        consumeAssertion(verifiedAssertion, subject, meetingId, request.artifactId());
        audit.collaboration(
                subject, meeting, "meeting.transcript-artifact.registered",
                "MEETING_TRANSCRIPT_ARTIFACT", registered.artifactId().toString(),
                correlation(correlationId), true,
                Map.of("artifactState", registered.state(),
                        "processingRegion", registered.processingRegion(),
                        "contentNoticeId", registered.contentNoticeId().toString(),
                        "artifactVersion", registered.version()));
        return MeetingTranscriptArtifactDtos.TranscriptArtifactResponse.from(registered);
    }

    @Transactional
    public MeetingTranscriptArtifactDtos.TranscriptArtifactResponse finalizeTranscript(
            UUID meetingId,
            MeetingTranscriptArtifactDtos.FinalizeTranscriptCommand request,
            String idempotencyKey,
            String correlationId,
            String producerToken,
            String producerAssertion) {
        MeetingRequestContext.Subject subject = trustedProducer();
        String key = commandKey(idempotencyKey);
        String hash = requestHash(
                meetingId, request.artifactId(), request.expectedArtifactVersion(),
                request.expectedContentPlanVersion(), request.contentNoticeId(),
                request.consentSnapshotSha256(), request.sourceSha256(),
                request.processingRegion(), request.storageProvider(), request.objectKey(),
                request.contentType(), request.sizeBytes());
        var verifiedAssertion = assertionVerifier.verify(
                producerToken, producerAssertion, subject.tenantId(), meetingId,
                request.artifactId(), hash);
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        TranscriptArtifact current = artifacts.lock(
                        subject.tenantId(), meetingId, request.artifactId())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND, "The transcript artifact was not found."));
        OffsetDateTime now = OffsetDateTime.now(clock);
        consumeAssertion(verifiedAssertion, subject, meetingId, request.artifactId());
        if (current.idempotencyKey() != null) {
            return replay(current, key, hash);
        }
        validateRequest(request);
        if (current.version() != request.expectedArtifactVersion()) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The transcript artifact changed. Refresh and retry.");
        }
        TenantPolicy policy = meetings.ensurePolicy(subject.tenantId(), subject.userId());
        ContentPlan plan = content.plan(subject.tenantId(), meetingId)
                .orElseThrow(() -> conflict("The meeting content plan is unavailable."));
        ContentNotice notice = validateGovernance(meeting, policy, plan, request);
        ConsentEvidence consent = intelligence.consentEvidence(
                subject.tenantId(), meetingId, notice.noticeId());
        if (!consent.complete() || !requestHashesMatch(
                consent.snapshotSha256(), request.consentSnapshotSha256())) {
            throw conflict("Participant consent evidence is incomplete or does not match.");
        }
        if (current.registrationIdempotencyKey() == null
                || current.registeredAt() == null
                || !requestHashesMatch(current.sourceSha256(), request.sourceSha256())
                || !request.processingRegion().equals(current.processingRegion())
                || !request.contentNoticeId().equals(current.contentNoticeId())
                || !requestHashesMatch(
                        current.consentSnapshotSha256(), request.consentSnapshotSha256())) {
            throw conflict("The transcript finalization does not match its registration.");
        }
        OffsetDateTime retentionUntil = current.retentionUntil().isBefore(
                now.plusDays(policy.artifactRetentionDays()))
                ? current.retentionUntil() : now.plusDays(policy.artifactRetentionDays());
        TranscriptArtifact finalized = artifacts.finalizeAvailable(
                current, request.storageProvider(), request.objectKey(), request.contentType(),
                request.sizeBytes(), request.sourceSha256(),
                retentionUntil, request.processingRegion(),
                notice.noticeId(), consent.snapshotSha256(), key, hash,
                subject.userId(), now);
        audit.collaboration(
                subject, meeting, "meeting.transcript-artifact.finalized",
                "MEETING_TRANSCRIPT_ARTIFACT", finalized.artifactId().toString(),
                correlation(correlationId), true,
                Map.of("artifactState", finalized.state(),
                        "processingRegion", finalized.processingRegion(),
                        "contentNoticeId", finalized.contentNoticeId().toString(),
                        "artifactVersion", finalized.version()));
        return MeetingTranscriptArtifactDtos.TranscriptArtifactResponse.from(finalized);
    }

    private ContentNotice validateGovernance(
            Meeting meeting,
            TenantPolicy policy,
            ContentPlan plan,
            MeetingTranscriptArtifactDtos.FinalizeTranscriptCommand request) {
        if (meeting.lifecycleState() != LifecycleState.ENDED
                || !policy.meetingsEnabled() || "NEVER".equals(policy.recordingPolicy())) {
            throw conflict("Meeting policy does not permit transcript finalization.");
        }
        if (plan.version() != request.expectedContentPlanVersion()
                || plan.state() != VideoMeetingContentModels.PlanState.READY
                || !plan.transcriptionRequested() || plan.e2eeEnabled()
                || !request.contentNoticeId().equals(plan.currentNoticeId())) {
            throw conflict("The current content plan does not permit transcript finalization.");
        }
        return content.currentNotice(plan.tenantId(), plan.meetingId())
                .filter(notice -> notice.noticeId().equals(request.contentNoticeId())
                        && notice.transcriptionDisclosed())
                .orElseThrow(() -> conflict(
                        "The current content notice does not permit transcript finalization."));
    }

    private void validateRequest(
            MeetingTranscriptArtifactDtos.FinalizeTranscriptCommand request) {
        if (!request.sourceSha256().matches("^[0-9a-f]{64}$")
                || !request.consentSnapshotSha256().matches("^[0-9a-f]{64}$")
                || !request.processingRegion().matches(
                        "^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$")
                || !request.storageProvider().matches("^[A-Z][A-Z0-9_-]{1,31}$")
                || !"application/json".equals(request.contentType())
                || request.sizeBytes() <= 0 || request.sizeBytes() > 1_000_000_000L
                || request.objectKey().isBlank() || request.objectKey().length() > 1_000
                || request.objectKey().chars().anyMatch(Character::isISOControl)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Transcript artifact finalization input is invalid.");
        }
    }

    private ContentNotice validateRegistrationGovernance(
            Meeting meeting,
            TenantPolicy policy,
            ContentPlan plan,
            MeetingTranscriptArtifactDtos.RegisterTranscriptCommand request) {
        if ((meeting.lifecycleState() != LifecycleState.LIVE
                && meeting.lifecycleState() != LifecycleState.ENDED)
                || !policy.meetingsEnabled() || "NEVER".equals(policy.recordingPolicy())) {
            throw conflict("Meeting policy does not permit transcript registration.");
        }
        if (plan.version() != request.expectedContentPlanVersion()
                || plan.state() != VideoMeetingContentModels.PlanState.READY
                || !plan.transcriptionRequested() || plan.e2eeEnabled()
                || !request.contentNoticeId().equals(plan.currentNoticeId())) {
            throw conflict("The current content plan does not permit transcript registration.");
        }
        return content.currentNotice(plan.tenantId(), plan.meetingId())
                .filter(notice -> notice.noticeId().equals(request.contentNoticeId())
                        && notice.transcriptionDisclosed())
                .orElseThrow(() -> conflict(
                        "The current content notice does not permit transcript registration."));
    }

    private void validateRegistration(
            MeetingTranscriptArtifactDtos.RegisterTranscriptCommand request) {
        if (!request.sourceSha256().matches("^[0-9a-f]{64}$")
                || !request.consentSnapshotSha256().matches("^[0-9a-f]{64}$")
                || !request.processingRegion().matches(
                        "^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Transcript artifact registration input is invalid.");
        }
    }

    private MeetingTranscriptArtifactDtos.TranscriptArtifactResponse registrationReplay(
            TranscriptArtifact current,
            MeetingTranscriptArtifactDtos.RegisterTranscriptCommand request,
            String key,
            String hash) {
        if (!current.artifactId().equals(request.artifactId())
                || !current.registrationIdempotencyKey().equals(key)
                || !requestHashesMatch(current.registrationRequestSha256(), hash)
                || !requestHashesMatch(current.sourceSha256(), request.sourceSha256())
                || !request.processingRegion().equals(current.processingRegion())
                || !request.contentNoticeId().equals(current.contentNoticeId())
                || !requestHashesMatch(
                        current.consentSnapshotSha256(), request.consentSnapshotSha256())) {
            throw conflict("The transcript artifact was already registered by another command.");
        }
        return MeetingTranscriptArtifactDtos.TranscriptArtifactResponse.from(current);
    }

    private MeetingRequestContext.Subject trustedProducer() {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        if (!subject.has("APP.MEETINGS", "UPDATE", "MANAGE")) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Trusted meeting artifact producer permission is required.");
        }
        return subject;
    }

    private void consumeAssertion(
            MeetingTranscriptFinalizationAssertionVerifier.VerifiedAssertion assertion,
            MeetingRequestContext.Subject subject,
            UUID meetingId,
            UUID artifactId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        artifacts.consumeAssertion(
                assertion.jti(), subject.tenantId(), meetingId, artifactId,
                OffsetDateTime.ofInstant(
                        assertion.expiresAt(), java.time.ZoneOffset.UTC), now);
    }

    private MeetingTranscriptArtifactDtos.TranscriptArtifactResponse replay(
            TranscriptArtifact current, String key, String hash) {
        if (!current.idempotencyKey().equals(key)
                || !requestHashesMatch(current.requestSha256(), hash)) {
            throw conflict("The transcript artifact was already finalized by another command.");
        }
        return MeetingTranscriptArtifactDtos.TranscriptArtifactResponse.from(current);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
