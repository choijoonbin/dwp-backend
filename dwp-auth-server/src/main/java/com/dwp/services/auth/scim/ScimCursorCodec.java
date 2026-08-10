package com.dwp.services.auth.scim;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

@Component
public class ScimCursorCodec {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final long ttlSeconds;
    private final Clock clock;

    @Autowired
    public ScimCursorCodec(
            @Value("${dwp.scim.cursor-secret}") String secret,
            @Value("${dwp.scim.cursor-ttl-seconds:900}") long ttlSeconds) {
        this(secret, ttlSeconds, Clock.systemUTC());
    }

    ScimCursorCodec(String secret, long ttlSeconds, Clock clock) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("SCIM cursor secret must be at least 32 characters.");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = Math.max(60, ttlSeconds);
        this.clock = clock;
    }

    public String encode(
            String resourceType,
            Long tenantId,
            int offset,
            int count,
            String filter) {
        long expiresAt = Instant.now(clock).plusSeconds(ttlSeconds).getEpochSecond();
        String payload = String.join(":",
                "v1", tenantId.toString(), resourceType, Integer.toString(offset),
                Integer.toString(count), Long.toString(expiresAt), filterHash(filter));
        byte[] encodedPayload = ENCODER.encode(payload.getBytes(StandardCharsets.UTF_8));
        byte[] signature = sign(encodedPayload);
        return new String(encodedPayload, StandardCharsets.US_ASCII)
                + "." + ENCODER.encodeToString(signature);
    }

    public int decode(
            String cursor,
            String resourceType,
            Long tenantId,
            int count,
            String filter) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            String[] token = cursor.split("\\.", 2);
            if (token.length != 2) throw invalid();
            byte[] payload = token[0].getBytes(StandardCharsets.US_ASCII);
            byte[] expected = sign(payload);
            byte[] actual = DECODER.decode(token[1]);
            if (!MessageDigest.isEqual(expected, actual)) throw invalid();
            String decoded = new String(DECODER.decode(token[0]), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", 7);
            if (parts.length != 7
                    || !"v1".equals(parts[0])
                    || !tenantId.toString().equals(parts[1])
                    || !resourceType.equals(parts[2])
                    || count != Integer.parseInt(parts[4])
                    || Instant.now(clock).getEpochSecond() > Long.parseLong(parts[5])
                    || !filterHash(filter).equals(parts[6])) {
                throw invalid();
            }
            int offset = Integer.parseInt(parts[3]);
            if (offset < 0 || (count > 0 && offset % count != 0)) throw invalid();
            return offset;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("SCIM cursor signing failed.", exception);
        }
    }

    private String filterHash(String filter) {
        try {
            return ENCODER.encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest((filter == null ? "" : filter.trim()).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ScimException invalid() {
        return ScimException.invalidValue("The SCIM cursor is invalid or expired.");
    }
}
