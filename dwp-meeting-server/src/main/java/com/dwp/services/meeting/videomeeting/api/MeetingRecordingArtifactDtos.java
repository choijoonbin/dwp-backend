package com.dwp.services.meeting.videomeeting.api;

import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingArtifactRepository.RecordingArtifact;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class MeetingRecordingArtifactDtos {

    private MeetingRecordingArtifactDtos() {
    }

    public record FinalizeRecordingCommand(
            @NotNull UUID artifactId,
            @NotNull UUID recordingSessionId,
            @NotNull @Min(0) Long expectedArtifactVersion,
            @NotNull @Min(0) Long expectedContentPlanVersion,
            @NotNull UUID contentNoticeId,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String consentSnapshotSha256,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String sourceSha256,
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$")
            String processingRegion,
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_-]{1,31}$")
            String storageProvider,
            @NotBlank @Size(max = 1_000) String objectKey,
            @NotBlank @Size(max = 120) String contentType,
            @Min(1) long sizeBytes,
            @NotNull OffsetDateTime retentionUntil) {
    }

    public record RecordingArtifactResponse(
            UUID artifactId,
            UUID recordingSessionId,
            String state,
            String contentType,
            long sizeBytes,
            OffsetDateTime retentionUntil,
            OffsetDateTime finalizedAt,
            long version) {

        public static RecordingArtifactResponse from(RecordingArtifact artifact) {
            return new RecordingArtifactResponse(
                    artifact.artifactId(), artifact.recordingSessionId(),
                    artifact.state(), artifact.contentType(), artifact.sizeBytes(),
                    artifact.retentionUntil(), artifact.recordingFinalizedAt(),
                    artifact.version());
        }
    }
}
