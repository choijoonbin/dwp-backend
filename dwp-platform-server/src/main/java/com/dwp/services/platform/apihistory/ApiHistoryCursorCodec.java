package com.dwp.services.platform.apihistory;

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
import java.util.UUID;

@Component
public class ApiHistoryCursorCodec {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String VERSION = "v1";

    private final byte[] secret;
    private final Duration ttl;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public ApiHistoryCursorCodec(
            @Value("${dwp.platform.api-history.cursor-secret:}") String secret,
            @Value("${dwp.platform.api-history.cursor-ttl:PT15M}") Duration ttl,
            ObjectMapper objectMapper) {
        this(secret, ttl, objectMapper, Clock.systemUTC());
    }

    ApiHistoryCursorCodec(
            String secret,
            Duration ttl,
            ObjectMapper objectMapper,
            Clock clock) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public String encode(
            Long tenantId,
            Instant occurredAt,
            UUID historyId,
            String fingerprint) {
        requireConfigured();
        CursorPayload payload = new CursorPayload(
                tenantId,
                occurredAt,
                historyId,
                fingerprint,
                clock.instant().plus(ttl).getEpochSecond());
        try {
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(payload));
            String signed = VERSION + "." + encoded;
            String signature = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(sign(signed));
            return signed + "." + signature;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("API history cursor serialization failed.", exception);
        }
    }

    public CursorPosition decode(String cursor, Long tenantId, String fingerprint) {
        requireConfigured();
        try {
            String[] parts = cursor == null ? new String[0] : cursor.split("\\.");
            if (parts.length != 3 || !VERSION.equals(parts[0])) throw invalid();
            String signed = parts[0] + "." + parts[1];
            if (!MessageDigest.isEqual(sign(signed), Base64.getUrlDecoder().decode(parts[2]))) {
                throw invalid();
            }
            CursorPayload payload = objectMapper.readValue(
                    Base64.getUrlDecoder().decode(parts[1]), CursorPayload.class);
            if (!tenantId.equals(payload.tenantId())
                    || !fingerprint.equals(payload.fingerprint())
                    || payload.expiresAtEpochSecond() < clock.instant().getEpochSecond()
                    || payload.occurredAt() == null
                    || payload.historyId() == null) {
                throw invalid();
            }
            return new CursorPosition(payload.occurredAt(), payload.historyId());
        } catch (IllegalArgumentException | IOException exception) {
            throw invalid();
        }
    }

    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("API history cursor signing failed.", exception);
        }
    }

    private void requireConfigured() {
        if (secret.length < 24) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "API history cursor signing is not configured.");
        }
    }

    private BaseException invalid() {
        return new BaseException(
                ErrorCode.INVALID_INPUT_VALUE,
                "The API history cursor is invalid or expired.");
    }

    public record CursorPosition(Instant occurredAt, UUID historyId) {
    }

    private record CursorPayload(
            Long tenantId,
            Instant occurredAt,
            UUID historyId,
            String fingerprint,
            long expiresAtEpochSecond) {
    }
}
