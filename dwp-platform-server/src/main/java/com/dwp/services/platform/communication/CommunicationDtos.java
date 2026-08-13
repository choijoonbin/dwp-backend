package com.dwp.services.platform.communication;

import com.dwp.services.platform.announcement.AnnouncementContentType;
import com.dwp.services.platform.announcement.AnnouncementSeverity;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class CommunicationDtos {

    private CommunicationDtos() {
    }

    public record ReaderState(
            boolean unread,
            boolean saved,
            boolean acknowledged,
            boolean dismissed,
            OffsetDateTime openedAt,
            OffsetDateTime savedAt,
            OffsetDateTime acknowledgedAt) {
    }

    public record CommunicationItem(
            Long communicationId,
            String title,
            String summary,
            String body,
            AnnouncementSeverity severity,
            AnnouncementContentType contentType,
            String categoryKey,
            String publisherName,
            String coverImageUrl,
            boolean featured,
            boolean pinned,
            boolean acknowledgementRequired,
            OffsetDateTime acknowledgementDueAt,
            boolean dismissible,
            short readingMinutes,
            String sourceLocale,
            String actionLabel,
            String actionUrl,
            OffsetDateTime publishedAt,
            OffsetDateTime endsAt,
            ReaderState readerState,
            ReactionSummary reactions) {
    }

    public record ReactionSummary(
            Map<CommunicationReaction, Long> counts,
            CommunicationReaction viewerReaction,
            long total) {
    }

    public record FeedSummary(
            long total,
            long unread,
            long required,
            long saved) {
    }

    public record FeedResponse(
            CommunicationItem featured,
            List<CommunicationItem> items,
            FeedSummary summary,
            OffsetDateTime generatedAt) {
    }

    public record ReaderPreferenceRequest(
            Boolean saved,
            Boolean dismissed) {
    }

    public record ReaderPreferenceResponse(
            @NotNull Long communicationId,
            @NotNull ReaderState readerState) {
    }

    public record ReactionRequest(CommunicationReaction reaction) {
    }
}
