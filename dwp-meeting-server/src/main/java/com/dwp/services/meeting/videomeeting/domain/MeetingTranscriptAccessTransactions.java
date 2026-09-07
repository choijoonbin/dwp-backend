package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptAccessModels.PreparedQuery;
import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptArtifactRepository.TranscriptArtifact;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ConsentEvidence;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.LifecycleState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.TenantPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@Component
class MeetingTranscriptAccessTransactions {

    private final VideoMeetingRepository meetings;
    private final MeetingTranscriptArtifactRepository artifacts;
    private final VideoMeetingIntelligenceRepository intelligence;
    private final MeetingTranscriptAccessRepository rateLimits;
    private final VideoMeetingAuditRecorder audit;
    private final Clock clock;

    @Autowired
    MeetingTranscriptAccessTransactions(
            VideoMeetingRepository meetings,
            MeetingTranscriptArtifactRepository artifacts,
            VideoMeetingIntelligenceRepository intelligence,
            MeetingTranscriptAccessRepository rateLimits,
            VideoMeetingAuditRecorder audit) {
        this(meetings, artifacts, intelligence, rateLimits, audit, Clock.systemUTC());
    }

    MeetingTranscriptAccessTransactions(
            VideoMeetingRepository meetings,
            MeetingTranscriptArtifactRepository artifacts,
            VideoMeetingIntelligenceRepository intelligence,
            MeetingTranscriptAccessRepository rateLimits,
            VideoMeetingAuditRecorder audit,
            Clock clock) {
        this.meetings = meetings;
        this.artifacts = artifacts;
        this.intelligence = intelligence;
        this.rateLimits = rateLimits;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PreparedQuery prepare(
            MeetingRequestContext.Subject subject,
            UUID meetingId,
            UUID artifactId,
            long expectedVersion,
            String correlationId) {
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        Participant participant = requireAccess(subject, meeting);
        TranscriptArtifact artifact = requireArtifact(
                subject, meetingId, artifactId, expectedVersion);
        OffsetDateTime window = now().withSecond(0).withNano(0);
        if (!rateLimits.consume(
                subject.tenantId(), meetingId, subject.userId(), window)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The transcript query limit was reached. Retry shortly.");
        }
        audit.transcriptAccess(
                subject, meeting, artifactId, "meeting.transcript.access-requested",
                correlationId,
                Map.of("artifactVersion", artifact.version(),
                        "participantRole", participant.participantRole().name()));
        return new PreparedQuery(subject, meeting, participant, artifact, correlationId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(
            PreparedQuery prepared,
            int returnedSegments,
            boolean queryApplied,
            boolean hasMore) {
        Meeting meeting = meetings.lockMeeting(
                prepared.subject().tenantId(), prepared.meeting().meetingId());
        Participant participant = requireAccess(prepared.subject(), meeting);
        TranscriptArtifact artifact = requireArtifact(
                prepared.subject(), meeting.meetingId(),
                prepared.artifact().artifactId(), prepared.artifact().version());
        if (!sameArtifact(prepared.artifact(), artifact)) throw versionConflict();
        audit.transcriptAccess(
                prepared.subject(), meeting, artifact.artifactId(),
                "meeting.transcript.access-completed", prepared.correlationId(),
                Map.of("artifactVersion", artifact.version(),
                        "participantRole", participant.participantRole().name(),
                        "returnedSegments", returnedSegments,
                        "queryApplied", queryApplied,
                        "hasMore", hasMore));
    }

    private Participant requireAccess(
            MeetingRequestContext.Subject subject,
            Meeting meeting) {
        if (meeting.lifecycleState() != LifecycleState.ENDED) throw notFound();
        TenantPolicy policy = meetings.ensurePolicy(subject.tenantId(), subject.userId());
        if (!policy.meetingsEnabled() || "NEVER".equals(policy.recordingPolicy())) {
            throw notFound();
        }
        return meetings.participant(
                        subject.tenantId(), meeting.meetingId(), subject.userId())
                .filter(Participant::admitted)
                .orElseThrow(this::notFound);
    }

    private TranscriptArtifact requireArtifact(
            MeetingRequestContext.Subject subject,
            UUID meetingId,
            UUID artifactId,
            long expectedVersion) {
        TranscriptArtifact artifact = artifacts.lock(
                        subject.tenantId(), meetingId, artifactId)
                .orElseThrow(this::notFound);
        if (artifact.version() != expectedVersion) throw versionConflict();
        OffsetDateTime now = now();
        if (!"AVAILABLE".equals(artifact.state())
                || artifact.retentionUntil() == null
                || !artifact.retentionUntil().isAfter(now)
                || !artifact.serverSideProcessingAllowed()
                || artifact.sourceSha256() == null
                || !artifact.sourceSha256().matches("^[0-9a-f]{64}$")
                || artifact.contentNoticeId() == null
                || artifact.consentSnapshotSha256() == null
                || !artifact.consentSnapshotSha256().matches("^[0-9a-f]{64}$")) {
            throw notFound();
        }
        ConsentEvidence consent = intelligence.consentEvidence(
                subject.tenantId(), meetingId, artifact.contentNoticeId());
        if (!consent.complete()
                || consent.snapshotSha256() == null
                || !constantTimeEquals(
                        artifact.consentSnapshotSha256(), consent.snapshotSha256())) {
            throw notFound();
        }
        return artifact;
    }

    private boolean sameArtifact(TranscriptArtifact first, TranscriptArtifact second) {
        return first.artifactId().equals(second.artifactId())
                && first.version() == second.version()
                && constantTimeEquals(first.sourceSha256(), second.sourceSha256())
                && first.retentionUntil().equals(second.retentionUntil())
                && first.contentNoticeId().equals(second.contentNoticeId())
                && constantTimeEquals(
                        first.consentSnapshotSha256(), second.consentSnapshotSha256());
    }

    private boolean constantTimeEquals(String first, String second) {
        return first != null && second != null
                && MessageDigest.isEqual(
                        first.getBytes(StandardCharsets.US_ASCII),
                        second.getBytes(StandardCharsets.US_ASCII));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private BaseException notFound() {
        return new BaseException(
                ErrorCode.ENTITY_NOT_FOUND,
                "The transcript artifact was not found.");
    }

    private BaseException versionConflict() {
        return new BaseException(
                ErrorCode.OBJECT_VERSION_CONFLICT,
                "The transcript artifact changed. Refresh and retry.");
    }
}
