package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.MeetingRecordingArtifactDtos;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingArtifactRepository.RecordingArtifact;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingArtifactRepository.RecordingProvenance;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ContentNotice;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.RecordingState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.LifecycleState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.commandKey;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHash;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHashesMatch;

@Service
public class MeetingRecordingArtifactFinalizationService {

    private static final long MAXIMUM_ARTIFACT_BYTES = 1_000_000_000_000L;
    private static final Set<String> PLAYABLE_CONTENT_TYPES = Set.of(
            "video/mp4", "video/webm", "audio/mp4", "audio/mpeg",
            "audio/webm", "audio/ogg", "audio/wav");

    private final VideoMeetingRepository meetings;
    private final VideoMeetingContentRepository content;
    private final MeetingRecordingArtifactRepository artifacts;
    private final MeetingRecordingArtifactAssertionVerifier assertionVerifier;
    private final VideoMeetingAuditRecorder audit;
    private final MeetingRecordingDeletionReadiness deletionReadiness;
    private final Clock clock;

    @Autowired
    public MeetingRecordingArtifactFinalizationService(
            VideoMeetingRepository meetings,
            VideoMeetingContentRepository content,
            MeetingRecordingArtifactRepository artifacts,
            MeetingRecordingArtifactAssertionVerifier assertionVerifier,
            VideoMeetingAuditRecorder audit,
            MeetingRecordingDeletionReadiness deletionReadiness) {
        this(meetings, content, artifacts, assertionVerifier, audit,
                deletionReadiness, Clock.systemUTC());
    }

    MeetingRecordingArtifactFinalizationService(
            VideoMeetingRepository meetings,
            VideoMeetingContentRepository content,
            MeetingRecordingArtifactRepository artifacts,
            MeetingRecordingArtifactAssertionVerifier assertionVerifier,
            VideoMeetingAuditRecorder audit,
            MeetingRecordingDeletionReadiness deletionReadiness,
            Clock clock) {
        this.meetings = meetings;
        this.content = content;
        this.artifacts = artifacts;
        this.assertionVerifier = assertionVerifier;
        this.audit = audit;
        this.deletionReadiness = deletionReadiness;
        this.clock = clock;
    }

    /**
     * Atomically registers and finalizes only content-free recording provenance after a
     * separately committed, provider-evidenced STOP. Raw media and URLs never cross this API.
     */
    @Transactional
    public MeetingRecordingArtifactDtos.RecordingArtifactResponse finalizeRecording(
            UUID meetingId,
            MeetingRecordingArtifactDtos.FinalizeRecordingCommand request,
            String idempotencyKey,
            String correlationId,
            String producerToken,
            String producerAssertion) {
        MeetingRequestContext.Subject subject = trustedProducer();
        validateRequest(request);
        deletionReadiness.requireReady();
        String safeCorrelationId = safeCorrelation(correlationId);
        String key = commandKey(idempotencyKey);
        String hash = requestHash(
                meetingId, request.artifactId(), request.recordingSessionId(),
                request.expectedArtifactVersion(), request.expectedContentPlanVersion(),
                request.contentNoticeId(), request.consentSnapshotSha256(),
                request.sourceSha256(), request.processingRegion(),
                request.storageProvider(), request.objectKey(), request.contentType(),
                request.sizeBytes(), request.retentionUntil());
        var assertion = assertionVerifier.verify(
                producerToken, producerAssertion, subject.tenantId(), meetingId,
                request.recordingSessionId(), request.artifactId(), hash);
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        RecordingArtifact current = artifacts.lock(
                subject.tenantId(), meetingId, request.artifactId()).orElse(null);
        RecordingArtifact sessionArtifact = artifacts.lockBySession(
                subject.tenantId(), meetingId, request.recordingSessionId()).orElse(null);
        if (sessionArtifact != null
                && !sessionArtifact.artifactId().equals(request.artifactId())) {
            throw conflict("The recording session already has another artifact.");
        }
        if (current != null && current.recordingSessionId() != null
                && !current.recordingSessionId().equals(request.recordingSessionId())) {
            throw conflict("The recording artifact belongs to another session.");
        }
        if (current != null && current.recordingDeletionCommandId() != null) {
            throw conflict("The recording artifact is under governed deletion.");
        }
        if (current != null && current.finalizationIdempotencyKey() != null) {
            RecordingArtifact replay = replay(current, request, key, hash);
            consumeAssertion(assertion, subject, meetingId, request);
            return MeetingRecordingArtifactDtos.RecordingArtifactResponse.from(replay);
        }
        if (current != null
                && (current.storageProvider() != null || current.objectKey() != null)) {
            throw conflict("A retained recording locator cannot be overwritten.");
        }
        requireVersion(current, request.expectedArtifactVersion());
        RecordingProvenance provenance = artifacts.stoppedProvenanceForUpdate(
                        subject.tenantId(), meetingId, request.recordingSessionId())
                .orElseThrow(() -> conflict(
                        "A completed governed recording STOP is required."));
        ContentNotice notice = validateGovernance(meeting, provenance, request);
        if (!requestHashesMatch(
                provenance.stopConsentSnapshotSha256(),
                request.consentSnapshotSha256())) {
            throw conflict("Participant consent evidence is incomplete or does not match.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime retentionCap = provenance.stoppedAt()
                .plusDays(provenance.artifactRetentionDays());
        OffsetDateTime retentionUntil = request.retentionUntil().isBefore(retentionCap)
                ? request.retentionUntil() : retentionCap;
        if (!retentionUntil.isAfter(now)) {
            throw conflict("The recording artifact retention window has expired.");
        }
        RecordingArtifact finalized = artifacts.finalizeAvailable(
                current, request.artifactId(), subject.tenantId(), meetingId, provenance,
                request.storageProvider(), request.objectKey(), request.contentType(),
                request.sizeBytes(), request.sourceSha256(), retentionUntil,
                provenance.processingRegion(), notice.noticeId(),
                provenance.stopConsentSnapshotSha256(),
                key, hash, subject.userId(), now);
        consumeAssertion(assertion, subject, meetingId, request);
        audit.collaboration(
                subject, meeting, "meeting.recording-artifact.finalized",
                "MEETING_RECORDING_ARTIFACT", finalized.artifactId().toString(),
                safeCorrelationId, true,
                Map.of("artifactState", finalized.state(),
                        "recordingSessionId", finalized.recordingSessionId().toString(),
                        "planVersion", finalized.recordingPlanVersion(),
                        "provider", finalized.recordingProviderCode(),
                        "contentType", finalized.contentType(),
                        "sizeBytes", finalized.sizeBytes(),
                        "contentNoticeId", finalized.contentNoticeId().toString(),
                        "retentionUntil", finalized.retentionUntil().toString(),
                        "artifactVersion", finalized.version()));
        return MeetingRecordingArtifactDtos.RecordingArtifactResponse.from(finalized);
    }

    private ContentNotice validateGovernance(
            Meeting meeting,
            RecordingProvenance provenance,
            MeetingRecordingArtifactDtos.FinalizeRecordingCommand request) {
        if ((meeting.lifecycleState() != LifecycleState.LIVE
                && meeting.lifecycleState() != LifecycleState.ENDED)) {
            throw conflict("Meeting retention policy does not permit finalization.");
        }
        if (provenance.state() != RecordingState.STOPPED
                || provenance.stoppedAt() == null
                || provenance.artifactRetentionDays() == null
                || provenance.artifactRetentionDays() <= 0
                || provenance.planVersion() != request.expectedContentPlanVersion()
                || !provenance.noticeId().equals(request.contentNoticeId())
                || !hash(provenance.stopConsentSnapshotSha256())
                || provenance.artifactRetentionDays() > 3650) {
            throw conflict("The recording finalization provenance does not match the plan.");
        }
        if (!safeProviderCode(provenance.providerCode())
                || !region(provenance.processingRegion())
                || !provenance.processingRegion().equals(request.processingRegion())) {
            throw unavailable("Recording provider governance is not configured.");
        }
        return content.notice(meeting.tenantId(), meeting.meetingId(),
                        request.contentNoticeId())
                .filter(ContentNotice::recordingDisclosed)
                .orElseThrow(() -> conflict(
                        "The session content notice does not permit recording finalization."));
    }

    private void validateRequest(
            MeetingRecordingArtifactDtos.FinalizeRecordingCommand request) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (request == null || request.artifactId() == null
                || request.recordingSessionId() == null
                || request.expectedArtifactVersion() == null
                || request.expectedArtifactVersion() < 0
                || request.expectedContentPlanVersion() == null
                || request.expectedContentPlanVersion() < 0
                || request.contentNoticeId() == null
                || !hash(request.consentSnapshotSha256())
                || !hash(request.sourceSha256())
                || !region(request.processingRegion())
                || !safeStorageProvider(request.storageProvider())
                || !safeObjectKey(request.objectKey())
                || !PLAYABLE_CONTENT_TYPES.contains(request.contentType())
                || request.sizeBytes() <= 0
                || request.sizeBytes() > MAXIMUM_ARTIFACT_BYTES
                || request.retentionUntil() == null
                || !request.retentionUntil().isAfter(now)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Recording artifact finalization input is invalid.");
        }
    }

    private RecordingArtifact replay(
            RecordingArtifact current,
            MeetingRecordingArtifactDtos.FinalizeRecordingCommand request,
            String key,
            String hash) {
        if (!"AVAILABLE".equals(current.state())
                || current.recordingDeletionCommandId() != null
                || current.retentionUntil() == null
                || !current.retentionUntil().isAfter(OffsetDateTime.now(clock))
                || !request.recordingSessionId().equals(current.recordingSessionId())
                || !current.finalizationIdempotencyKey().equals(key)
                || !requestHashesMatch(current.finalizationRequestSha256(), hash)) {
            throw conflict("The recording artifact was finalized by another command.");
        }
        return current;
    }

    private void requireVersion(RecordingArtifact current, long expectedVersion) {
        long actualVersion = current == null ? 0 : current.version();
        if (actualVersion != expectedVersion) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The recording artifact changed. Refresh and retry.");
        }
    }

    private void consumeAssertion(
            MeetingRecordingArtifactAssertionVerifier.VerifiedAssertion assertion,
            MeetingRequestContext.Subject subject,
            UUID meetingId,
            MeetingRecordingArtifactDtos.FinalizeRecordingCommand request) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        artifacts.consumeAssertion(
                assertion.jti(), subject.tenantId(), meetingId,
                request.recordingSessionId(), request.artifactId(),
                OffsetDateTime.ofInstant(assertion.expiresAt(), ZoneOffset.UTC), now);
    }

    private MeetingRequestContext.Subject trustedProducer() {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        if (!subject.has("APP.MEETINGS", "UPDATE", "MANAGE")) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Trusted meeting artifact producer permission is required.");
        }
        return subject;
    }

    private boolean hash(String value) {
        return value != null && value.matches("^[0-9a-f]{64}$");
    }

    private boolean region(String value) {
        return value != null
                && value.matches("^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$");
    }

    private boolean safeStorageProvider(String value) {
        return value != null && value.matches("^[A-Z][A-Z0-9_-]{1,31}$");
    }

    private boolean safeProviderCode(String value) {
        return value != null && value.matches("^[A-Z][A-Z0-9_-]{2,47}$");
    }

    private boolean safeObjectKey(String value) {
        return value != null && !value.isBlank() && value.length() <= 1_000
                && value.chars().noneMatch(Character::isISOControl)
                && !value.contains("://") && !value.contains("?") && !value.contains("#");
    }

    private String safeCorrelation(String value) {
        String candidate = value == null ? "" : value.trim();
        return candidate.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$")
                ? candidate : "meeting-recording:" + UUID.randomUUID();
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, message);
    }
}
