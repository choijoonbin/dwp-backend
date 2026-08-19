package com.dwp.services.messaging.attachment;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class AttachmentDtos {

    private AttachmentDtos() {
    }

    public record CreateUploadRequest(
            @NotBlank @Size(max = 255) String filename,
            @NotBlank @Size(max = 160) String contentType,
            @Min(1) long sizeBytes,
            @NotNull UUID idempotencyKey) {
    }

    public record AttachmentSummary(
            UUID attachmentId,
            String filename,
            String contentType,
            long sizeBytes,
            String status,
            String rejectionReason,
            OffsetDateTime createdAt,
            long version) {
    }

    public record UploadSession(
            AttachmentSummary attachment,
            String uploadUrl,
            OffsetDateTime expiresAt) {
    }

    public record DownloadGrant(
            UUID attachmentId,
            String filename,
            String contentType,
            long sizeBytes,
            String downloadUrl,
            OffsetDateTime expiresAt) {
    }
}
