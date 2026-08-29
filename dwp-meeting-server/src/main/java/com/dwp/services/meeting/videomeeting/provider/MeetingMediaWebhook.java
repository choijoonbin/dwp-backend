package com.dwp.services.meeting.videomeeting.provider;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Signed media-provider event boundary. Raw payloads never cross this interface. */
public interface MeetingMediaWebhook {

    ProviderEvent verify(String body, String authorization);

    enum EventType {
        ROOM_STARTED,
        ROOM_FINISHED,
        PARTICIPANT_JOINED,
        PARTICIPANT_LEFT,
        PARTICIPANT_CONNECTION_ABORTED
    }

    record ProviderEvent(
            String provider,
            String eventId,
            EventType type,
            OffsetDateTime createdAt,
            RoomBinding room,
            ParticipantBinding participant) {
    }

    record RoomBinding(
            String roomSid,
            String roomName,
            long tenantId,
            UUID meetingId,
            UUID incarnation,
            OffsetDateTime createdAt) {
    }

    record ParticipantBinding(
            String participantSid,
            UUID participantId,
            long userId,
            String identity,
            OffsetDateTime joinedAt) {
    }
}
