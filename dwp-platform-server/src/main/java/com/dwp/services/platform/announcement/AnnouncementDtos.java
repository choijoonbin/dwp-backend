package com.dwp.services.platform.announcement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

public final class AnnouncementDtos {

    private AnnouncementDtos() {
    }

    public record AnnouncementDefinition(
            @NotBlank @Size(max = 160) String title,
            @NotBlank @Size(max = 1000) String message,
            @NotNull AnnouncementSeverity severity,
            @NotNull AnnouncementAudienceType audienceType,
            @Size(max = 80) String audienceValue,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            @NotNull Boolean pinned,
            @Size(max = 80) String actionLabel,
            @Size(max = 1000) String actionUrl) {
    }

    public record CreateAnnouncementRequest(
            @NotNull @Valid AnnouncementDefinition definition) {
    }

    public record UpdateAnnouncementRequest(
            @NotNull @Valid AnnouncementDefinition definition,
            @NotNull @Min(0) Long version) {
    }

    public record VersionRequest(@NotNull @Min(0) Long version) {
    }

    public record AnnouncementResponse(
            Long announcementId,
            String title,
            String message,
            AnnouncementSeverity severity,
            AnnouncementLifecycle lifecycleState,
            AnnouncementAudienceType audienceType,
            String audienceValue,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            Boolean pinned,
            String actionLabel,
            String actionUrl,
            OffsetDateTime publishedAt,
            Long publishedBy,
            Long version,
            LocalDateTime updatedAt,
            Long updatedBy) {
    }
}
