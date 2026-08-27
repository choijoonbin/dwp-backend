package com.dwp.services.meeting.videomeeting.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class VideoMeetingCollaborationModels {

    private VideoMeetingCollaborationModels() {
    }

    public enum ChatMessageState {
        ACTIVE, DELETED
    }

    public enum HandRequestState {
        RAISED, ACKNOWLEDGED, LOWERED, DISMISSED, CLEARED;

        public boolean active() {
            return this == RAISED || this == ACKNOWLEDGED;
        }
    }

    public record ChatMessage(
            UUID messageId,
            long tenantId,
            UUID meetingId,
            UUID participantId,
            long senderUserId,
            UUID senderPersonPublicId,
            String senderDisplayName,
            VideoMeetingModels.ParticipantRole senderRole,
            long createdSequence,
            long lastSequence,
            ChatMessageState state,
            String text,
            OffsetDateTime retentionUntil,
            OffsetDateTime sentAt,
            OffsetDateTime deletedAt) {
    }

    public record HandRequest(
            UUID requestId,
            long tenantId,
            UUID meetingId,
            UUID participantId,
            long requesterUserId,
            UUID requesterPersonPublicId,
            String requesterDisplayName,
            VideoMeetingModels.ParticipantRole requesterRole,
            long raisedSequence,
            long lastSequence,
            HandRequestState state,
            OffsetDateTime raisedAt,
            OffsetDateTime acknowledgedAt,
            Long acknowledgedBy,
            OffsetDateTime resolvedAt,
            Long resolvedBy) {
    }

    public record StreamPage<T>(
            List<T> items,
            long nextSequence,
            boolean hasMore) {
    }

    public record StoredCommand(
            String requestHash,
            UUID resultResourceId,
            long resultSequence,
            int resultCount) {
    }
}
