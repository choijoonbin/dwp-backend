package com.dwp.services.time.clockingestion;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Authenticated IAQ cursor bound to tenant, normalized query, population scope, and authority. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.time.clock-ingestion.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class ClockQueryCursorCodec {

    private static final String ALGORITHM = "HmacSHA256";
    private static final byte VERSION = 1;
    private static final int SIGNATURE_LENGTH = 32;
    private static final byte[] DOMAIN =
            "dwp.hris.time.clock-ingestion.cursor.v1\n".getBytes(StandardCharsets.UTF_8);

    private final byte[] secret;
    private final Duration ttl;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    ClockQueryCursorCodec(
            @Value("${dwp.hris.time.clock-ingestion.cursor-secret:}") String secret,
            @Value("${dwp.hris.time.clock-ingestion.cursor-ttl:PT15M}") Duration ttl,
            ObjectMapper objectMapper) {
        this(secret, ttl, objectMapper, Clock.systemUTC());
    }

    ClockQueryCursorCodec(String secret, Duration ttl, ObjectMapper objectMapper, Clock clock) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    String issue(
            long tenantId,
            long position,
            String fingerprint,
            String scopeRevision,
            String authorizationRevision) {
        requireConfigured();
        if (position < 0) throw invalid();
        CursorPayload payload = new CursorPayload(
                tenantId,
                position,
                fingerprint,
                scopeRevision,
                authorizationRevision,
                clock.instant().plus(ttl).getEpochSecond());
        try {
            byte[] encoded = objectMapper.writeValueAsBytes(payload);
            byte[] signature = sign(encoded);
            ByteBuffer envelope = ByteBuffer.allocate(
                    1 + Integer.BYTES + encoded.length + signature.length);
            envelope.put(VERSION).putInt(encoded.length).put(encoded).put(signature);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(envelope.array());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("TIM IAQ cursor serialization failed.", exception);
        }
    }

    long verify(
            String cursor,
            long tenantId,
            String fingerprint,
            String scopeRevision,
            String authorizationRevision) {
        requireConfigured();
        try {
            if (cursor == null || cursor.length() < 16 || cursor.length() > 1024
                    || !cursor.matches("[A-Za-z0-9_-]+")) {
                throw invalid();
            }
            byte[] envelope = Base64.getUrlDecoder().decode(cursor);
            if (envelope.length <= 1 + Integer.BYTES + SIGNATURE_LENGTH) throw invalid();
            ByteBuffer buffer = ByteBuffer.wrap(envelope);
            if (buffer.get() != VERSION) throw invalid();
            int payloadLength = buffer.getInt();
            if (payloadLength <= 0
                    || payloadLength != buffer.remaining() - SIGNATURE_LENGTH) {
                throw invalid();
            }
            byte[] payloadBytes = new byte[payloadLength];
            byte[] signature = new byte[SIGNATURE_LENGTH];
            buffer.get(payloadBytes).get(signature);
            if (!MessageDigest.isEqual(sign(payloadBytes), signature)) throw invalid();
            CursorPayload payload = objectMapper.readValue(payloadBytes, CursorPayload.class);
            if (payload.tenantId() != tenantId
                    || payload.position() < 0
                    || !fingerprint.equals(payload.fingerprint())
                    || !scopeRevision.equals(payload.scopeRevision())
                    || !authorizationRevision.equals(payload.authorizationRevision())
                    || payload.expiresAtEpochSecond() < clock.instant().getEpochSecond()) {
                throw invalid();
            }
            return payload.position();
        } catch (BaseException exception) {
            throw exception;
        } catch (IllegalArgumentException | IOException exception) {
            throw invalid();
        }
    }

    static String fingerprint(String canonicalRequest) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            mac.update(DOMAIN);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("TIM IAQ cursor signing failed.", exception);
        }
    }

    private void requireConfigured() {
        if (secret.length < 24) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "TIM IAQ cursor authority is not configured.");
        }
    }

    private BaseException invalid() {
        return new BaseException(
                ErrorCode.FORBIDDEN,
                "The TIM IAQ cursor is invalid, expired, or outside the current scope.");
    }

    private record CursorPayload(
            long tenantId,
            long position,
            String fingerprint,
            String scopeRevision,
            String authorizationRevision,
            long expiresAtEpochSecond) {}
}
