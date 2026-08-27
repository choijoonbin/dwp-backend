package com.dwp.services.meeting.videomeeting.api;

import com.dwp.services.meeting.videomeeting.domain.VideoMeetingCollaborationModels.ChatMessage;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingCollaborationModels.HandRequest;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingCollaborationModels.StreamPage;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class VideoMeetingCollaborationDtos {

    private VideoMeetingCollaborationDtos() {
    }

    public record SendChatMessageCommand(
            @NotBlank @Size(max = 4000) String text) {
    }

    public record DeleteChatMessageCommand(
            @Size(max = 240) String reason) {
    }

    public record ParticipantSnapshotResponse(
            UUID participantId,
            long userId,
            UUID personPublicId,
            String displayName,
            String participantRole) {

        static ParticipantSnapshotResponse sender(ChatMessage message) {
            return new ParticipantSnapshotResponse(
                    message.participantId(), message.senderUserId(),
                    message.senderPersonPublicId(), message.senderDisplayName(),
                    message.senderRole().name());
        }

        static ParticipantSnapshotResponse requester(HandRequest request) {
            return new ParticipantSnapshotResponse(
                    request.participantId(), request.requesterUserId(),
                    request.requesterPersonPublicId(), request.requesterDisplayName(),
                    request.requesterRole().name());
        }
    }

    public record ChatMessageResponse(
            UUID messageId,
            long sequence,
            long createdSequence,
            ParticipantSnapshotResponse sender,
            String state,
            String text,
            OffsetDateTime sentAt,
            OffsetDateTime retentionUntil,
            OffsetDateTime deletedAt,
            boolean mine,
            boolean canDelete) {

        public static ChatMessageResponse from(
                ChatMessage message,
                Participant viewer) {
            boolean mine = message.senderUserId() == viewer.userId();
            return new ChatMessageResponse(
                    message.messageId(), message.lastSequence(), message.createdSequence(),
                    ParticipantSnapshotResponse.sender(message), message.state().name(),
                    message.text(), message.sentAt(), message.retentionUntil(),
                    message.deletedAt(), mine,
                    message.state().name().equals("ACTIVE") && (mine || viewer.canHost()));
        }
    }

    public record ChatMessagePageResponse(
            List<ChatMessageResponse> items,
            long nextSequence,
            boolean hasMore) {

        public static ChatMessagePageResponse from(
                StreamPage<ChatMessage> page,
                Participant viewer) {
            return new ChatMessagePageResponse(
                    page.items().stream().map(item -> ChatMessageResponse.from(item, viewer)).toList(),
                    page.nextSequence(), page.hasMore());
        }
    }

    public record HandRequestResponse(
            UUID requestId,
            long sequence,
            long raisedSequence,
            ParticipantSnapshotResponse requester,
            String state,
            OffsetDateTime raisedAt,
            OffsetDateTime acknowledgedAt,
            OffsetDateTime resolvedAt,
            boolean mine,
            boolean canLower,
            boolean canAcknowledge,
            boolean canDismiss) {

        public static HandRequestResponse from(
                HandRequest request,
                Participant viewer,
                boolean meetingLive) {
            boolean mine = request.requesterUserId() == viewer.userId();
            boolean active = request.state().active();
            return new HandRequestResponse(
                    request.requestId(), request.lastSequence(), request.raisedSequence(),
                    ParticipantSnapshotResponse.requester(request), request.state().name(),
                    request.raisedAt(), request.acknowledgedAt(), request.resolvedAt(), mine,
                    mine && active,
                    viewer.canHost() && meetingLive
                            && request.state().name().equals("RAISED"),
                    viewer.canHost() && active);
        }
    }

    public record HandRequestPageResponse(
            List<HandRequestResponse> items,
            long nextSequence,
            boolean hasMore) {

        public static HandRequestPageResponse from(
                StreamPage<HandRequest> page,
                Participant viewer,
                boolean meetingLive) {
            return new HandRequestPageResponse(
                    page.items().stream()
                            .map(item -> HandRequestResponse.from(item, viewer, meetingLive))
                            .toList(),
                    page.nextSequence(), page.hasMore());
        }
    }

    public record ClearHandRequestsResponse(
            int clearedCount,
            long sequence) {
    }
}
