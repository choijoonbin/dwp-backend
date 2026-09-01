package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeetingRecordingArtifactAssertionVerifierTest {

    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");
    private static final String TOKEN = "recording-producer-" + "t".repeat(32);
    private static final String KEY_ID = "recording-producer-v1";
    private static final byte[] SECRET =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final UUID meetingId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID artifactId = UUID.randomUUID();

    @Test
    void verifiesTenantMeetingSessionArtifactBodyAndShortLivedWorkloadIdentity() {
        UUID jti = UUID.randomUUID();

        var verified = verifier().verify(
                TOKEN, assertion(jti, NOW.getEpochSecond(),
                        NOW.plusSeconds(30).getEpochSecond(), "a".repeat(64)),
                77, meetingId, sessionId, artifactId, "a".repeat(64));

        assertThat(verified.jti()).isEqualTo(jti);
        assertThat(verified.expiresAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void rejectsWrongDirectionSessionBodyTokenExpiryAndUnknownPayload() throws Exception {
        String valid = assertion(
                UUID.randomUUID(), NOW.getEpochSecond(),
                NOW.plusSeconds(30).getEpochSecond(), "a".repeat(64));

        assertDenied(() -> verifier().verify(
                "wrong", valid, 77, meetingId, sessionId, artifactId, "a".repeat(64)));
        assertDenied(() -> verifier().verify(
                TOKEN, valid, 77, meetingId, UUID.randomUUID(), artifactId, "a".repeat(64)));
        assertDenied(() -> verifier().verify(
                TOKEN, valid, 77, meetingId, sessionId, artifactId, "b".repeat(64)));
        assertDenied(() -> verifier().verify(
                TOKEN, assertion(UUID.randomUUID(), NOW.minusSeconds(60).getEpochSecond(),
                        NOW.minusSeconds(1).getEpochSecond(), "a".repeat(64)),
                77, meetingId, sessionId, artifactId, "a".repeat(64)));
        LinkedHashMap<String, Object> payload = payload(
                UUID.randomUUID(), NOW.getEpochSecond(),
                NOW.plusSeconds(30).getEpochSecond(), "a".repeat(64));
        payload.put("objectKey", "must-not-be-accepted");
        assertDenied(() -> verifier().verify(
                TOKEN, sign(payload), 77, meetingId, sessionId, artifactId,
                "a".repeat(64)));
    }

    @Test
    void rejectsSignedWrongTenantMeetingAndArtifactBindings() throws Exception {
        for (java.util.function.Consumer<LinkedHashMap<String, Object>> mutation :
                java.util.List.<java.util.function.Consumer<LinkedHashMap<String, Object>>>of(
                        payload -> payload.put("tenantId", 78),
                        payload -> payload.put("meetingId", UUID.randomUUID()),
                        payload -> payload.put("artifactId", UUID.randomUUID()))) {
            LinkedHashMap<String, Object> payload = payload(
                    UUID.randomUUID(), NOW.getEpochSecond(),
                    NOW.plusSeconds(30).getEpochSecond(), "a".repeat(64));
            mutation.accept(payload);
            assertDenied(() -> verifier().verify(
                    TOKEN, sign(payload), 77, meetingId, sessionId, artifactId,
                    "a".repeat(64)));
        }
    }

    @Test
    void rejectsSignedWrongMethodAndCanonicalPathBindings() throws Exception {
        LinkedHashMap<String, Object> method = payload(
                UUID.randomUUID(), NOW.getEpochSecond(),
                NOW.plusSeconds(30).getEpochSecond(), "a".repeat(64));
        method.put("method", "GET");
        assertDenied(() -> verifier().verify(
                TOKEN, sign(method), 77, meetingId, sessionId, artifactId,
                "a".repeat(64)));

        LinkedHashMap<String, Object> path = payload(
                UUID.randomUUID(), NOW.getEpochSecond(),
                NOW.plusSeconds(30).getEpochSecond(), "a".repeat(64));
        path.put("path", "/internal/v1/meetings/" + meetingId
                + "/artifacts/recording/finalize/suffix");
        assertDenied(() -> verifier().verify(
                TOKEN, sign(path), 77, meetingId, sessionId, artifactId,
                "a".repeat(64)));
    }

    @Test
    void rejectsFutureIssuedAtAndAssertionsLongerThanSixtySeconds() {
        assertDenied(() -> verifier().verify(
                TOKEN, assertion(UUID.randomUUID(), NOW.plusSeconds(6).getEpochSecond(),
                        NOW.plusSeconds(30).getEpochSecond(), "a".repeat(64)),
                77, meetingId, sessionId, artifactId, "a".repeat(64)));
        assertDenied(() -> verifier().verify(
                TOKEN, assertion(UUID.randomUUID(), NOW.getEpochSecond(),
                        NOW.plusSeconds(61).getEpochSecond(), "a".repeat(64)),
                77, meetingId, sessionId, artifactId, "a".repeat(64)));
    }

    private MeetingRecordingArtifactAssertionVerifier verifier() {
        return new MeetingRecordingArtifactAssertionVerifier(
                TOKEN, KEY_ID, Base64.getEncoder().encodeToString(SECRET), mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String assertion(UUID jti, long iat, long exp, String bodySha256) {
        try {
            return sign(payload(jti, iat, exp, bodySha256));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private LinkedHashMap<String, Object> payload(
            UUID jti, long iat, long exp, String bodySha256) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", 1);
        payload.put("kid", KEY_ID);
        payload.put("method", "POST");
        payload.put("path", "/internal/v1/meetings/" + meetingId
                + "/artifacts/recording/finalize");
        payload.put("tenantId", 77);
        payload.put("meetingId", meetingId);
        payload.put("recordingSessionId", sessionId);
        payload.put("artifactId", artifactId);
        payload.put("iat", iat);
        payload.put("exp", exp);
        payload.put("jti", jti);
        payload.put("bodySha256", bodySha256);
        return payload;
    }

    private String sign(Object payload) throws Exception {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mapper.writeValueAsBytes(payload));
        String input = "dwpraf1." + encoded;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
        return input + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
    }

    private void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BaseException.class)
                .hasMessage("Trusted recording artifact producer identity is required.")
                .hasNoCause();
    }
}
