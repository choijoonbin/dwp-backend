package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Component
public class MeetingTranscriptFinalizationAssertionVerifier {

    private static final String PREFIX = "dwpaf1";
    private static final String METHOD = "POST";
    private static final long MAX_TTL_SECONDS = 60;

    private final String token;
    private final String keyId;
    private final byte[] secret;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public MeetingTranscriptFinalizationAssertionVerifier(
            @Value("${dwp.meeting.transcript-finalization.service-token:}") String token,
            @Value("${dwp.meeting.transcript-finalization.assertion-key-id:}") String keyId,
            @Value("${dwp.meeting.transcript-finalization.assertion-secret-base64:}")
            String secretBase64,
            ObjectMapper mapper) {
        this(token, keyId, secretBase64, mapper, Clock.systemUTC());
    }

    MeetingTranscriptFinalizationAssertionVerifier(
            String token,
            String keyId,
            String secretBase64,
            ObjectMapper mapper,
            Clock clock) {
        this.token = normalized(token);
        this.keyId = normalized(keyId);
        this.secret = decodeSecret(secretBase64);
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.clock = clock;
    }

    VerifiedAssertion verify(
            String presentedToken,
            String assertion,
            long tenantId,
            UUID meetingId,
            UUID artifactId,
            String semanticBodySha256) {
        if (!configured() || !constantTime(token, normalized(presentedToken))) {
            throw denied();
        }
        try {
            String[] compact = assertion == null ? new String[0] : assertion.split("\\.", -1);
            if (compact.length != 3 || !PREFIX.equals(compact[0])) throw denied();
            byte[] expected = hmac((PREFIX + "." + compact[1])
                    .getBytes(StandardCharsets.US_ASCII));
            byte[] signature = Base64.getUrlDecoder().decode(compact[2]);
            if (!MessageDigest.isEqual(expected, signature)) throw denied();
            AssertionPayload payload = mapper.readValue(
                    Base64.getUrlDecoder().decode(compact[1]), AssertionPayload.class);
            Instant now = clock.instant();
            String path = "/internal/v1/meetings/" + meetingId
                    + "/artifacts/transcript/finalize";
            if (payload.v() != 1 || !keyId.equals(payload.kid())
                    || !METHOD.equals(payload.method()) || !path.equals(payload.path())
                    || payload.tenantId() != tenantId
                    || !meetingId.equals(payload.meetingId())
                    || !artifactId.equals(payload.artifactId())
                    || !semanticBodySha256.equals(payload.bodySha256())
                    || payload.jti() == null || payload.iat() <= 0
                    || payload.exp() <= payload.iat()
                    || payload.exp() - payload.iat() > MAX_TTL_SECONDS
                    || payload.iat() > now.plusSeconds(5).getEpochSecond()
                    || payload.exp() <= now.getEpochSecond()) {
                throw denied();
            }
            return new VerifiedAssertion(
                    payload.jti(), Instant.ofEpochSecond(payload.exp()));
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException | java.io.IOException exception) {
            throw denied();
        }
    }

    private boolean configured() {
        return token.length() >= 32 && token.length() <= 4_096
                && token.chars().noneMatch(Character::isISOControl)
                && keyId.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{2,63}$")
                && secret.length >= 32 && secret.length <= 64;
    }

    private byte[] hmac(byte[] input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input);
        } catch (Exception exception) {
            throw denied();
        }
    }

    private byte[] decodeSecret(String value) {
        try {
            return Base64.getDecoder().decode(normalized(value));
        } catch (RuntimeException exception) {
            return new byte[0];
        }
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean constantTime(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private BaseException denied() {
        return new BaseException(
                ErrorCode.UNAUTHORIZED,
                "Trusted transcript artifact producer identity is required.");
    }

    record VerifiedAssertion(UUID jti, Instant expiresAt) {
    }

    private record AssertionPayload(
            int v,
            String kid,
            String method,
            String path,
            long tenantId,
            UUID meetingId,
            UUID artifactId,
            long iat,
            long exp,
            UUID jti,
            String bodySha256) {
    }
}
