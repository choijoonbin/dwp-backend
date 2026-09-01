package com.dwp.services.meeting.videomeeting.provider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

/** Compact dwp1.payload.signature HMAC assertion bound to one exact internal request. */
public final class MeetingWorkloadAssertionSigner {

    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();

    private final String keyId;
    private final byte[] secret;
    private final Duration ttl;
    private final Clock clock;
    private final Supplier<UUID> jtiSupplier;

    public MeetingWorkloadAssertionSigner(MeetingIntelligenceHttpProperties properties) {
        this(properties, Clock.systemUTC(), UUID::randomUUID);
    }

    MeetingWorkloadAssertionSigner(MeetingRecordingHttpProperties properties) {
        this(properties.getAssertionKeyId(), properties.getAssertionSecretBase64(),
                properties.getAssertionTtl(), Clock.systemUTC(), UUID::randomUUID);
    }

    MeetingWorkloadAssertionSigner(
            MeetingIntelligenceHttpProperties properties,
            Clock clock,
            Supplier<UUID> jtiSupplier) {
        this(properties.getAssertionKeyId(), properties.getAssertionSecretBase64(),
                properties.getAssertionTtl(), clock, jtiSupplier);
    }

    private MeetingWorkloadAssertionSigner(
            String keyId,
            String secretBase64,
            Duration ttl,
            Clock clock,
            Supplier<UUID> jtiSupplier) {
        this.keyId = requiredKeyId(keyId);
        this.secret = requiredSecret(secretBase64);
        this.ttl = requiredTtl(ttl);
        this.clock = clock;
        this.jtiSupplier = jtiSupplier;
    }

    public String sign(
            MeetingIntelligenceProvider.ExecutionContext context,
            String method,
            String path,
            byte[] body) {
        String canonicalMethod = requiredMethod(method);
        String canonicalPath = requiredPath(path);
        Instant issuedAt = clock.instant();
        long iat = issuedAt.getEpochSecond();
        long exp = issuedAt.plus(ttl).getEpochSecond();
        String payload = "{" +
                "\"v\":1," +
                "\"kid\":\"" + keyId + "\"," +
                "\"method\":\"" + canonicalMethod + "\"," +
                "\"path\":\"" + canonicalPath + "\"," +
                "\"tenantId\":" + context.tenantId() + "," +
                "\"meetingId\":\"" + context.meetingId() + "\"," +
                "\"runId\":\"" + context.runId() + "\"," +
                "\"iat\":" + iat + "," +
                "\"exp\":" + exp + "," +
                "\"jti\":\"" + jtiSupplier.get() + "\"," +
                "\"bodySha256\":\"" + sha256(body == null ? new byte[0] : body) + "\"}";
        String encoded = BASE64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signingInput = "dwp1." + encoded;
        return signingInput + "." + BASE64.encodeToString(hmac(signingInput));
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception exception) {
            throw new IllegalStateException("Meeting workload assertion signing failed.");
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.");
        }
    }

    private String requiredKeyId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$")) {
            throw new IllegalArgumentException("Meeting assertion key ID is invalid.");
        }
        return normalized;
    }

    private byte[] requiredSecret(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value == null ? "" : value.trim());
            if (decoded.length < 32 || decoded.length > 128) {
                throw new IllegalArgumentException("Meeting assertion secret is invalid.");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Meeting assertion secret is invalid.");
        }
    }

    private Duration requiredTtl(Duration value) {
        if (value == null || value.compareTo(Duration.ofSeconds(5)) < 0
                || value.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("Meeting assertion TTL is invalid.");
        }
        return value;
    }

    private String requiredMethod(String value) {
        if (!"GET".equals(value) && !"POST".equals(value)) {
            throw new IllegalArgumentException("Meeting assertion method is invalid.");
        }
        return value;
    }

    private String requiredPath(String value) {
        if (value == null || !value.matches("^/[A-Za-z0-9/_-]{1,240}$")) {
            throw new IllegalArgumentException("Meeting assertion path is invalid.");
        }
        return value;
    }
}
