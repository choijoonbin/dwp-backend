package com.dwp.services.platform.communication;

import com.dwp.services.platform.announcement.AnnouncementContentType;
import com.dwp.services.platform.announcement.AnnouncementSeverity;
import io.swagger.v3.oas.annotations.media.Schema;
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
            long saved,
            @Schema(description = "Authoritative unread CRITICAL count across the reader's active feed.")
            long criticalUnread,
            @Schema(
                    description = "Authoritative union count of unacknowledged required and unread CRITICAL items.")
            long actionable) {

        public FeedSummary(long total, long unread, long required, long saved) {
            this(total, unread, required, saved, 0, required);
        }
    }

    public record FeedResponse(
            CommunicationItem featured,
            List<CommunicationItem> items,
            @Schema(
                    description = "Reader-wide action-first detail slice; intentionally independent of scope and query filters.")
            List<CommunicationItem> actionableItems,
            FeedSummary summary,
            OffsetDateTime generatedAt) {

        public FeedResponse(
                CommunicationItem featured,
                List<CommunicationItem> items,
                FeedSummary summary,
                OffsetDateTime generatedAt) {
            this(featured, items, List.of(), summary, generatedAt);
        }
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
