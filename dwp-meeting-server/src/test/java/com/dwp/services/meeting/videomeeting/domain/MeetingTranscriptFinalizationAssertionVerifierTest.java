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

class MeetingTranscriptFinalizationAssertionVerifierTest {

    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
    private static final String TOKEN = "producer-token-" + "t".repeat(32);
    private static final String KEY_ID = "transcript-producer-v1";
    private static final byte[] SECRET =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final UUID meetingId = UUID.randomUUID();
    private final UUID artifactId = UUID.randomUUID();

    @Test
    void verifiesTenantMeetingArtifactSemanticBodyAndShortLivedWorkloadIdentity() {
        UUID jti = UUID.randomUUID();
        var verified = verifier().verify(
                TOKEN, assertion(jti, NOW.getEpochSecond(), NOW.plusSeconds(30).getEpochSecond(),
                        "a".repeat(64)),
                77, meetingId, artifactId, "a".repeat(64));

        assertThat(verified.jti()).isEqualTo(jti);
        assertThat(verified.expiresAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void registrationAssertionIsBoundToTheExactRegisterPath() {
        UUID jti = UUID.randomUUID();
        String registration = assertion(
                jti, NOW.getEpochSecond(), NOW.plusSeconds(30).getEpochSecond(),
                "a".repeat(64), "register");

        var verified = verifier().verifyRegistration(
                TOKEN, registration, 77, meetingId, artifactId, "a".repeat(64));

        assertThat(verified.jti()).isEqualTo(jti);
        assertDenied(() -> verifier().verify(
                TOKEN, registration, 77, meetingId, artifactId, "a".repeat(64)));
        assertDenied(() -> verifier().verifyRegistration(
                TOKEN,
                assertion(UUID.randomUUID(), NOW.getEpochSecond(),
                        NOW.plusSeconds(30).getEpochSecond(), "a".repeat(64)),
                77, meetingId, artifactId, "a".repeat(64)));
    }

    @Test
    void rejectsWrongTokenBodyBindingAndExpiredAssertionWithStableError() {
        String valid = assertion(
                UUID.randomUUID(), NOW.getEpochSecond(),
                NOW.plusSeconds(30).getEpochSecond(), "a".repeat(64));

        assertDenied(() -> verifier().verify(
                "wrong", valid, 77, meetingId, artifactId, "a".repeat(64)));
        assertDenied(() -> verifier().verify(
                TOKEN, valid, 77, meetingId, artifactId, "b".repeat(64)));
        assertDenied(() -> verifier().verify(
                TOKEN, assertion(UUID.randomUUID(), NOW.minusSeconds(60).getEpochSecond(),
                        NOW.minusSeconds(1).getEpochSecond(), "a".repeat(64)),
                77, meetingId, artifactId, "a".repeat(64)));
    }

    @Test
    void rejectsTtlAboveSixtySecondsAndUnknownPayloadFields() throws Exception {
        assertDenied(() -> verifier().verify(
                TOKEN, assertion(UUID.randomUUID(), NOW.getEpochSecond(),
                        NOW.plusSeconds(61).getEpochSecond(), "a".repeat(64)),
                77, meetingId, artifactId, "a".repeat(64)));
        LinkedHashMap<String, Object> payload = payload(
                UUID.randomUUID(), NOW.getEpochSecond(),
                NOW.plusSeconds(30).getEpochSecond(), "a".repeat(64));
        payload.put("objectKey", "must-not-be-accepted");
        assertDenied(() -> verifier().verify(
                TOKEN, sign(payload), 77, meetingId, artifactId, "a".repeat(64)));
    }

    private MeetingTranscriptFinalizationAssertionVerifier verifier() {
        return new MeetingTranscriptFinalizationAssertionVerifier(
                TOKEN, KEY_ID, Base64.getEncoder().encodeToString(SECRET), mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String assertion(UUID jti, long iat, long exp, String bodySha256) {
        return assertion(jti, iat, exp, bodySha256, "finalize");
    }

    private String assertion(
            UUID jti, long iat, long exp, String bodySha256, String operation) {
        try {
            LinkedHashMap<String, Object> payload = payload(jti, iat, exp, bodySha256);
            payload.put("path", "/internal/v1/meetings/" + meetingId
                    + "/artifacts/transcript/" + operation);
            return sign(payload);
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
                + "/artifacts/transcript/finalize");
        payload.put("tenantId", 77);
        payload.put("meetingId", meetingId);
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
        String input = "dwpaf1." + encoded;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
        return input + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
    }

    private void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BaseException.class)
                .hasMessage("Trusted transcript artifact producer identity is required.")
                .hasNoCause();
    }
}
