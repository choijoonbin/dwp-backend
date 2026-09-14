package com.dwp.services.meeting.videomeeting.api;

import jakarta.validation.constraints.Min;

import java.util.UUID;

public final class MeetingParticipantDisconnectDtos {
    private MeetingParticipantDisconnectDtos() {
    }

    public record DisconnectCommand(@Min(0) long expectedVersion) { }
    public record DisconnectResponse(UUID meetingId, UUID participantId, UUID commandId,
                                     String state, boolean blockedForCurrentSession) { }
}
