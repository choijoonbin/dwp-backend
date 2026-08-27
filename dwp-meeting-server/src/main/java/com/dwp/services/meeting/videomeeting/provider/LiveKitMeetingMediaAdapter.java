package com.dwp.services.meeting.videomeeting.provider;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanPublishSources;
import io.livekit.server.CanSubscribe;
import io.livekit.server.CanUpdateOwnMetadata;
import io.livekit.server.LiveKitAPI;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import retrofit2.Response;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "dwp.meeting", name = "provider", havingValue = "livekit")
public class LiveKitMeetingMediaAdapter implements MeetingMediaProvider {

    private final MeetingMediaProperties properties;
    private final LiveKitAPI api;

    public LiveKitMeetingMediaAdapter(MeetingMediaProperties properties) {
        this.properties = properties;
        MeetingMediaProperties.LiveKit livekit = properties.getLivekit();
        this.api = livekit.configured()
                ? LiveKitAPI.createClient(
                        livekit.getApiUrl(), livekit.getApiKey(), livekit.getApiSecret())
                : null;
    }

    @Override
    public Capability capability() {
        boolean available = api != null;
        return new Capability(
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
    public PreparedRoom prepareRoom(UUID meetingId, long tenantId, int maximumParticipants) {
        requireAvailable();
        String roomName = "dwp-meeting-t" + tenantId + "-"
                + meetingId.toString().replace("-", "");
        try {
            Response<?> response = api.getRoom()
                    .createRoom(roomName, null, maximumParticipants)
                    .execute();
            if (!response.isSuccessful()) {
                throw providerFailure(
                        "LiveKit room creation failed with status " + response.code() + ".");
            }
            return new PreparedRoom("LIVEKIT", roomName);
        } catch (IOException exception) {
            throw providerFailure("LiveKit room creation could not be completed.", exception);
        }
    }

    @Override
    public ParticipantToken issueParticipantToken(
            Meeting meeting,
            Participant participant,
            MeetingRequestContext.Subject subject,
            EffectivePermissions permissions,
            OffsetDateTime issuedAt) {
        requireAvailable();
        AccessToken token = new AccessToken(
                properties.getLivekit().getApiKey(),
                properties.getLivekit().getApiSecret());
        token.setIdentity("tenant:" + subject.tenantId() + ":user:" + subject.userId());
        token.setName(participant.displayName());
        token.setMetadata("{\"meetingRole\":\"" + participant.participantRole().name()
                + "\",\"meetingId\":\"" + meeting.meetingId() + "\"}");
        token.setTtl(properties.getTokenTtl().toMillis());
        token.addGrants(
                new RoomJoin(true),
                new RoomName(meeting.roomName()),
                new CanSubscribe(true),
                new CanPublish(permissions.canPublishMedia()),
                new CanPublishSources(publishSources(permissions)),
                new CanPublishData(permissions.canPublishData()),
                new CanUpdateOwnMetadata(false));
        OffsetDateTime expiresAt = issuedAt.plus(properties.getTokenTtl());
        return new ParticipantToken(
                properties.getLivekit().getClientUrl(), token.toJwt(), expiresAt);
    }

    private List<String> publishSources(EffectivePermissions permissions) {
        List<String> sources = new ArrayList<>();
        if (permissions.camera()) sources.add("camera");
        if (permissions.microphone()) sources.add("microphone");
        if (permissions.screenShare()) {
            sources.add("screen_share");
            sources.add("screen_share_audio");
        }
        return List.copyOf(sources);
    }

    @Override
    public void endRoom(Meeting meeting) {
        requireAvailable();
        try {
            Response<?> response = api.getRoom().deleteRoom(meeting.roomName()).execute();
            if (!response.isSuccessful() && response.code() != 404) {
                throw providerFailure(
                        "LiveKit room termination failed with status " + response.code() + ".");
            }
        } catch (IOException exception) {
            throw providerFailure("LiveKit room termination could not be completed.", exception);
        }
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
