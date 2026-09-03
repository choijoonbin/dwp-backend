package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptDeletionModels.CommandState;
import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptDeletionModels.DeletionArtifact;
import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptDeletionModels.DeletionCommand;
import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptDeletionModels.DeletionCycle;
import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptDeletionModels.Health;
import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptDeletionModels.PreparedDeletion;
import com.dwp.services.meeting.videomeeting.provider.MeetingTranscriptHttpProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingTranscriptSource.DeletionReceipt;
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
class MeetingTranscriptDeletionTransactions {

    private final MeetingTranscriptDeletionRepository repository;
    private final MeetingTranscriptDeletionProperties properties;
    private final MeetingTranscriptHttpProperties transcriptProperties;
    private final VideoMeetingAuditRecorder audit;
    private final Clock clock;

    @Autowired
    MeetingTranscriptDeletionTransactions(
            MeetingTranscriptDeletionRepository repository,
            MeetingTranscriptDeletionProperties properties,
            MeetingTranscriptHttpProperties transcriptProperties,
            VideoMeetingAuditRecorder audit) {
        this(repository, properties, transcriptProperties, audit, Clock.systemUTC());
    }

    MeetingTranscriptDeletionTransactions(
            MeetingTranscriptDeletionRepository repository,
            MeetingTranscriptDeletionProperties properties,
            MeetingTranscriptHttpProperties transcriptProperties,
            VideoMeetingAuditRecorder audit,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.transcriptProperties = transcriptProperties;
        this.audit = audit;
        this.clock = clock;
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
    public DeletionCycle renewCycle(DeletionCycle cycle) {
        OffsetDateTime now = now();
        requireCycle(cycle, now);
        OffsetDateTime renewedLease = now.plus(properties.getLeaseDuration());
        Health health = repository.renewCycle(
                        cycle.fence(), cycle.workerId(), cycle.leaseExpiresAt(),
                        now, renewedLease)
                .orElseThrow(() -> conflict(
                        "The transcript retention worker lease could not be renewed."));
        return new DeletionCycle(
                cycle.fence(), cycle.workerId(), health.activeLeaseExpiresAt());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PreparedDeletion prepareNext(
            DeletionCycle cycle,
            String providerCode,
            String storageProviderCode,
            boolean legacyLocatorDeletionAvailable) {
        OffsetDateTime now = now();
        requireCycle(cycle, now);
        DeletionArtifact artifact = repository.expiredCandidateForUpdate(now).orElse(null);
        if (artifact == null) return null;
        validateArtifact(artifact);
        if (providerCode == null
                || !providerCode.matches("^[A-Z][A-Z0-9_-]{2,47}$")) {
            throw unavailable("The transcript deletion provider is unavailable.");
        }
        if ((artifact.provenanceProviderCode() != null
                && (artifact.provenanceStorageProviderCode() == null
                    || !artifact.provenanceProviderCode().equals(providerCode)
                    || !artifact.provenanceStorageProviderCode().equals(
                            storageProviderCode)
                    || !artifact.storageProvider().equals(storageProviderCode)))
                || (artifact.provenanceProviderCode() == null
                    && !legacyLocatorDeletionAvailable)) {
            throw unavailable(
                    "The transcript artifact provider provenance cannot be deleted safely.");
        }
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
                throw conflict("The transcript deletion command no longer matches the artifact.");
            }
            command = repository.reclaim(
                    current, cycle.fence(), cycle.workerId(), now,
                    cycle.leaseExpiresAt());
        }
        String correlationId = "transcript-delete:" + command.commandId();
        audit.transcriptDeletion(
                artifact.tenantId(), artifact.meetingId(), artifact.artifactId(),
                "meeting.transcript-deletion.claimed", correlationId, "SUCCESS",
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
                .orElseThrow(() -> conflict("The transcript artifact is unavailable."));
        requireSameArtifact(prepared.artifact(), artifact);
        if (receipt == null || receipt.artifactId() == null
                || !receipt.artifactId().equals(artifact.artifactId())
                || receipt.artifactVersion() != artifact.version()
                || receipt.providerDeletionId() == null
                || !receipt.providerDeletionId().matches(
                        "^[A-Za-z0-9][A-Za-z0-9._:-]{2,159}$")
                || receipt.deletedAt() == null
                || receipt.deletedAt().isBefore(
                        command.requestedAt().minusSeconds(30))
                || receipt.deletedAt().isAfter(completedAt.plusMinutes(5))) {
            throw unavailable("The transcript deletion receipt is invalid.");
        }
        repository.markArtifactDeleted(artifact, command, completedAt);
        repository.succeedCommand(command, receipt.providerDeletionId(), completedAt);
        audit.transcriptDeletion(
                artifact.tenantId(), artifact.meetingId(), artifact.artifactId(),
                "meeting.transcript-deletion.completed", prepared.correlationId(),
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
        audit.transcriptDeletion(
                command.tenantId(), command.meetingId(), command.artifactId(),
                "meeting.transcript-deletion.failed", prepared.correlationId(),
                "FAILED", Map.of(
                        "artifactVersion", command.artifactVersion(),
                        "attempt", command.attemptCount(),
                        "provider", command.providerCode(),
                        "failureCode", failureCode));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeCycle(
            DeletionCycle cycle,
            String providerCode,
            String storageProviderCode) {
        OffsetDateTime completedAt = now();
        requireCycle(cycle, completedAt);
        repository.completeCycle(
                cycle.fence(), providerCode, storageProviderCode, completedAt);
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
                .orElseThrow(() -> conflict(
                        "The transcript deletion command is unavailable."));
        if (current.state() != CommandState.RUNNING
                || !current.executionFence().equals(prepared.cycle().fence())
                || !current.executionFence().equals(prepared.command().executionFence())
                || !current.leaseExpiresAt().isAfter(now)) {
            throw conflict("The transcript deletion command lease changed or expired.");
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
            throw conflict("The transcript retention worker lease changed or expired.");
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
            throw conflict("The transcript artifact changed during deletion.");
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
                || artifact.objectKey().contains("?")
                || artifact.objectKey().contains("#")
                || artifact.deletionBindingSha256() == null
                || !artifact.deletionBindingSha256().matches("^[0-9a-f]{64}$")) {
            throw unavailable("The transcript artifact deletion evidence is invalid.");
        }
    }

    private void validateConfiguration() {
        if (!new MeetingTranscriptDeletionReadiness(
                repository, properties, transcriptProperties, clock).validConfiguration()) {
            throw unavailable("Transcript retention is not configured.");
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
