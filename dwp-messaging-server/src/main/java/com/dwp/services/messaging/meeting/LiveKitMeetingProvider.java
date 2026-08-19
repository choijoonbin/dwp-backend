package com.dwp.services.messaging.meeting;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import io.livekit.server.AccessToken;
import io.livekit.server.LiveKitAPI;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import retrofit2.Response;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        prefix = "dwp.messaging.meeting",
        name = "provider",
        havingValue = "livekit")
public class LiveKitMeetingProvider implements MeetingProvider {

    private final MeetingProperties properties;
    private final LiveKitAPI api;

    public LiveKitMeetingProvider(MeetingProperties properties) {
        this.properties = properties;
        MeetingProperties.LiveKit livekit = properties.getLivekit();
        this.api = livekit.configured()
                ? LiveKitAPI.createClient(
                        livekit.getApiUrl(), livekit.getApiKey(), livekit.getApiSecret())
                : null;
    }

    @Override
    public MeetingProviderCapability capability() {
        boolean available = api != null;
        return new MeetingProviderCapability(
                available,
                "LIVEKIT",
                available ? null : "MEETING_PROVIDER_CONFIGURATION_INCOMPLETE",
                available,
                available,
                available,
                available,
                Math.toIntExact(properties.getTokenTtl().toSeconds()));
    }

    @Override
    public PreparedRoom prepareRoom(UUID sessionId, long tenantId, UUID conversationId) {
        requireAvailable();
        String roomName = "dwp-t" + tenantId + "-" + sessionId.toString().replace("-", "");
        try {
            Response<?> response = api.getRoom().createRoom(roomName).execute();
            if (!response.isSuccessful()) {
                throw providerFailure("LiveKit room creation failed with status " + response.code() + ".");
            }
            return new PreparedRoom("LIVEKIT", roomName);
        } catch (IOException exception) {
            throw providerFailure("LiveKit room creation could not be completed.", exception);
        }
    }

    @Override
    public ParticipantToken issueParticipantToken(
            MeetingSession session,
            MessagingRequestContext.Subject subject,
            OffsetDateTime issuedAt) {
        requireAvailable();
        AccessToken token = new AccessToken(
                properties.getLivekit().getApiKey(),
                properties.getLivekit().getApiSecret());
        token.setIdentity("tenant:" + subject.tenantId() + ":user:" + subject.userId());
        token.setName(displayName(subject));
        token.setTtl(properties.getTokenTtl().toMillis());
        token.addGrants(new RoomJoin(true), new RoomName(session.roomName()));
        OffsetDateTime expiresAt = issuedAt.plus(properties.getTokenTtl());
        return new ParticipantToken(
                properties.getLivekit().getClientUrl(), token.toJwt(), expiresAt);
    }

    @Override
    public void endRoom(MeetingSession session) {
        requireAvailable();
        try {
            Response<?> response = api.getRoom().deleteRoom(session.roomName()).execute();
            if (!response.isSuccessful() && response.code() != 404) {
                throw providerFailure("LiveKit room termination failed with status " + response.code() + ".");
            }
        } catch (IOException exception) {
            throw providerFailure("LiveKit room termination could not be completed.", exception);
        }
    }

    private String displayName(MessagingRequestContext.Subject subject) {
        if (subject.displayName() == null || subject.displayName().isBlank()) {
            return "DWP user " + subject.userId();
        }
        return subject.displayName().trim();
    }

    private void requireAvailable() {
        if (api == null) {
            throw providerFailure("LiveKit meeting provider configuration is incomplete.");
        }
    }

    private BaseException providerFailure(String message) {
        return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, message);
    }

    private BaseException providerFailure(String message, Exception cause) {
        return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, message, cause);
    }
}
