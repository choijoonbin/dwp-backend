package com.dwp.services.meeting.videomeeting.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

final class MeetingTranscriptDeletionModels {

    private MeetingTranscriptDeletionModels() {
    }

    enum CommandState {
        RUNNING, SUCCEEDED, FAILED
    }

    record DeletionArtifact(
            UUID artifactId,
            long tenantId,
            UUID meetingId,
            String artifactState,
            String provenanceProviderCode,
            String provenanceStorageProviderCode,
            String storageProvider,
            String objectKey,
            String deletionBindingSha256,
            OffsetDateTime retentionUntil,
            long version) {
    }

    record DeletionCommand(
            UUID commandId,
            long tenantId,
            UUID meetingId,
            UUID artifactId,
            long artifactVersion,
            String requestSha256,
            CommandState state,
            UUID executionFence,
            OffsetDateTime leaseExpiresAt,
            int attemptCount,
            String workerId,
            String providerCode,
            String providerDeletionId,
            String failureCode,
            OffsetDateTime requestedAt) {
    }

    record DeletionCycle(
            UUID fence,
            String workerId,
            OffsetDateTime leaseExpiresAt) {
    }

    record PreparedDeletion(
            DeletionCycle cycle,
            DeletionArtifact artifact,
            DeletionCommand command,
            String correlationId) {
    }

    record Health(
            OffsetDateTime lastSuccessAt,
            OffsetDateTime lastAttemptAt,
            OffsetDateTime lastFailureAt,
            String lastFailureCode,
            UUID activeFence,
            OffsetDateTime activeLeaseExpiresAt,
            String activeWorkerId,
            String lastProviderCode,
            String lastStorageProviderCode) {
    }
}
