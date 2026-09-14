package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.MeetingCard;

import java.time.OffsetDateTime;
import java.util.List;

/** Immutable query results shared by the repository facade and its projection helpers. */
public final class VideoMeetingQueryModels {

    private VideoMeetingQueryModels() {
    }

    public record PagedMeetings(List<MeetingCard> items, long total) {
        public PagedMeetings {
            items = List.copyOf(items);
        }
    }

    public record AdminOverviewData(
            int liveMeetings,
            int scheduledToday,
            int waitingParticipants,
            int meetingsLastSevenDays,
            int failedJoinAttempts) {
    }

    public record HistoryItem(
            MeetingCard card,
            String publicationState,
            String retentionState,
            OffsetDateTime retentionUntil) {
    }

    public record PagedHistory(List<HistoryItem> items, long total) {
        public PagedHistory {
            items = List.copyOf(items);
        }
    }
}
