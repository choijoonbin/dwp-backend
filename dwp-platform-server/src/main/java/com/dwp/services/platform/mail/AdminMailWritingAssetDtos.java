package com.dwp.services.platform.mail;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class AdminMailWritingAssetDtos {

    private AdminMailWritingAssetDtos() {
    }

    public enum AssetKind { TEMPLATE, SIGNATURE }

    public enum PublicationState {
        DRAFT, PENDING_APPROVAL, APPROVED, PUBLISHED, RETIRED
    }

    public record DraftRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 500) String subject,
            @NotBlank @Size(max = 100_000) String body,
            @NotNull MailWorkspaceDtos.BodyFormat bodyFormat,
            @NotBlank @Size(max = 20_000) String mandatoryContent,
            boolean defaultForNew,
            boolean defaultForReply,
            UUID supersedesId,
            @Min(0) Long version) {
    }

    public record TransitionRequest(@NotNull @Min(0) Long version) {
    }

    public record OrganizationAsset(
            UUID assetId,
            AssetKind kind,
            UUID publicationKey,
            int publicationVersion,
            PublicationState publicationState,
            UUID supersedesId,
            String name,
            String subject,
            String body,
            MailWorkspaceDtos.BodyFormat bodyFormat,
            String mandatoryContent,
            boolean defaultForNew,
            boolean defaultForReply,
            long createdBy,
            Long approvedBy,
            OffsetDateTime submittedAt,
            OffsetDateTime approvedAt,
            OffsetDateTime publishedAt,
            OffsetDateTime retiredAt,
            long version,
            OffsetDateTime updatedAt) {
    }
}
