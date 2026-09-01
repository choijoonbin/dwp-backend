package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.MeetingRecordingAccessDtos;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingAccessModels.PreparedAccess;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingAccessModels.RecordingArtifact;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.LifecycleState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.TenantPolicy;
import com.dwp.services.meeting.videomeeting.provider.MeetingRecordingHttpProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingRecordingProvider.AccessTicket;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
class MeetingRecordingAccessTransactions {

    private static final Set<String> PLAYABLE_CONTENT_TYPES = Set.of(
            "video/mp4", "video/webm", "audio/mp4", "audio/mpeg",
            "audio/webm", "audio/ogg", "audio/wav");

    private final VideoMeetingRepository meetings;
    private final MeetingRecordingAccessRepository artifacts;
    private final VideoMeetingAuditRecorder audit;
    private final MeetingRecordingHttpProperties properties;
    private final Clock clock;

    @Autowired
    MeetingRecordingAccessTransactions(
            VideoMeetingRepository meetings,
            MeetingRecordingAccessRepository artifacts,
            VideoMeetingAuditRecorder audit,
            MeetingRecordingHttpProperties properties) {
        this(meetings, artifacts, audit, properties, Clock.systemUTC());
    }

    MeetingRecordingAccessTransactions(
            VideoMeetingRepository meetings,
            MeetingRecordingAccessRepository artifacts,
            VideoMeetingAuditRecorder audit,
            MeetingRecordingHttpProperties properties,
            Clock clock) {
        this.meetings = meetings;
        this.artifacts = artifacts;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PreparedAccess prepare(
            MeetingRequestContext.Subject subject,
            UUID meetingId,
            UUID artifactId,
            long expectedArtifactVersion,
            String correlationId) {
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        requireEnded(meeting);
        requirePlaybackPolicy(subject);
        Participant participant = admittedParticipant(subject, meeting);
        RecordingArtifact artifact = artifact(subject, meetingId, artifactId);
        requireVersion(artifact, expectedArtifactVersion);
        OffsetDateTime now = now();
        validateAvailable(artifact, now);
        OffsetDateTime expiresNoLaterThan = minimum(
                artifact.retentionUntil(), now.plus(accessTicketTtl()));
        audit.recordingAccess(
                subject, meeting, artifactId, "meeting.recording.access-requested",
                correlationId,
                Map.of("artifactVersion", artifact.version(),
                        "contentType", artifact.contentType(),
                        "participantRole", participant.participantRole().name(),
                        "expiresNoLaterThan", expiresNoLaterThan.toString()));
        return new PreparedAccess(
                subject, meeting, participant, artifact, expiresNoLaterThan, correlationId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MeetingRecordingAccessDtos.AccessTicketResponse complete(
            PreparedAccess prepared,
            AccessTicket ticket) {
        Meeting meeting = meetings.lockMeeting(
                prepared.subject().tenantId(), prepared.meeting().meetingId());
        requireEnded(meeting);
        requirePlaybackPolicy(prepared.subject());
        Participant participant = admittedParticipant(prepared.subject(), meeting);
        RecordingArtifact current = artifact(
                prepared.subject(), meeting.meetingId(), prepared.artifact().artifactId());
        requireVersion(current, prepared.artifact().version());
        validateAvailable(current, now());
        if (!sameLocator(prepared.artifact(), current)) {
            throw versionConflict();
        }
        if (ticket == null || ticket.artifactId() == null || ticket.accessUri() == null
                || ticket.expiresAt() == null
                || !ticket.artifactId().equals(current.artifactId())
                || ticket.requesterUserId() != prepared.subject().userId()
                || ticket.artifactVersion() != current.version()
                || !validAccessUri(ticket.accessUri(), current)
                || !ticket.expiresAt().isAfter(now())
                || ticket.expiresAt().isAfter(prepared.expiresNoLaterThan())
                || ticket.expiresAt().isAfter(current.retentionUntil())) {
            throw unavailable("The recording access ticket is invalid.");
        }
        audit.recordingAccess(
                prepared.subject(), meeting, current.artifactId(),
                "meeting.recording.access-issued", prepared.correlationId(),
                Map.of("artifactVersion", current.version(),
                        "contentType", current.contentType(),
                        "participantRole", participant.participantRole().name(),
                        "expiresAt", ticket.expiresAt().toString()));
        return new MeetingRecordingAccessDtos.AccessTicketResponse(
                current.artifactId(), current.version(), ticket.accessUri().toString(),
                ticket.expiresAt(), current.contentType());
    }

    private Participant admittedParticipant(
            MeetingRequestContext.Subject subject, Meeting meeting) {
        return meetings.participant(
                        subject.tenantId(), meeting.meetingId(), subject.userId())
                .filter(Participant::admitted)
                .orElseThrow(() -> notFound("The recording artifact was not found."));
    }

    private RecordingArtifact artifact(
            MeetingRequestContext.Subject subject, UUID meetingId, UUID artifactId) {
        return artifacts.recordingArtifactForUpdate(
                        subject.tenantId(), meetingId, artifactId)
                .orElseThrow(() -> notFound("The recording artifact was not found."));
    }

    private void validateAvailable(RecordingArtifact artifact, OffsetDateTime now) {
        if (!"AVAILABLE".equals(artifact.artifactState())
                || artifact.retentionUntil() == null
                || !artifact.retentionUntil().isAfter(now)
                || artifact.sizeBytes() == null || artifact.sizeBytes() <= 0
                || artifact.sha256() == null
                || !artifact.sha256().matches("^[0-9a-f]{64}$")
                || !PLAYABLE_CONTENT_TYPES.contains(artifact.contentType())
                || !safeCode(artifact.storageProvider(), 32)
                || artifact.objectKey() == null || artifact.objectKey().isBlank()
                || artifact.objectKey().length() > 1_000
                || artifact.objectKey().chars().anyMatch(Character::isISOControl)) {
            throw unavailable("The recording artifact is not available for playback.");
        }
    }

    private boolean sameLocator(RecordingArtifact first, RecordingArtifact second) {
        return Objects.equals(first.storageProvider(), second.storageProvider())
                && Objects.equals(first.objectKey(), second.objectKey())
                && Objects.equals(first.contentType(), second.contentType())
                && Objects.equals(first.sha256(), second.sha256())
                && Objects.equals(first.retentionUntil(), second.retentionUntil());
    }

    private void requireEnded(Meeting meeting) {
        if (meeting.lifecycleState() != LifecycleState.ENDED) {
            throw unavailable(
                    "The recording artifact is available only after the meeting ends.");
        }
    }

    private void requirePlaybackPolicy(MeetingRequestContext.Subject subject) {
        TenantPolicy policy = meetings.ensurePolicy(subject.tenantId(), subject.userId());
        if (!policy.meetingsEnabled() || "NEVER".equals(policy.recordingPolicy())) {
            throw unavailable("Recording playback is disabled by tenant policy.");
        }
    }

    private boolean validAccessUri(URI candidate, RecordingArtifact artifact) {
        if (candidate == null || candidate.toString().length() > 8_192) return false;
        String host = candidate.getHost() == null
                ? "" : candidate.getHost().toLowerCase(Locale.ROOT);
        Set<String> allowlist = properties.getAccessTicketAllowedHosts().stream()
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        String prefix = properties.getAccessTicketPathPrefix() == null
                ? "" : properties.getAccessTicketPathPrefix().trim();
        String rawQuery = candidate.getRawQuery();
        String decoded = candidate.getPath()
                + (candidate.getQuery() == null ? "" : "?" + candidate.getQuery());
        return "https".equalsIgnoreCase(candidate.getScheme())
                && !host.isBlank() && candidate.getUserInfo() == null
                && candidate.getFragment() == null
                && (candidate.getPort() == -1 || candidate.getPort() == 443)
                && prefix.matches("^/[A-Za-z0-9._~/-]{1,200}/$")
                && !prefix.contains("//") && !prefix.contains("/../")
                && !prefix.contains("/./")
                && candidate.getPath() != null && candidate.getPath().startsWith(prefix)
                && candidate.getPath().length() > prefix.length()
                && !host.equals("localhost") && !host.endsWith(".local")
                && !host.matches("^[0-9a-f:.]+$") && allowlist.contains(host)
                && (rawQuery == null || rawQuery.matches(
                        "^(token|ticket)=[A-Za-z0-9._~-]{16,4096}$"))
                && !candidate.toString().contains(artifact.objectKey())
                && !decoded.contains(artifact.objectKey())
                && !candidate.toString().contains(artifact.sha256())
                && !decoded.contains(artifact.sha256());
    }

    private Duration accessTicketTtl() {
        Duration ttl = properties.getAccessTicketTtl();
        if (ttl == null || ttl.compareTo(Duration.ofSeconds(30)) < 0
                || ttl.compareTo(Duration.ofMinutes(10)) > 0) {
            throw unavailable("The recording access broker is not configured.");
        }
        return ttl;
    }

    private boolean safeCode(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.length() <= maximumLength
                && value.matches("^[A-Za-z0-9][A-Za-z0-9._:-]*$");
    }

    private void requireVersion(RecordingArtifact artifact, long expectedVersion) {
        if (artifact.version() != expectedVersion) throw versionConflict();
    }

    private OffsetDateTime minimum(OffsetDateTime first, OffsetDateTime second) {
        return first.isBefore(second) ? first : second;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private BaseException notFound(String message) {
        return new BaseException(ErrorCode.ENTITY_NOT_FOUND, message);
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE, message);
    }

    private BaseException versionConflict() {
        return new BaseException(
                ErrorCode.OBJECT_VERSION_CONFLICT,
                "The recording artifact changed. Refresh and retry.");
    }
}
