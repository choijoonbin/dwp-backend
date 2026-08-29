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
import livekit.LivekitModels.Room;
import livekit.LivekitRoom.RoomConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import retrofit2.Response;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "dwp.meeting", name = "provider", havingValue = "livekit")
public class LiveKitMeetingMediaAdapter implements MeetingMediaProvider {

    private static final long READINESS_TIMEOUT_SECONDS = 2L;

    private final MeetingMediaProperties properties;
    private final LiveKitAPI api;

    @Autowired
    public LiveKitMeetingMediaAdapter(MeetingMediaProperties properties) {
        this(properties, createApi(properties));
    }

    LiveKitMeetingMediaAdapter(MeetingMediaProperties properties, LiveKitAPI api) {
        this.properties = properties;
        this.api = api;
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
    public boolean operationallyReady() {
        if (api == null) return false;
        try {
            retrofit2.Call<List<Room>> probe = api.getRoom().listRooms(List.of());
            probe.timeout().timeout(READINESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return probe.execute().isSuccessful();
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    @Override
    public PreparedRoom planRoom(UUID meetingId, long tenantId) {
        requireAvailable();
        UUID incarnation = compatibilityIncarnation(tenantId, meetingId);
        if (incarnation == null) {
            throw providerFailure("The LiveKit room binding is invalid.");
        }
        return new PreparedRoom(
                "LIVEKIT", roomName(tenantId, meetingId), tenantId, meetingId, incarnation);
    }

    @Override
    public PreparedRoom planRoom(UUID meetingId, long tenantId, UUID incarnation) {
        requireAvailable();
        if (tenantId <= 0 || meetingId == null || incarnation == null) {
            throw providerFailure("The LiveKit room binding is invalid.");
        }
        return new PreparedRoom(
                "LIVEKIT", roomName(tenantId, meetingId, incarnation),
                tenantId, meetingId, incarnation);
    }

    @Override
    public void ensureRoom(PreparedRoom room, int maximumParticipants) {
        requireAvailable();
        if (!validRoomPlan(room) || maximumParticipants <= 0) {
            throw providerFailure("The LiveKit room plan is invalid.");
        }
        try {
            Optional<Room> existing = findRoom(room.roomName());
            if (existing.isPresent()) {
                requireMatchingRoom(existing.orElseThrow(), room);
                return;
            }
            Response<Room> response = api.getRoom()
                    .createRoom(
                            room.roomName(), null, maximumParticipants, room.roomMetadata())
                    .execute();
            if (!response.isSuccessful()) {
                Optional<Room> reconciled = findRoom(room.roomName());
                if (reconciled.isPresent()) {
                    requireMatchingRoom(reconciled.orElseThrow(), room);
                    return;
                }
                throw providerFailure(
                        "LiveKit room creation failed with status " + response.code() + ".");
            }
            Room created = response.body();
            if (created != null) {
                requireMatchingRoom(created, room);
                return;
            }
            Optional<Room> reconciled = findRoom(room.roomName());
            if (reconciled.isEmpty()) {
                throw providerFailure("LiveKit room creation returned no bound room.");
            }
            requireMatchingRoom(reconciled.orElseThrow(), room);
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
        UUID incarnation = compatibilityIncarnation(
                meeting == null ? 0L : meeting.tenantId(),
                meeting == null ? null : meeting.meetingId());
        return issueParticipantToken(
                meeting, participant, subject, permissions, issuedAt, incarnation);
    }

    @Override
    public ParticipantToken issueParticipantToken(
            Meeting meeting,
            Participant participant,
            MeetingRequestContext.Subject subject,
            EffectivePermissions permissions,
            OffsetDateTime issuedAt,
            UUID roomIncarnation) {
        requireAvailable();
        requireTokenBinding(
                meeting, participant, subject, permissions, issuedAt, roomIncarnation);
        String roomMetadata = roomMetadata(
                meeting.tenantId(), meeting.meetingId(), roomIncarnation);
        AccessToken token = new AccessToken(
                properties.getLivekit().getApiKey(),
                properties.getLivekit().getApiSecret());
        token.setIdentity(participantIdentity(
                meeting.tenantId(), meeting.meetingId(), participant.participantId(),
                roomIncarnation, subject.userId()));
        token.setName(participant.displayName());
        token.setMetadata(participantMetadata(
                meeting.tenantId(), meeting.meetingId(), participant.participantId(),
                roomIncarnation, subject.userId(), participant.participantRole().name(),
                permissions.reactions()));
        token.setRoomConfiguration(RoomConfiguration.newBuilder()
                .setName(meeting.roomName())
                .setMetadata(roomMetadata)
                .build());
        OffsetDateTime expiresAt = issuedAt.plus(properties.getTokenTtl()).withNano(0);
        token.setTtl(properties.getTokenTtl().toMillis());
        token.setExpiration(Date.from(expiresAt.toInstant()));
        token.addGrants(
                new RoomJoin(true),
                new RoomName(meeting.roomName()),
                new CanSubscribe(true),
                new CanPublish(permissions.canPublishMedia()),
                new CanPublishSources(publishSources(permissions)),
                new CanPublishData(permissions.canPublishData()),
                new CanUpdateOwnMetadata(false));
        return new ParticipantToken(
                properties.getLivekit().getClientUrl(), token.toJwt(), expiresAt);
    }

    private void requireTokenBinding(
            Meeting meeting,
            Participant participant,
            MeetingRequestContext.Subject subject,
            EffectivePermissions permissions,
            OffsetDateTime issuedAt,
            UUID roomIncarnation) {
        if (meeting == null || participant == null || subject == null
                || permissions == null || issuedAt == null || roomIncarnation == null
                || meeting.meetingId() == null || participant.participantId() == null
                || participant.userId() == null || participant.participantRole() == null
                || meeting.tenantId() <= 0 || subject.tenantId() <= 0 || subject.userId() <= 0
                || participant.userId() != subject.userId()
                || participant.tenantId() != meeting.tenantId()
                || subject.tenantId() != meeting.tenantId()
                || !meeting.meetingId().equals(participant.meetingId())
                || !"LIVEKIT".equals(meeting.provider())
                || !validTokenRoomName(meeting, roomIncarnation)) {
            throw providerFailure("The LiveKit participant token binding is invalid.");
        }
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
    public void endRoom(String roomName) {
        requireAvailable();
        if (roomName == null || roomName.isBlank()) {
            throw providerFailure("The LiveKit room name is missing.");
        }
        try {
            Response<?> response = api.getRoom().deleteRoom(roomName).execute();
            if (!response.isSuccessful() && response.code() != 404) {
                throw providerFailure(
                        "LiveKit room termination failed with status " + response.code() + ".");
            }
        } catch (IOException exception) {
            throw providerFailure("LiveKit room termination could not be completed.", exception);
        }
    }

    private Optional<Room> findRoom(String roomName) throws IOException {
        Response<List<livekit.LivekitModels.Room>> response =
                api.getRoom().listRooms(List.of(roomName)).execute();
        if (!response.isSuccessful()) {
            throw providerFailure(
                    "LiveKit room reconciliation failed with status "
                            + response.code() + ".");
        }
        List<livekit.LivekitModels.Room> rooms = response.body();
        if (rooms == null) return Optional.empty();
        return rooms.stream().filter(room -> roomName.equals(room.getName())).findFirst();
    }

    private boolean validRoomPlan(PreparedRoom room) {
        return room != null
                && "LIVEKIT".equals(room.provider())
                && room.tenantId() > 0
                && room.meetingId() != null
                && room.incarnation() != null
                && validPlannedRoomName(room)
                && roomMetadata(room.tenantId(), room.meetingId(), room.incarnation())
                        .equals(room.roomMetadata());
    }

    private boolean validPlannedRoomName(PreparedRoom room) {
        if (roomName(room.tenantId(), room.meetingId(), room.incarnation())
                .equals(room.roomName())) {
            return true;
        }
        return compatibilityIncarnation(room.tenantId(), room.meetingId())
                .equals(room.incarnation())
                && roomName(room.tenantId(), room.meetingId()).equals(room.roomName());
    }

    private boolean validTokenRoomName(Meeting meeting, UUID incarnation) {
        return roomName(meeting.tenantId(), meeting.meetingId(), incarnation)
                .equals(meeting.roomName());
    }

    private void requireMatchingRoom(Room providerRoom, PreparedRoom plannedRoom) {
        if (!plannedRoom.roomName().equals(providerRoom.getName())
                || !plannedRoom.roomMetadata().equals(providerRoom.getMetadata())) {
            throw providerFailure("The LiveKit room binding does not match the room plan.");
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

    static String roomName(long tenantId, UUID meetingId) {
        if (tenantId <= 0 || meetingId == null) return "";
        return "dwp-meeting-t" + tenantId + "-"
                + meetingId.toString().replace("-", "");
    }

    static String roomName(long tenantId, UUID meetingId, UUID incarnation) {
        if (incarnation == null) return "";
        return roomName(tenantId, meetingId) + "-i"
                + incarnation.toString().replace("-", "");
    }

    static String roomMetadata(long tenantId, UUID meetingId, UUID incarnation) {
        return new PreparedRoom(
                "LIVEKIT", roomName(tenantId, meetingId, incarnation),
                tenantId, meetingId, incarnation)
                .roomMetadata();
    }

    static String participantIdentity(
            long tenantId,
            UUID meetingId,
            UUID participantId,
            UUID incarnation,
            long userId) {
        return "tenant:" + tenantId
                + ":meeting:" + meetingId
                + ":participant:" + participantId
                + ":incarnation:" + incarnation
                + ":user:" + userId;
    }

    static String participantMetadata(
            long tenantId,
            UUID meetingId,
            UUID participantId,
            UUID incarnation,
            long userId,
            String meetingRole,
            boolean reactionsAllowed) {
        return "{\"schemaVersion\":1,\"tenantId\":" + tenantId
                + ",\"meetingId\":\"" + meetingId
                + "\",\"participantId\":\"" + participantId
                + "\",\"roomIncarnation\":\"" + incarnation
                + "\",\"userId\":" + userId
                + ",\"meetingRole\":\"" + meetingRole
                + "\",\"reactionsAllowed\":" + reactionsAllowed + "}";
    }

    private static UUID compatibilityIncarnation(long tenantId, UUID meetingId) {
        if (tenantId <= 0 || meetingId == null) return null;
        String material = "dwp-meeting-media-compat-v1|" + tenantId + "|" + meetingId;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }

    private static LiveKitAPI createApi(MeetingMediaProperties properties) {
        MeetingMediaProperties.LiveKit livekit = properties.getLivekit();
        return livekit.configured()
                ? LiveKitAPI.createClient(
                        livekit.getApiUrl(), livekit.getApiKey(), livekit.getApiSecret())
                : null;
    }
}
