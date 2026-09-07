package com.dwp.services.meeting.videomeeting.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Operator-invoked local conformance probe. It creates, reconciles and deletes only a uniquely
 * named synthetic room; CI remains fail-closed/skipped unless the operator supplies credentials.
 */
@EnabledIfEnvironmentVariable(named = "DWP_LIVEKIT_SMOKE", matches = "true")
class LiveKitLocalOperationalSmokeTest {

    @Test
    void credentialedControlPlaneCreatesReconcilesAndDeletesABoundEphemeralRoom() {
        MeetingMediaProperties properties = new MeetingMediaProperties();
        properties.getLivekit().setApiUrl(required("DWP_LIVEKIT_API_URL"));
        properties.getLivekit().setClientUrl(required("DWP_LIVEKIT_CLIENT_URL"));
        properties.getLivekit().setApiKey(required("DWP_LIVEKIT_API_KEY"));
        properties.getLivekit().setApiSecret(required("DWP_LIVEKIT_API_SECRET"));
        LiveKitMeetingMediaAdapter adapter = new LiveKitMeetingMediaAdapter(properties);
        MeetingMediaProvider.PreparedRoom room = adapter.planRoom(
                UUID.randomUUID(), 999_999L, UUID.randomUUID());

        assertThat(adapter.operationallyReady()).isTrue();
        try {
            adapter.ensureRoom(room, 4);
            adapter.ensureRoom(room, 4);
        } finally {
            adapter.endRoom(room.roomName());
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the explicit smoke test");
        }
        return value;
    }
}
