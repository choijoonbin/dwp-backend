package com.dwp.services.people.hris.performance.home;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;

/** Stateless opaque reference bound to tenant, current authority and payload digest. */
@Component
@ConditionalOnProperty(prefix = "dwp.people.hris.performance-home", name = "enabled",
        havingValue = "true")
final class PerformanceHomeReferenceCodec {

    private static final byte VERSION = 1;
    private static final int PREFIX_SIZE = 49;
    private static final int TOKEN_SIZE = PREFIX_SIZE + 32;
    private static final String HMAC = "HmacSHA256";

    private final byte[] secret;
    private final Clock clock;

    PerformanceHomeReferenceCodec(
            @Value("${dwp.people.hris.performance-home.hmac-secret-base64:}") String secret) {
        this(decodeSecret(secret), Clock.systemUTC());
    }

    PerformanceHomeReferenceCodec(byte[] secret, Clock clock) {
        if (secret == null || secret.length < 32) {
            throw new IllegalArgumentException(
                    "Performance Home reference secret must contain at least 256 bits.");
        }
        this.secret = secret.clone();
        this.clock = clock;
    }

    String issue(
            PerformanceHomeAuthorityPort.AuthorityDecision authority,
            String payloadDigest,
            Instant expiresAt) {
        requireDigest(payloadDigest);
        if (expiresAt == null || !expiresAt.isAfter(clock.instant())) {
            throw new IllegalArgumentException("Performance Home reference expiry must be future.");
        }
        byte[] prefix = ByteBuffer.allocate(PREFIX_SIZE)
                .put(VERSION)
                .putLong(authority.tenantId())
                .putLong(expiresAt.getEpochSecond())
                .put(first(contextDigest(authority), 16))
                .put(first(hex(payloadDigest), 16))
                .array();
        byte[] token = ByteBuffer.allocate(TOKEN_SIZE).put(prefix).put(mac(prefix)).array();
        return "href_" + Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    void verify(
            String payloadRef,
            PerformanceHomeAuthorityPort.AuthorityDecision authority,
            String payloadDigest) {
        requireDigest(payloadDigest);
        byte[] token = decodeReference(payloadRef);
        byte[] prefix = new byte[PREFIX_SIZE];
        byte[] providedTag = new byte[32];
        ByteBuffer.wrap(token).get(prefix).get(providedTag);
        if (!MessageDigest.isEqual(mac(prefix), providedTag)) {
            throw failure(Reason.UNKNOWN_REFERENCE);
        }
        ByteBuffer values = ByteBuffer.wrap(prefix);
        if (values.get() != VERSION) throw failure(Reason.UNKNOWN_REFERENCE);
        long tenantId = values.getLong();
        long expiresAt = values.getLong();
        byte[] context = new byte[16];
        byte[] digest = new byte[16];
        values.get(context).get(digest);
        if (tenantId != authority.tenantId()) throw failure(Reason.TENANT_MISMATCH);
        if (!MessageDigest.isEqual(context, first(contextDigest(authority), 16))) {
            throw failure(Reason.AUTHORITY_CHANGED);
        }
        if (!MessageDigest.isEqual(digest, first(hex(payloadDigest), 16))) {
            throw failure(Reason.PAYLOAD_DIGEST_MISMATCH);
        }
        if (!Instant.ofEpochSecond(expiresAt).isAfter(clock.instant())) {
            throw failure(Reason.STALE_REFERENCE);
        }
    }

    static String contextDigest(PerformanceHomeAuthorityPort.AuthorityDecision authority) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, Long.toString(authority.tenantId()));
            update(digest, authority.subjectPrincipalPublicId().toString());
            update(digest, authority.capability());
            update(digest, authority.browserAudience().name());
            update(digest, authority.ownerPopulationScope().name());
            update(digest, Long.toString(authority.scopeRevision()));
            update(digest, authority.populationScopeDigest());
            update(digest, Long.toString(authority.effectivePermissionRevision()));
            update(digest, Long.toString(authority.fieldPolicyRevision()));
            update(digest, authority.widgetKey());
            authority.fieldDecisions().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> update(digest,
                            entry.getKey() + '=' + entry.getValue().name()));
            authority.authorizedParticipantIds().stream()
                    .sorted(Comparator.comparing(Object::toString))
                    .forEach(value -> update(digest, value.toString()));
            return HexFormat.of().formatHex(digest.digest());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private byte[] decodeReference(String payloadRef) {
        if (payloadRef == null || !payloadRef.matches("^href_[A-Za-z0-9_-]{16,128}$")) {
            throw failure(Reason.UNKNOWN_REFERENCE);
        }
        String encoded = payloadRef.substring(5);
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            if (decoded.length != TOKEN_SIZE
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded)
                    .equals(encoded)) {
                throw failure(Reason.UNKNOWN_REFERENCE);
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw failure(Reason.UNKNOWN_REFERENCE);
        }
    }

    private byte[] mac(byte[] value) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return mac.doFinal(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable.", exception);
        }
    }

    private static byte[] decodeSecret(String encoded) {
        try {
            if (encoded == null || encoded.isBlank() || encoded.indexOf('=') >= 0) {
                throw new IllegalArgumentException("missing secret");
            }
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encoded)) {
                throw new IllegalArgumentException("non-canonical secret");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Performance Home reference secret must be canonical base64url.", exception);
        }
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }

    private static byte[] first(String digest, int length) {
        return first(hex(digest), length);
    }

    private static byte[] first(byte[] value, int length) {
        byte[] result = new byte[length];
        System.arraycopy(value, 0, result, 0, length);
        return result;
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static void requireDigest(String value) {
        if (value == null || !value.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("Canonical payload digest is required.");
        }
    }

    enum Reason {
        UNKNOWN_REFERENCE,
        TENANT_MISMATCH,
        AUTHORITY_CHANGED,
        PAYLOAD_DIGEST_MISMATCH,
        STALE_REFERENCE
    }

    static final class ReferenceFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Reason reason;

        private ReferenceFailure(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        Reason reason() {
            return reason;
        }
    }

    private static ReferenceFailure failure(Reason reason) {
        return new ReferenceFailure(reason);
    }
}
