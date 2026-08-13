package com.dwp.services.platform.announcement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
            @Size(max = 1000) String actionUrl,
            AnnouncementContentType contentType,
            @Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String categoryKey,
            @Size(max = 20000) String body,
            @Size(max = 500) String coverImageUrl,
            @Size(max = 160) String publisherName,
            Boolean featured,
            Boolean acknowledgementRequired,
            OffsetDateTime acknowledgementDueAt,
            Boolean dismissible,
            @Min(1) Short readingMinutes,
            @Pattern(regexp = "[a-z]{2}(-[A-Z]{2})?") String sourceLocale) {
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
            AnnouncementContentType contentType,
            String categoryKey,
            String body,
            String coverImageUrl,
            String publisherName,
            Boolean featured,
            Boolean acknowledgementRequired,
            OffsetDateTime acknowledgementDueAt,
            Boolean dismissible,
            Short readingMinutes,
            String sourceLocale,
            OffsetDateTime publishedAt,
            Long publishedBy,
            long uniqueViewerCount,
            long viewCount,
            long actionClickCount,
            long acknowledgementCount,
            Long version,
            LocalDateTime updatedAt,
            Long updatedBy) {
    }
}
