package com.dwp.services.messaging.meeting;

import com.dwp.services.messaging.security.MessagingRequestContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class LiveKitMeetingProviderTest {

    @Test
    void issuesRoomJoinOnlyTokenWithNamespacedIdentityAndBoundedTtl() throws Exception {
        MeetingProperties properties = properties();
        LiveKitMeetingProvider provider = new LiveKitMeetingProvider(properties);
        UUID conversationId = UUID.randomUUID();
        MeetingSession session = new MeetingSession(
                UUID.randomUUID(), 7L, conversationId, "LIVEKIT", "dwp-room-7",
                "ACTIVE", 42L, OffsetDateTime.now(ZoneOffset.UTC), null, null, 0L);
        MessagingRequestContext.Subject subject = new MessagingRequestContext.Subject(
                42L, 7L, UUID.randomUUID(), "Kim Minseo", Set.of(), Set.of(), Set.of());
        OffsetDateTime issuedAt = OffsetDateTime.of(
                2026, 8, 19, 10, 30, 0, 0, ZoneOffset.UTC);

        MeetingProvider.ParticipantToken result =
                provider.issueParticipantToken(session, subject, issuedAt);

        Map<String, Object> jwt = jwtPayload(result.token());
        assertThat(result.serverUrl()).isEqualTo("ws://localhost:7880");
        assertThat(result.expiresAt()).isEqualTo(issuedAt.plusMinutes(5));
        assertThat(jwt.get("iss")).isEqualTo("devkey");
        assertThat(jwt.get("sub")).isEqualTo("tenant:7:user:42");
        assertThat(jwt.get("name")).isEqualTo("Kim Minseo");
        @SuppressWarnings("unchecked")
        Map<String, Object> video = (Map<String, Object>) jwt.get("video");
        assertThat(video)
                .containsEntry("roomJoin", true)
                .containsEntry("room", "dwp-room-7")
                .doesNotContainKeys("roomAdmin", "roomRecord", "roomCreate");
        assertThat(result.token()).doesNotContain("devsecret");
    }

    @Test
    void incompleteLiveKitConfigurationIsReportedWithoutExposingSecrets() {
        MeetingProperties properties = new MeetingProperties();
        properties.setProvider("livekit");

        MeetingProviderCapability capability =
                new LiveKitMeetingProvider(properties).capability();

        assertThat(capability.available()).isFalse();
        assertThat(capability.provider()).isEqualTo("LIVEKIT");
        assertThat(capability.unavailableReason())
                .isEqualTo("MEETING_PROVIDER_CONFIGURATION_INCOMPLETE");
    }

    private MeetingProperties properties() {
        MeetingProperties properties = new MeetingProperties();
        properties.setProvider("livekit");
        properties.setTokenTtl(Duration.ofMinutes(5));
        properties.getLivekit().setApiUrl("http://localhost:7880");
        properties.getLivekit().setClientUrl("ws://localhost:7880");
        properties.getLivekit().setApiKey("devkey");
        properties.getLivekit().setApiSecret("devsecret");
        return properties;
    }

    private Map<String, Object> jwtPayload(String token) throws Exception {
        String encoded = token.split("\\.")[1];
        byte[] decoded = Base64.getUrlDecoder().decode(encoded);
        return new ObjectMapper().readValue(decoded, new TypeReference<>() {
        });
    }
}
