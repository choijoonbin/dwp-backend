package com.dwp.services.meeting.videomeeting.provider;

import com.google.protobuf.util.JsonFormat;
import io.livekit.server.AccessToken;
import livekit.LivekitModels.ParticipantInfo;
import livekit.LivekitModels.Room;
import livekit.LivekitWebhook.WebhookEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveKitMeetingWebhookAdapterTest {

    private static final String API_KEY = "devkey";
    private static final String API_SECRET = "secretsecretsecretsecretsecretsecret";
    private static final UUID MEETING_ID = UUID.fromString(
            "034c0bb7-2236-4d04-bf10-aa830fc7960a");
    private static final UUID PARTICIPANT_ID = UUID.fromString(
            "57a4c3e5-05fd-4633-89ed-35b231c5de0c");
    private static final UUID ROOM_INCARNATION = UUID.fromString(
            "a460f11d-19af-4988-8ba4-65dd6f46af70");

    @Test
    void verifiesAndMapsEveryGovernedEventType() throws Exception {
        LiveKitMeetingWebhookAdapter adapter = adapter();
        Map<String, MeetingMediaWebhook.EventType> types = new LinkedHashMap<>();
        types.put("room_started", MeetingMediaWebhook.EventType.ROOM_STARTED);
        types.put("room_finished", MeetingMediaWebhook.EventType.ROOM_FINISHED);
        types.put("participant_joined", MeetingMediaWebhook.EventType.PARTICIPANT_JOINED);
        types.put("participant_left", MeetingMediaWebhook.EventType.PARTICIPANT_LEFT);
        types.put(
                "participant_connection_aborted",
                MeetingMediaWebhook.EventType.PARTICIPANT_CONNECTION_ABORTED);

        for (Map.Entry<String, MeetingMediaWebhook.EventType> entry : types.entrySet()) {
            boolean participantEvent = entry.getKey().startsWith("participant_");
            String body = body(entry.getKey(), roomMetadata(),
                    participantEvent ? participantMetadata(ROOM_INCARNATION) : null,
                    participantEvent ? identity(ROOM_INCARNATION) : null);

            MeetingMediaWebhook.ProviderEvent event = adapter.verify(body, sign(body));

            assertThat(event.provider()).isEqualTo("LIVEKIT");
            assertThat(event.eventId()).isEqualTo("EV_governed_1");
            assertThat(event.type()).isEqualTo(entry.getValue());
            assertThat(event.createdAt()).isNotNull();
            assertThat(event.room().roomSid()).isEqualTo("RM_governed_1");
            assertThat(event.room().roomName()).isEqualTo(
                    LiveKitMeetingMediaAdapter.roomName(
                            77L, MEETING_ID, ROOM_INCARNATION));
            assertThat(event.room().tenantId()).isEqualTo(77L);
            assertThat(event.room().meetingId()).isEqualTo(MEETING_ID);
            assertThat(event.room().incarnation()).isEqualTo(ROOM_INCARNATION);
            if (participantEvent) {
                assertThat(event.participant().participantSid())
                        .isEqualTo("PA_governed_1");
                assertThat(event.participant().participantId()).isEqualTo(PARTICIPANT_ID);
                assertThat(event.participant().userId()).isEqualTo(101L);
                assertThat(event.participant().identity())
                        .isEqualTo(identity(ROOM_INCARNATION));
            } else {
                assertThat(event.participant()).isNull();
            }
        }
    }

    @Test
    void rejectsAValidlySignedEventWithNonCanonicalRoomMetadata() throws Exception {
        String reordered = "{\"tenantId\":77,\"schemaVersion\":1,\"meetingId\":\""
                + MEETING_ID + "\",\"roomIncarnation\":\""
                + ROOM_INCARNATION + "\"}";
        String body = body("room_started", reordered, null, null);

        assertThatThrownBy(() -> adapter().verify(body, sign(body)))
                .hasMessageContaining("binding validation failed");
    }

    @Test
    void rejectsAValidlySignedParticipantWhoseIdentityAndMetadataIncarnationsDiffer()
            throws Exception {
        UUID otherIncarnation = UUID.fromString("fb8c3809-c452-49c9-aa71-f4d257ed7ba8");
        String body = body(
                "participant_joined", roomMetadata(),
                participantMetadata(otherIncarnation), identity(ROOM_INCARNATION));

        assertThatThrownBy(() -> adapter().verify(body, sign(body)))
                .hasMessageContaining("binding validation failed");
    }

    @Test
    void rejectsAnEventWhenTheOfficialSignatureCheckFails() throws Exception {
        String body = body("room_finished", roomMetadata(), null, null);
        AccessToken attacker = new AccessToken(
                API_KEY, "differentsecretsecretsecretsecretsecret");
        attacker.setSha256(bodySha256(body));

        assertThatThrownBy(() -> adapter().verify(body, "Bearer " + attacker.toJwt()))
                .hasMessageContaining("authentication or binding validation failed");
    }

    @Test
    void rejectsUnsupportedOrStructurallyAmbiguousEventsAfterSignatureVerification()
            throws Exception {
        String unsupported = body("track_published", roomMetadata(), null, null);
        String missingParticipant = body("participant_left", roomMetadata(), null, null);

        assertThatThrownBy(() -> adapter().verify(unsupported, sign(unsupported)))
                .hasMessageContaining("binding validation failed");
        assertThatThrownBy(() -> adapter().verify(
                missingParticipant, sign(missingParticipant)))
                .hasMessageContaining("binding validation failed");
    }

    private LiveKitMeetingWebhookAdapter adapter() {
        MeetingMediaProperties properties = new MeetingMediaProperties();
        properties.getLivekit().setApiUrl("http://localhost:7880");
        properties.getLivekit().setClientUrl("ws://localhost:7880");
        properties.getLivekit().setApiKey(API_KEY);
        properties.getLivekit().setApiSecret(API_SECRET);
        return new LiveKitMeetingWebhookAdapter(properties);
    }

    private String body(
            String eventType,
            String roomMetadata,
            String participantMetadata,
            String participantIdentity) throws Exception {
        Room room = Room.newBuilder()
                .setSid("RM_governed_1")
                .setName(LiveKitMeetingMediaAdapter.roomName(
                        77L, MEETING_ID, ROOM_INCARNATION))
                .setMetadata(roomMetadata)
                .setCreationTime(1_788_000_000L)
                .setCreationTimeMs(1_788_000_000_123L)
                .build();
        WebhookEvent.Builder event = WebhookEvent.newBuilder()
                .setEvent(eventType)
                .setId("EV_governed_1")
                .setCreatedAt(1_788_000_001L)
                .setRoom(room);
        if (participantMetadata != null || participantIdentity != null) {
            event.setParticipant(ParticipantInfo.newBuilder()
                    .setSid("PA_governed_1")
                    .setIdentity(participantIdentity == null ? "" : participantIdentity)
                    .setMetadata(participantMetadata == null ? "" : participantMetadata)
                    .setJoinedAt(1_788_000_000L)
                    .setJoinedAtMs(1_788_000_000_456L)
                    .build());
        }
        return JsonFormat.printer().omittingInsignificantWhitespace().print(event.build());
    }

    private String roomMetadata() {
        return LiveKitMeetingMediaAdapter.roomMetadata(
                77L, MEETING_ID, ROOM_INCARNATION);
    }

    private String identity(UUID incarnation) {
        return LiveKitMeetingMediaAdapter.participantIdentity(
                77L, MEETING_ID, PARTICIPANT_ID, incarnation, 101L);
    }

    private String participantMetadata(UUID incarnation) {
        return LiveKitMeetingMediaAdapter.participantMetadata(
                77L, MEETING_ID, PARTICIPANT_ID, incarnation,
                101L, "ATTENDEE", false);
    }

    private String sign(String body) throws Exception {
        AccessToken auth = new AccessToken(API_KEY, API_SECRET);
        auth.setSha256(bodySha256(body));
        auth.setTtl(300_000L);
        return "Bearer " + auth.toJwt();
    }

    private String bodySha256(String body) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }
}
