package com.dwp.services.messaging.meeting;

import com.dwp.services.messaging.security.MessagingRequestContext;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Provider boundary for realtime media. Provider credentials never cross this port. */
public interface MeetingProvider {

    MeetingProviderCapability capability();

    PreparedRoom prepareRoom(UUID sessionId, long tenantId, UUID conversationId);

    ParticipantToken issueParticipantToken(
            MeetingSession session,
            MessagingRequestContext.Subject subject,
            OffsetDateTime issuedAt);

    void endRoom(MeetingSession session);

    record PreparedRoom(String provider, String roomName) {
    }

    record ParticipantToken(String serverUrl, String token, OffsetDateTime expiresAt) {
    }
}
