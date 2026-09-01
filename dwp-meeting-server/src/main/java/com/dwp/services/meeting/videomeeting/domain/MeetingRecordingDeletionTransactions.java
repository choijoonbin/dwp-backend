package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingDeletionModels.CommandState;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingDeletionModels.DeletionArtifact;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingDeletionModels.DeletionCommand;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingDeletionModels.DeletionCycle;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingDeletionModels.Health;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingDeletionModels.PreparedDeletion;
import com.dwp.services.meeting.videomeeting.provider.MeetingRecordingProvider.DeletionReceipt;
import com.dwp.services.meeting.videomeeting.provider.MeetingRecordingHttpProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHash;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHashesMatch;

@Component
class MeetingRecordingDeletionTransactions {

    private final MeetingRecordingDeletionRepository repository;
    private final MeetingRecordingDeletionProperties properties;
    private final MeetingRecordingHttpProperties recordingProperties;
    private final VideoMeetingAuditRecorder audit;
    private final Clock clock;

    @Autowired
    MeetingRecordingDeletionTransactions(
            MeetingRecordingDeletionRepository repository,
            MeetingRecordingDeletionProperties properties,
            MeetingRecordingHttpProperties recordingProperties,
            VideoMeetingAuditRecorder audit) {
        this(repository, properties, recordingProperties, audit, Clock.systemUTC());
    }

    MeetingRecordingDeletionTransactions(
            MeetingRecordingDeletionRepository repository,
            MeetingRecordingDeletionProperties properties,
            VideoMeetingAuditRecorder audit,
            Clock clock) {
        this(repository, properties, new MeetingRecordingHttpProperties(), audit, clock);
    }

    MeetingRecordingDeletionTransactions(
            MeetingRecordingDeletionRepository repository,
            MeetingRecordingDeletionProperties properties,
            MeetingRecordingHttpProperties recordingProperties,
            VideoMeetingAuditRecorder audit,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.recordingProperties = recordingProperties;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeletionCycle renewCycle(DeletionCycle cycle) {
        OffsetDateTime now = now();
        requireCycle(cycle, now);
        OffsetDateTime renewedLease = now.plus(properties.getLeaseDuration());
        Health health = repository.renewCycle(
                        cycle.fence(), cycle.workerId(), cycle.leaseExpiresAt(),
                        now, renewedLease)
                .orElseThrow(() -> conflict(
                        "The recording retention worker lease could not be renewed."));
        return new DeletionCycle(
                cycle.fence(), cycle.workerId(), health.activeLeaseExpiresAt());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeletionCycle claimCycle() {
        validateConfiguration();
        OffsetDateTime now = now();
        UUID fence = UUID.randomUUID();
        OffsetDateTime leaseExpiresAt = now.plus(properties.getLeaseDuration());
        return repository.claimCycle(
                        properties.getWorkerId(), fence, now, leaseExpiresAt)
                .map(health -> new DeletionCycle(
                        fence, properties.getWorkerId(), health.activeLeaseExpiresAt()))
                .orElse(null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PreparedDeletion prepareNext(DeletionCycle cycle, String providerCode) {
        OffsetDateTime now = now();
        requireCycle(cycle, now);
        DeletionArtifact artifact = repository.expiredCandidateForUpdate(now).orElse(null);
        if (artifact == null) return null;
        validateArtifact(artifact);
        String hash = requestHash(
                artifact.tenantId(), artifact.meetingId(), artifact.artifactId(),
                artifact.version(), artifact.storageProvider(), artifact.objectKey(),
                artifact.deletionBindingSha256());
        Optional<DeletionCommand> existing = repository.commandForUpdate(
                artifact.tenantId(), artifact.meetingId(), artifact.artifactId());
        DeletionCommand command;
        if (existing.isEmpty()) {
            command = repository.insertCommand(
                    artifact, hash, cycle.fence(), cycle.workerId(), providerCode,
                    now, cycle.leaseExpiresAt());
        } else {
            DeletionCommand current = existing.get();
            if (current.artifactVersion() != artifact.version()
                    || !requestHashesMatch(current.requestSha256(), hash)
                    || !current.providerCode().equals(providerCode)) {
                throw conflict("The recording deletion command no longer matches the artifact.");
            }
            command = repository.reclaim(
                    current, cycle.fence(), cycle.workerId(), now,
                    cycle.leaseExpiresAt());
        }
        String correlationId = "recording-delete:" + command.commandId();
        audit.recordingDeletion(
                artifact.tenantId(), artifact.meetingId(), artifact.artifactId(),
                "meeting.recording-deletion.claimed", correlationId, "SUCCESS",
                Map.of("artifactVersion", artifact.version(),
                        "attempt", command.attemptCount(),
                        "retentionDue", artifact.retentionUntil() == null
                                ? "UNSPECIFIED_LEGACY" : artifact.retentionUntil().toString(),
                        "provider", command.providerCode()));
        return new PreparedDeletion(cycle, artifact, command, correlationId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(PreparedDeletion prepared, DeletionReceipt receipt) {
        OffsetDateTime completedAt = now();
        requireCycle(prepared.cycle(), completedAt);
        DeletionCommand command = current(prepared, completedAt);
        DeletionArtifact artifact = repository.artifactForUpdate(
                        command.tenantId(), command.meetingId(), command.artifactId())
                .orElseThrow(() -> conflict("The recording artifact is unavailable."));
        requireSameArtifact(prepared.artifact(), artifact);
        if (receipt == null || receipt.artifactId() == null
                || !receipt.artifactId().equals(artifact.artifactId())
                || receipt.artifactVersion() != artifact.version()
                || receipt.providerDeletionId() == null
                || !receipt.providerDeletionId().matches(
                        "^[A-Za-z0-9][A-Za-z0-9._:-]{2,159}$")
                || receipt.deletedAt() == null
                || receipt.deletedAt().isAfter(completedAt.plusMinutes(5))) {
            throw unavailable("The recording deletion receipt is invalid.");
        }
        repository.markArtifactDeleted(artifact, command, completedAt);
        repository.succeedCommand(command, receipt.providerDeletionId(), completedAt);
        audit.recordingDeletion(
                artifact.tenantId(), artifact.meetingId(), artifact.artifactId(),
                "meeting.recording-deletion.completed", prepared.correlationId(),
                "SUCCESS", Map.of(
                        "artifactVersion", artifact.version() + 1,
                        "attempt", command.attemptCount(),
                        "provider", command.providerCode(),
                        "deletedAt", completedAt.toString()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failDeletion(PreparedDeletion prepared, String failureCode) {
        OffsetDateTime failedAt = now();
        requireCycle(prepared.cycle(), failedAt);
        DeletionCommand command = current(prepared, failedAt);
        repository.failCommand(command, failureCode, failedAt);
        audit.recordingDeletion(
                command.tenantId(), command.meetingId(), command.artifactId(),
                "meeting.recording-deletion.failed", prepared.correlationId(),
                "FAILED", Map.of(
                        "artifactVersion", command.artifactVersion(),
                        "attempt", command.attemptCount(),
                        "provider", command.providerCode(),
                        "failureCode", failureCode));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeCycle(DeletionCycle cycle) {
        OffsetDateTime completedAt = now();
        requireCycle(cycle, completedAt);
        repository.completeCycle(cycle.fence(), completedAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failCycle(DeletionCycle cycle, String failureCode) {
        OffsetDateTime failedAt = now();
        requireCycle(cycle, failedAt);
        repository.failCycle(cycle.fence(), failureCode, failedAt);
    }

    private DeletionCommand current(PreparedDeletion prepared, OffsetDateTime now) {
        DeletionCommand current = repository.commandForUpdate(
                        prepared.command().tenantId(), prepared.command().meetingId(),
                        prepared.command().artifactId())
                .orElseThrow(() -> conflict("The recording deletion command is unavailable."));
        if (current.state() != CommandState.RUNNING
                || !current.executionFence().equals(prepared.cycle().fence())
                || !current.executionFence().equals(prepared.command().executionFence())
                || !current.leaseExpiresAt().isAfter(now)) {
            throw conflict("The recording deletion command lease changed or expired.");
        }
        return current;
    }

    private void requireCycle(DeletionCycle cycle, OffsetDateTime now) {
        Health health = repository.healthForUpdate();
        if (cycle == null || health.activeFence() == null
                || !health.activeFence().equals(cycle.fence())
                || !cycle.workerId().equals(health.activeWorkerId())
                || health.activeLeaseExpiresAt() == null
                || !health.activeLeaseExpiresAt().equals(cycle.leaseExpiresAt())
                || !health.activeLeaseExpiresAt().isAfter(now)) {
            throw conflict("The recording retention worker lease changed or expired.");
        }
    }

    private void requireSameArtifact(
            DeletionArtifact prepared, DeletionArtifact current) {
        String preparedHash = requestHash(
                prepared.tenantId(), prepared.meetingId(), prepared.artifactId(),
                prepared.version(), prepared.storageProvider(), prepared.objectKey(),
                prepared.deletionBindingSha256());
        String currentHash = requestHash(
                current.tenantId(), current.meetingId(), current.artifactId(),
                current.version(), current.storageProvider(), current.objectKey(),
                current.deletionBindingSha256());
        if (!requestHashesMatch(preparedHash, currentHash)) {
            throw conflict("The recording artifact changed during deletion.");
        }
    }

    private void validateArtifact(DeletionArtifact artifact) {
        if (artifact.tenantId() <= 0 || artifact.meetingId() == null
                || artifact.artifactId() == null || artifact.version() < 0
                || artifact.storageProvider() == null
                || !artifact.storageProvider().matches("^[A-Z][A-Z0-9_-]{1,31}$")
                || artifact.objectKey() == null || artifact.objectKey().isBlank()
                || artifact.objectKey().length() > 1_000
                || artifact.objectKey().chars().anyMatch(Character::isISOControl)
                || artifact.objectKey().contains("://")
                || artifact.deletionBindingSha256() == null
                || !artifact.deletionBindingSha256().matches("^[0-9a-f]{64}$")) {
            throw unavailable("The recording artifact deletion evidence is invalid.");
        }
    }

    private void validateConfiguration() {
        if (!new MeetingRecordingDeletionReadiness(
                repository, properties, recordingProperties, clock).validConfiguration()) {
            throw unavailable("Recording retention is not configured.");
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, message);
    }
}
