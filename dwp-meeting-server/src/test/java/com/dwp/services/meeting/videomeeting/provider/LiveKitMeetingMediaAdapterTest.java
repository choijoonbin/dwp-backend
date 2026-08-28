package com.dwp.services.meeting.videomeeting.provider;

import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AccessScope;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AttendanceState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.LifecycleState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.ParticipantRole;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.livekit.server.LiveKitAPI;
import io.livekit.server.RoomServiceClient;
import org.junit.jupiter.api.Test;
import retrofit2.Call;
import retrofit2.Response;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveKitMeetingMediaAdapterTest {

    @Test
    void retryAdoptsTheDeterministicRoomInsteadOfCreatingAnOrphanDuplicate() throws Exception {
        MeetingMediaProperties properties = configuredProperties();
        LiveKitAPI api = mock(LiveKitAPI.class);
        RoomServiceClient rooms = mock(RoomServiceClient.class);
        @SuppressWarnings("unchecked")
        Call<java.util.List<livekit.LivekitModels.Room>> listCall = mock(Call.class);
        livekit.LivekitModels.Room existing = livekit.LivekitModels.Room.newBuilder()
                .setName("dwp-meeting-t77-room").build();
        when(api.getRoom()).thenReturn(rooms);
        when(rooms.listRooms(java.util.List.of("dwp-meeting-t77-room")))
                .thenReturn(listCall);
        when(listCall.execute()).thenReturn(Response.success(java.util.List.of(existing)));
        LiveKitMeetingMediaAdapter adapter = new LiveKitMeetingMediaAdapter(properties, api);
        MeetingMediaProvider.PreparedRoom room =
                new MeetingMediaProvider.PreparedRoom("LIVEKIT", "dwp-meeting-t77-room");

        adapter.ensureRoom(room, 100);

        verify(rooms, never()).createRoom(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
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
    void tokenCarriesExplicitFailClosedMediaAndDataGrants() {
        MeetingMediaProperties properties = configuredProperties();
        LiveKitMeetingMediaAdapter adapter = new LiveKitMeetingMediaAdapter(properties);
        Meeting meeting = meeting();
        Participant participant = participant(meeting.meetingId());
        MeetingMediaProvider.EffectivePermissions permissions =
                new MeetingMediaProvider.EffectivePermissions(
                        true, true, false, true, false, false, false);

        MeetingMediaProvider.ParticipantToken token = adapter.issueParticipantToken(
                meeting, participant, subject(), permissions,
                OffsetDateTime.of(2026, 8, 27, 0, 0, 0, 0, ZoneOffset.UTC));

        String claims = new String(
                Base64.getUrlDecoder().decode(token.token().split("\\.")[1]),
                StandardCharsets.UTF_8);
        assertThat(claims)
                .contains("\"roomJoin\":true")
                .contains("\"canPublish\":true")
                .contains("\"canSubscribe\":true")
                .contains("\"canPublishData\":false")
                .contains("\"canUpdateOwnMetadata\":false")
                .contains("camera")
                .contains("microphone")
                .doesNotContain("screen_share");
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
        OffsetDateTime now = OffsetDateTime.of(
                2026, 8, 27, 0, 0, 0, 0, ZoneOffset.UTC);
        return new Meeting(
                UUID.randomUUID(), 77L, "Security review", null, null,
                LifecycleState.LIVE, AccessScope.INVITED, "7K9M4Q2X8R6T",
                now, now.plusHours(1), "Asia/Seoul", true, false,
                false, false, false, "LIVEKIT", "formal-room", 101L,
                subject().personPublicId(), "Park Hyunwoo", now, null, null,
                JsonNodeFactory.instance.arrayNode(), JsonNodeFactory.instance.arrayNode(),
                1, now.minusMinutes(5), now);
    }

    private Participant participant(UUID meetingId) {
        OffsetDateTime now = OffsetDateTime.of(
                2026, 8, 27, 0, 0, 0, 0, ZoneOffset.UTC);
        return new Participant(
                UUID.randomUUID(), 77L, meetingId, 101L, subject().personPublicId(),
                "hyunwoo.park@sk.com", "Park Hyunwoo", "Platform Lead",
                "Platform Engineering", ParticipantRole.ATTENDEE, AttendanceState.ADMITTED,
                true, now.minusMinutes(2), now.minusMinutes(1), 9L,
                null, null, null, null, 1);
    }
}
