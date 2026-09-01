package com.dwp.services.meeting.videomeeting.api;

import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptArtifactRepository.TranscriptArtifact;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class MeetingTranscriptArtifactDtos {

    private MeetingTranscriptArtifactDtos() {
    }

    public record RegisterTranscriptCommand(
            @NotNull UUID artifactId,
            @NotNull @Min(0) Long expectedContentPlanVersion,
            @NotNull UUID contentNoticeId,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String consentSnapshotSha256,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String sourceSha256,
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$")
            String processingRegion) {
    }

    public record FinalizeTranscriptCommand(
            @NotNull UUID artifactId,
            @NotNull @Min(0) Long expectedArtifactVersion,
            @NotNull @Min(0) Long expectedContentPlanVersion,
            @NotNull UUID contentNoticeId,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String consentSnapshotSha256,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String sourceSha256,
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$")
            String processingRegion,
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_-]{1,31}$") String storageProvider,
            @NotBlank @Size(max = 1_000) String objectKey,
            @NotBlank @Pattern(regexp = "^application/json$") String contentType,
            @Min(1) long sizeBytes) {
    }

    public record TranscriptArtifactResponse(
            UUID artifactId,
            String state,
            String sourceSha256,
            String processingRegion,
            UUID contentNoticeId,
            OffsetDateTime retentionUntil,
            OffsetDateTime finalizedAt,
            long version) {

        public static TranscriptArtifactResponse from(TranscriptArtifact artifact) {
            return new TranscriptArtifactResponse(
                    artifact.artifactId(), artifact.state(), artifact.sourceSha256(),
                    artifact.processingRegion(), artifact.contentNoticeId(),
                    artifact.retentionUntil(), artifact.finalizedAt(), artifact.version());
        }
    }
}
