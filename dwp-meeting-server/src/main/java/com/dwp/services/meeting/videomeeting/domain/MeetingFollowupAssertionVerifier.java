package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceDtos.Request;
import com.fasterxml.jackson.core.JsonParser;
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
import java.util.HexFormat;
import java.util.UUID;

@Component
public class MeetingFollowupAssertionVerifier {

    public static final String PATH = "/internal/v1/meeting-followups/resolve";
    public static final String HEADER = "X-DWP-Work-Assertion";
    private static final String PREFIX = "dwp1";
    private static final String ISSUER = "dwp-platform-work";
    private static final String AUDIENCE = "dwp-meeting-followup-source";
    private static final long MAX_TTL_SECONDS = 30;

    private final String keyId;
    private final byte[] secret;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public MeetingFollowupAssertionVerifier(
            @Value("${DWP_MEETING_WORK_ASSERTION_KEY_ID:}") String keyId,
            @Value("${DWP_MEETING_WORK_ASSERTION_SECRET_BASE64:}") String secretBase64,
            ObjectMapper mapper) {
        this(keyId, secretBase64, mapper, Clock.systemUTC());
    }

    MeetingFollowupAssertionVerifier(
            String keyId,
            String secretBase64,
            ObjectMapper mapper,
            Clock clock) {
        this.keyId = normalize(keyId);
        this.secret = decodeSecret(secretBase64);
        this.mapper = mapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        this.clock = clock;
    }

    public VerifiedAssertion verify(String assertion, byte[] exactBody, Request request) {
        if (!configured() || exactBody == null || request == null || request.source() == null
                || request.action() == null) {
            throw denied();
        }
        try {
            String[] compact = assertion == null ? new String[0] : assertion.split("\\.", -1);
            if (compact.length != 3 || !PREFIX.equals(compact[0])
                    || !canonicalBase64(compact[1]) || !canonicalBase64(compact[2])) {
                throw denied();
            }
            byte[] signature = Base64.getUrlDecoder().decode(compact[2]);
            if (signature.length != 32 || !MessageDigest.isEqual(
                    hmac((PREFIX + "." + compact[1]).getBytes(StandardCharsets.US_ASCII)),
                    signature)) {
                throw denied();
            }
            Claims claims = mapper.readValue(
                    Base64.getUrlDecoder().decode(compact[1]), Claims.class);
            Instant now = clock.instant();
            String bodySha256 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(exactBody));
            if (claims.v() != 1 || !keyId.equals(claims.kid())
                    || !ISSUER.equals(claims.iss()) || !AUDIENCE.equals(claims.aud())
                    || !"POST".equals(claims.method()) || !PATH.equals(claims.path())
                    || claims.tenantId() != request.tenantId()
                    || claims.actorUserId() != request.actorUserId()
                    || !claims.meetingId().equals(request.source().meetingId())
                    || !claims.reportId().equals(request.source().reportId())
                    || !claims.candidateId().equals(request.source().candidateId())
                    || claims.action() != request.action()
                    || !bodySha256.equals(claims.bodySha256())
                    || claims.jti() == null || claims.iat() <= 0
                    || claims.exp() <= claims.iat()
                    || claims.exp() - claims.iat() > MAX_TTL_SECONDS
                    || claims.iat() > now.plusSeconds(5).getEpochSecond()
                    || claims.exp() <= now.getEpochSecond()) {
                throw denied();
            }
            return new VerifiedAssertion(
                    claims.kid(), claims.jti(), claims.tenantId(), claims.actorUserId(),
                    claims.meetingId(), claims.reportId(), claims.candidateId(),
                    claims.action().name(), Instant.ofEpochSecond(claims.exp()));
        } catch (BaseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw denied();
        }
    }

    private boolean configured() {
        return keyId.matches("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
                && secret.length >= 32 && secret.length <= 64;
    }

    private boolean canonicalBase64(String value) {
        if (value == null || !value.matches("^[A-Za-z0-9_-]+$")) return false;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private byte[] hmac(byte[] input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(input);
    }

    private byte[] decodeSecret(String value) {
        try {
            return Base64.getDecoder().decode(normalize(value));
        } catch (RuntimeException exception) {
            return new byte[0];
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private BaseException denied() {
        return new BaseException(
                ErrorCode.UNAUTHORIZED,
                "Trusted Platform Work source assertion is required.");
    }

    public record VerifiedAssertion(
            String keyId,
            UUID jti,
            long tenantId,
            long actorUserId,
            UUID meetingId,
            UUID reportId,
            UUID candidateId,
            String action,
            Instant expiresAt) {
    }

    private record Claims(
            int v,
            String kid,
            String iss,
            String aud,
            String method,
            String path,
            long tenantId,
            long actorUserId,
            UUID meetingId,
            UUID reportId,
            UUID candidateId,
            com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceDtos.Operation action,
            long iat,
            long exp,
            UUID jti,
            String bodySha256) {
    }
}
