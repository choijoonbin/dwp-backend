package com.dwp.services.meeting.videomeeting.provider;

import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AccessScope;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AttendanceState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.LifecycleState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.ParticipantRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.livekit.server.LiveKitAPI;
import io.livekit.server.RoomServiceClient;
import livekit.LivekitModels.Room;
import org.junit.jupiter.api.Test;
import retrofit2.Call;
import retrofit2.Response;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveKitMeetingMediaAdapterTest {

    private static final UUID MEETING_ID = UUID.fromString(
            "034c0bb7-2236-4d04-bf10-aa830fc7960a");
    private static final UUID PARTICIPANT_ID = UUID.fromString(
            "57a4c3e5-05fd-4633-89ed-35b231c5de0c");
    private static final UUID ROOM_INCARNATION = UUID.fromString(
            "a460f11d-19af-4988-8ba4-65dd6f46af70");

    @Test
    void operationalReadinessUsesABoundedCredentialedControlPlaneProbe()
            throws Exception {
        LiveKitAPI api = mock(LiveKitAPI.class);
        RoomServiceClient rooms = mock(RoomServiceClient.class);
        @SuppressWarnings("unchecked")
        Call<java.util.List<Room>> listCall = mock(Call.class);
        okio.Timeout timeout = mock(okio.Timeout.class);
        when(api.getRoom()).thenReturn(rooms);
        when(rooms.listRooms(java.util.List.of())).thenReturn(listCall);
        when(listCall.timeout()).thenReturn(timeout);
        when(timeout.timeout(2L, TimeUnit.SECONDS)).thenReturn(timeout);
        when(listCall.execute()).thenReturn(Response.success(java.util.List.of()));

        boolean ready = new LiveKitMeetingMediaAdapter(
                configuredProperties(), api).operationallyReady();

        assertThat(ready).isTrue();
        verify(timeout).timeout(2L, TimeUnit.SECONDS);
    }

    @Test
    void operationalReadinessFailsClosedWhenTheControlPlaneIsUnavailable()
            throws Exception {
        LiveKitAPI api = mock(LiveKitAPI.class);
        RoomServiceClient rooms = mock(RoomServiceClient.class);
        @SuppressWarnings("unchecked")
        Call<java.util.List<Room>> listCall = mock(Call.class);
        okio.Timeout timeout = mock(okio.Timeout.class);
        when(api.getRoom()).thenReturn(rooms);
        when(rooms.listRooms(java.util.List.of())).thenReturn(listCall);
        when(listCall.timeout()).thenReturn(timeout);
        when(timeout.timeout(2L, TimeUnit.SECONDS)).thenReturn(timeout);
        when(listCall.execute()).thenThrow(new java.net.SocketTimeoutException("timeout"));

        boolean ready = new LiveKitMeetingMediaAdapter(
                configuredProperties(), api).operationallyReady();

        assertThat(ready).isFalse();
    }

    @Test
    void differentIncarnationsProduceDifferentExactRoomNamesAndMetadata() {
        LiveKitMeetingMediaAdapter adapter = new LiveKitMeetingMediaAdapter(
                configuredProperties());
        MeetingMediaProvider.PreparedRoom first = adapter.planRoom(
                MEETING_ID, 77L, ROOM_INCARNATION);
        UUID nextIncarnation = UUID.fromString("fb8c3809-c452-49c9-aa71-f4d257ed7ba8");
        MeetingMediaProvider.PreparedRoom next = adapter.planRoom(
                MEETING_ID, 77L, nextIncarnation);

        assertThat(first.roomName()).isEqualTo(
                "dwp-meeting-t77-034c0bb722364d04bf10aa830fc7960a"
                        + "-ia460f11d19af49888ba465dd6f46af70");
        assertThat(next.roomName()).isNotEqualTo(first.roomName());
        assertThat(next.roomMetadata()).isNotEqualTo(first.roomMetadata());
        assertThat(first.tenantId()).isEqualTo(77L);
        assertThat(first.meetingId()).isEqualTo(MEETING_ID);
        assertThat(first.incarnation()).isEqualTo(ROOM_INCARNATION);
    }

    @Test
    void retryAdoptsOnlyTheExactBoundRoomInsteadOfCreatingAnOrphanDuplicate()
            throws Exception {
        MeetingMediaProperties properties = configuredProperties();
        LiveKitAPI api = mock(LiveKitAPI.class);
        RoomServiceClient rooms = mock(RoomServiceClient.class);
        @SuppressWarnings("unchecked")
        Call<java.util.List<Room>> listCall = mock(Call.class);
        LiveKitMeetingMediaAdapter adapter = new LiveKitMeetingMediaAdapter(properties, api);
        MeetingMediaProvider.PreparedRoom room = adapter.planRoom(
                MEETING_ID, 77L, ROOM_INCARNATION);
        Room existing = Room.newBuilder()
                .setName(room.roomName())
                .setMetadata(room.roomMetadata())
                .build();
        when(api.getRoom()).thenReturn(rooms);
        when(rooms.listRooms(java.util.List.of(room.roomName()))).thenReturn(listCall);
        when(listCall.execute()).thenReturn(Response.success(java.util.List.of(existing)));

        adapter.ensureRoom(room, 100);

        verify(rooms, never()).createRoom(
                anyString(), any(), anyInt(), anyString());
    }

    @Test
    void retryRejectsAProviderRoomWhoseMetadataHasAnotherIncarnation() throws Exception {
        MeetingMediaProperties properties = configuredProperties();
        LiveKitAPI api = mock(LiveKitAPI.class);
        RoomServiceClient rooms = mock(RoomServiceClient.class);
        @SuppressWarnings("unchecked")
        Call<java.util.List<Room>> listCall = mock(Call.class);
        LiveKitMeetingMediaAdapter adapter = new LiveKitMeetingMediaAdapter(properties, api);
        MeetingMediaProvider.PreparedRoom room = adapter.planRoom(
                MEETING_ID, 77L, ROOM_INCARNATION);
        Room conflicting = Room.newBuilder()
                .setName(room.roomName())
                .setMetadata(LiveKitMeetingMediaAdapter.roomMetadata(
                        77L, MEETING_ID, UUID.randomUUID()))
                .build();
        when(api.getRoom()).thenReturn(rooms);
        when(rooms.listRooms(java.util.List.of(room.roomName()))).thenReturn(listCall);
        when(listCall.execute()).thenReturn(Response.success(java.util.List.of(conflicting)));

        assertThatThrownBy(() -> adapter.ensureRoom(room, 100))
                .hasMessageContaining("room binding");

        verify(rooms, never()).createRoom(
                anyString(), any(), anyInt(), anyString());
    }

    @Test
    void roomCreationCarriesCanonicalTenantMeetingAndIncarnationMetadata()
            throws Exception {
        MeetingMediaProperties properties = configuredProperties();
        LiveKitAPI api = mock(LiveKitAPI.class);
        RoomServiceClient rooms = mock(RoomServiceClient.class);
        @SuppressWarnings("unchecked")
        Call<java.util.List<Room>> listCall = mock(Call.class);
        @SuppressWarnings("unchecked")
        Call<Room> createCall = mock(Call.class);
        LiveKitMeetingMediaAdapter adapter = new LiveKitMeetingMediaAdapter(properties, api);
        MeetingMediaProvider.PreparedRoom room = adapter.planRoom(
                MEETING_ID, 77L, ROOM_INCARNATION);
        Room created = Room.newBuilder()
                .setName(room.roomName())
                .setMetadata(room.roomMetadata())
                .build();
        when(api.getRoom()).thenReturn(rooms);
        when(rooms.listRooms(java.util.List.of(room.roomName()))).thenReturn(listCall);
        when(listCall.execute()).thenReturn(Response.success(java.util.List.of()));
        when(rooms.createRoom(room.roomName(), null, 100, room.roomMetadata()))
                .thenReturn(createCall);
        when(createCall.execute()).thenReturn(Response.success(created));

        adapter.ensureRoom(room, 100);

        verify(rooms).createRoom(room.roomName(), null, 100, room.roomMetadata());
        assertThat(room.roomMetadata()).isEqualTo(
                "{\"schemaVersion\":1,\"tenantId\":77,\"meetingId\":\""
                        + MEETING_ID + "\",\"roomIncarnation\":\""
                        + ROOM_INCARNATION + "\"}");
    }

    @Test
    void retryTreatsAnAlreadyDeletedRoomAsSuccessfulTermination() throws Exception {
        MeetingMediaProperties properties = configuredProperties();
        LiveKitAPI api = mock(LiveKitAPI.class);
        RoomServiceClient rooms = mock(RoomServiceClient.class);
        @SuppressWarnings("unchecked")
        Call<Void> deleteCall = mock(Call.class);
        @SuppressWarnings("unchecked")
        Response<Void> missing = mock(Response.class);
        when(api.getRoom()).thenReturn(rooms);
        when(rooms.deleteRoom("dwp-meeting-t77-room")).thenReturn(deleteCall);
        when(deleteCall.execute()).thenReturn(missing);
        when(missing.isSuccessful()).thenReturn(false);
        when(missing.code()).thenReturn(404);
        LiveKitMeetingMediaAdapter adapter = new LiveKitMeetingMediaAdapter(properties, api);

        adapter.endRoom("dwp-meeting-t77-room");

        verify(rooms).deleteRoom("dwp-meeting-t77-room");
    }

    @Test
    void tokenCarriesExactRoomTtlGrantsIdentityMetadataAndRoomConfiguration()
            throws Exception {
        MeetingMediaProperties properties = configuredProperties();
        LiveKitMeetingMediaAdapter adapter = new LiveKitMeetingMediaAdapter(properties);
        Meeting meeting = meeting();
        Participant participant = participant(meeting.meetingId());
        MeetingMediaProvider.EffectivePermissions permissions =
                new MeetingMediaProvider.EffectivePermissions(
                        true, true, false, true, false, false, false);
        OffsetDateTime issuedAt = OffsetDateTime.of(
                2026, 8, 27, 0, 0, 0, 0, ZoneOffset.UTC);

        MeetingMediaProvider.ParticipantToken token = adapter.issueParticipantToken(
                meeting, participant, subject(), permissions, issuedAt, ROOM_INCARNATION);

        JsonNode claims = claims(token.token());
        JsonNode video = claims.path("video");
        assertThat(video.path("roomJoin").booleanValue()).isTrue();
        assertThat(video.path("room").textValue()).isEqualTo(meeting.roomName());
        assertThat(video.path("canPublish").booleanValue()).isTrue();
        assertThat(video.path("canSubscribe").booleanValue()).isTrue();
        assertThat(video.path("canPublishData").booleanValue()).isFalse();
        assertThat(video.path("canUpdateOwnMetadata").booleanValue()).isFalse();
        assertThat(video.path("canPublishSources").toString())
                .contains("camera", "microphone")
                .doesNotContain("screen_share");
        assertThat(claims.path("sub").textValue()).isEqualTo(
                LiveKitMeetingMediaAdapter.participantIdentity(
                        77L, MEETING_ID, PARTICIPANT_ID, ROOM_INCARNATION, 101L));
        assertThat(claims.path("metadata").textValue()).isEqualTo(
                LiveKitMeetingMediaAdapter.participantMetadata(
                        77L, MEETING_ID, PARTICIPANT_ID, ROOM_INCARNATION,
                        101L, "ATTENDEE", false));
        assertThat(claims.path("roomConfig").path("name").textValue())
                .isEqualTo(meeting.roomName());
        assertThat(claims.path("roomConfig").path("metadata").textValue())
                .isEqualTo(LiveKitMeetingMediaAdapter.roomMetadata(
                        77L, MEETING_ID, ROOM_INCARNATION));
        assertThat(token.expiresAt()).isEqualTo(issuedAt.plus(properties.getTokenTtl()));
        assertThat(claims.path("exp").longValue())
                .isEqualTo(token.expiresAt().toEpochSecond());
    }

    @Test
    void differentIncarnationsProduceDifferentExactTokenRoomGrants() throws Exception {
        UUID nextIncarnation = UUID.fromString("fb8c3809-c452-49c9-aa71-f4d257ed7ba8");
        LiveKitMeetingMediaAdapter adapter = new LiveKitMeetingMediaAdapter(
                configuredProperties());
        Meeting firstMeeting = meeting(ROOM_INCARNATION);
        Meeting nextMeeting = meeting(nextIncarnation);
        MeetingMediaProvider.EffectivePermissions permissions =
                new MeetingMediaProvider.EffectivePermissions(
                        false, false, false, true, false, false, false);
        OffsetDateTime issuedAt = OffsetDateTime.of(
                2026, 8, 27, 0, 0, 0, 0, ZoneOffset.UTC);

        JsonNode firstClaims = claims(adapter.issueParticipantToken(
                firstMeeting, participant(MEETING_ID), subject(), permissions,
                issuedAt, ROOM_INCARNATION).token());
        JsonNode nextClaims = claims(adapter.issueParticipantToken(
                nextMeeting, participant(MEETING_ID), subject(), permissions,
                issuedAt, nextIncarnation).token());

        assertThat(firstClaims.path("video").path("room").textValue())
                .isNotEqualTo(nextClaims.path("video").path("room").textValue());
        assertThat(firstClaims.path("roomConfig").path("metadata").textValue())
                .isNotEqualTo(nextClaims.path("roomConfig").path("metadata").textValue());
    }

    @Test
    void tokenFailsClosedWhenParticipantMeetingOrSubjectBindingDiffers() {
        LiveKitMeetingMediaAdapter adapter = new LiveKitMeetingMediaAdapter(
                configuredProperties());
        Meeting meeting = meeting();
        Participant participant = participant(UUID.randomUUID());
        MeetingMediaProvider.EffectivePermissions permissions =
                new MeetingMediaProvider.EffectivePermissions(
                        false, false, false, false, false, true, false);

        assertThatThrownBy(() -> adapter.issueParticipantToken(
                meeting, participant, subject(), permissions,
                OffsetDateTime.now(ZoneOffset.UTC), ROOM_INCARNATION))
                .hasMessageContaining("token binding");
    }

    @Test
    void explicitIncarnationTokenNeverFallsBackToALegacyRoomName() {
        UUID compatibilityIncarnation = UUID.nameUUIDFromBytes((
                "dwp-meeting-media-compat-v1|77|" + MEETING_ID)
                .getBytes(StandardCharsets.UTF_8));
        LiveKitMeetingMediaAdapter adapter = new LiveKitMeetingMediaAdapter(
                configuredProperties());
        Meeting legacy = meeting(
                compatibilityIncarnation,
                LiveKitMeetingMediaAdapter.roomName(77L, MEETING_ID));
        MeetingMediaProvider.EffectivePermissions permissions =
                new MeetingMediaProvider.EffectivePermissions(
                        false, false, false, true, false, false, false);

        assertThatThrownBy(() -> adapter.issueParticipantToken(
                legacy, participant(MEETING_ID), subject(), permissions,
                OffsetDateTime.now(ZoneOffset.UTC), compatibilityIncarnation))
                .hasMessageContaining("token binding");
    }

    private JsonNode claims(String jwt) throws Exception {
        String claims = new String(
                Base64.getUrlDecoder().decode(jwt.split("\\.")[1]),
                StandardCharsets.UTF_8);
        return new ObjectMapper().readTree(claims);
    }

    private MeetingMediaProperties configuredProperties() {
        MeetingMediaProperties properties = new MeetingMediaProperties();
        properties.getLivekit().setApiUrl("http://localhost:7880");
        properties.getLivekit().setClientUrl("ws://localhost:7880");
        properties.getLivekit().setApiKey("devkey");
        properties.getLivekit().setApiSecret("secretsecretsecretsecretsecretsecret");
        return properties;
    }

    private MeetingRequestContext.Subject subject() {
        return new MeetingRequestContext.Subject(
                101L, 77L, UUID.fromString("5af80da3-0dd8-b3bc-2f44-22d90eecaac4"),
                "Park Hyunwoo", Set.of("WORKSPACE_MEMBER"),
                Set.of("APP.MEETINGS:VIEW"), Set.of("SKAX_ALL_EMPLOYEES"));
    }

    private Meeting meeting() {
        return meeting(ROOM_INCARNATION);
    }

    private Meeting meeting(UUID incarnation) {
        return meeting(
                incarnation,
                LiveKitMeetingMediaAdapter.roomName(77L, MEETING_ID, incarnation));
    }

    private Meeting meeting(UUID incarnation, String roomName) {
        OffsetDateTime now = OffsetDateTime.of(
                2026, 8, 27, 0, 0, 0, 0, ZoneOffset.UTC);
        return new Meeting(
                MEETING_ID, 77L, "Security review", null, null,
                LifecycleState.LIVE, AccessScope.INVITED, "7K9M4Q2X8R6T",
                now, now.plusHours(1), "Asia/Seoul", true, false,
                false, false, false, "LIVEKIT",
                roomName,
                101L, subject().personPublicId(), "Park Hyunwoo", now, null, null,
                JsonNodeFactory.instance.arrayNode(), JsonNodeFactory.instance.arrayNode(),
                1, now.minusMinutes(5), now);
    }

    private Participant participant(UUID meetingId) {
        OffsetDateTime now = OffsetDateTime.of(
                2026, 8, 27, 0, 0, 0, 0, ZoneOffset.UTC);
        return new Participant(
                PARTICIPANT_ID, 77L, meetingId, 101L, subject().personPublicId(),
                "hyunwoo.park@sk.com", "Park Hyunwoo", "Platform Lead",
                "Platform Engineering", ParticipantRole.ATTENDEE, AttendanceState.ADMITTED,
                true, now.minusMinutes(2), now.minusMinutes(1), 9L,
                null, null, null, null, 1);
    }
}
