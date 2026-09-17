package com.dwp.services.platform.home.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.platform.home.runtime.DwaionHomeWorkloadProtocol.*;

/** Issues one short-lived, single-use assertion for an exact DWAI-ON Home request. */
final class DwaionHomeWorkloadAssertionSigner {

    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private static final int MAX_ASSERTION_BYTES = 16_384;

    private final String keyId;
    private final byte[] secret;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Supplier<UUID> nonce;

    DwaionHomeWorkloadAssertionSigner(String keyId, String signingSecret, ObjectMapper mapper) {
        this(keyId, signingSecret, mapper, Clock.systemUTC(), UUID::randomUUID);
    }

    DwaionHomeWorkloadAssertionSigner(
            String keyId,
            String signingSecret,
            ObjectMapper mapper,
            Clock clock,
            Supplier<UUID> nonce) {
        String normalizedKeyId = keyId == null ? "" : keyId.trim();
        if (!normalizedKeyId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{2,79}")) {
            throw new IllegalArgumentException("DWAI-ON Home assertion key ID is invalid.");
        }
        byte[] configuredSecret = signingSecret == null
                ? new byte[0] : signingSecret.trim().getBytes(StandardCharsets.UTF_8);
        if (configuredSecret.length < 32 || configuredSecret.length > 256) {
            throw new IllegalArgumentException("DWAI-ON Home assertion secret is invalid.");
        }
        this.keyId = normalizedKeyId;
        this.secret = configuredSecret.clone();
        this.mapper = mapper;
        this.clock = clock;
        this.nonce = nonce;
    }

    String sign(HomeRuntimeContext context, OffsetDateTime deadline, byte[] exactBody) {
        long issuedAt = clock.instant().getEpochSecond();
        long expiresAt = Math.min(
                issuedAt + 30,
                Math.min(ceilEpoch(deadline), ceilEpoch(context.authorityRevalidateAt())));
        if (expiresAt <= issuedAt) {
            throw new WidgetProviderException(
                    WidgetProviderException.Kind.TIMEOUT,
                    "PROVIDER_DEADLINE_EXPIRED",
                    "DWAI-ON Home assertion cannot outlive the provider deadline.");
        }
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("v", 1);
        claims.put("kid", keyId);
        claims.put("iss", ISSUER);
        claims.put("aud", AUDIENCE);
        claims.put("sub", Long.toString(context.userId()));
        claims.put("tid", Long.toString(context.tenantId()));
        claims.put("pid", context.personPublicId() == null
                ? null : context.personPublicId().toString());
        claims.put("cid", context.correlationId());
        claims.put("traceparent", context.traceparent());
        claims.put("tracestate", context.tracestate());
        claims.put("ip", "TENANT");
        claims.put("htm", "POST");
        claims.put("htu", PATH);
        claims.put("permissions", context.permissions().stream().sorted().toList());
        claims.put("roles", context.roles().stream().sorted().toList());
        claims.put("groups", context.groupRefs().stream().sorted().toList());
        claims.put("authorityRevision", context.authorityDecisionRevision());
        claims.put("authorityRevalidateAt", context.authorityRevalidateAt().toString());
        claims.put("deadlineAt", deadline.toString());
        claims.put("bodySha256", sha256(exactBody));
        claims.put("iat", issuedAt);
        claims.put("nbf", issuedAt - 1);
        claims.put("exp", expiresAt);
        claims.put("jti", nonce.get().toString());
        try {
            String input = "dwp1." + BASE64.encodeToString(mapper.writeValueAsBytes(claims));
            String assertion = input + "." + BASE64.encodeToString(hmac(input));
            if (assertion.getBytes(StandardCharsets.US_ASCII).length > MAX_ASSERTION_BYTES) {
                throw new IllegalArgumentException("DWAI-ON Home assertion is too large.");
            }
            return assertion;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("DWAI-ON Home assertion serialization failed.", exception);
        }
    }

    private long ceilEpoch(OffsetDateTime value) {
        long seconds = value.toEpochSecond();
        return value.getNano() == 0 ? seconds : seconds + 1;
    }

    private byte[] hmac(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception exception) {
            throw new IllegalStateException("DWAI-ON Home assertion signing failed.", exception);
        }
    }

    private String sha256(byte[] exactBody) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(exactBody));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
