package com.dwp.services.people.directory;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Component
public class PeopleCursorCodec {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String VERSION = "v1";

    private final byte[] secret;
    private final Duration ttl;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public PeopleCursorCodec(
            @Value("${dwp.people.cursor-secret:}") String secret,
            @Value("${dwp.people.cursor-ttl:PT15M}") Duration ttl,
            ObjectMapper objectMapper) {
        this(secret, ttl, objectMapper, Clock.systemUTC());
    }

    PeopleCursorCodec(String secret, Duration ttl, ObjectMapper objectMapper, Clock clock) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public String encode(Long tenantId, long lastPersonId, String fingerprint) {
        requireConfigured();
        CursorPayload payload = new CursorPayload(
                tenantId,
                lastPersonId,
                fingerprint,
                clock.instant().plus(ttl).getEpochSecond());
        try {
            String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(payload));
            String signedValue = VERSION + "." + encodedPayload;
            String signature = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(sign(signedValue));
            return signedValue + "." + signature;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("People cursor serialization failed.", exception);
        }
    }

    public long decode(String cursor, Long tenantId, String fingerprint) {
        requireConfigured();
        try {
            String[] parts = cursor == null ? new String[0] : cursor.split("\\.");
            if (parts.length != 3 || !VERSION.equals(parts[0])) throw invalid();
            String signedValue = parts[0] + "." + parts[1];
            byte[] actualSignature = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(sign(signedValue), actualSignature)) throw invalid();
            CursorPayload payload = objectMapper.readValue(
                    Base64.getUrlDecoder().decode(parts[1]), CursorPayload.class);
            if (!tenantId.equals(payload.tenantId())
                    || !fingerprint.equals(payload.fingerprint())
                    || payload.expiresAtEpochSecond() < clock.instant().getEpochSecond()
                    || payload.lastPersonId() < 0) {
                throw invalid();
            }
            return payload.lastPersonId();
        } catch (IllegalArgumentException | IOException exception) {
            throw invalid();
        }
    }

    public String fingerprint(String query, String status, String asOf) {
        String normalized = normalize(query) + "|" + normalize(status) + "|" + normalize(asOf);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("People cursor signing failed.", exception);
        }
    }

    private void requireConfigured() {
        if (secret.length < 24) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "People cursor signing is not configured.");
        }
    }

    private BaseException invalid() {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The people cursor is invalid or expired.");
    }

    private record CursorPayload(
            Long tenantId,
            long lastPersonId,
            String fingerprint,
            long expiresAtEpochSecond) {
    }
}
