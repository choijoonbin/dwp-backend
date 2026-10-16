package com.dwp.services.time.home;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Compact tenant- and digest-bound stateless reference authenticated with HMAC-SHA-256. */
final class TimeHomePayloadReferenceCodec {

    private static final String PREFIX = "href_";
    private static final String ALGORITHM = "HmacSHA256";
    private static final byte VERSION = 1;
    private static final int DIGEST_BYTES = 32;
    private static final int PAYLOAD_BYTES = 1 + Long.BYTES + Long.BYTES + DIGEST_BYTES;
    private static final int SIGNATURE_BYTES = 32;
    private static final int TOKEN_BYTES = PAYLOAD_BYTES + SIGNATURE_BYTES;

    private final byte[] secret;

    TimeHomePayloadReferenceCodec(byte[] secret) {
        Objects.requireNonNull(secret, "secret");
        if (secret.length < 32) {
            throw new IllegalArgumentException(
                    "Time Home payload-reference HMAC secret must contain at least 32 bytes");
        }
        this.secret = secret.clone();
    }

    String issue(long tenantId, Instant issuedAt, String payloadDigest) {
        if (tenantId < 1 || issuedAt == null || issuedAt.getNano() != 0
                || payloadDigest == null || !payloadDigest.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("Time Home payload reference input is invalid");
        }
        byte[] digest = HexFormat.of().parseHex(payloadDigest);
        ByteBuffer payload = ByteBuffer.allocate(PAYLOAD_BYTES);
        payload.put(VERSION);
        payload.putLong(tenantId);
        payload.putLong(issuedAt.getEpochSecond());
        payload.put(digest);
        byte[] body = payload.array();
        ByteBuffer token = ByteBuffer.allocate(TOKEN_BYTES);
        token.put(body);
        token.put(sign(body));
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(token.array());
    }

    DecodedReference decode(String reference) {
        if (reference == null || !reference.matches("^href_[A-Za-z0-9_-]{16,128}$")) {
            throw invalid();
        }
        byte[] token;
        try {
            token = Base64.getUrlDecoder().decode(reference.substring(PREFIX.length()));
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
        if (token.length != TOKEN_BYTES) {
            throw invalid();
        }
        byte[] body = java.util.Arrays.copyOfRange(token, 0, PAYLOAD_BYTES);
        byte[] supplied = java.util.Arrays.copyOfRange(token, PAYLOAD_BYTES, TOKEN_BYTES);
        if (!MessageDigest.isEqual(sign(body), supplied)) {
            throw invalid();
        }
        ByteBuffer payload = ByteBuffer.wrap(body);
        if (payload.get() != VERSION) {
            throw invalid();
        }
        long tenantId = payload.getLong();
        long issuedAt = payload.getLong();
        byte[] digest = new byte[DIGEST_BYTES];
        payload.get(digest);
        if (tenantId < 1 || issuedAt < 1) {
            throw invalid();
        }
        return new DecodedReference(
                tenantId, Instant.ofEpochSecond(issuedAt), HexFormat.of().formatHex(digest));
    }

    private byte[] sign(byte[] value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(value);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", failure);
        }
    }

    record DecodedReference(long tenantId, Instant issuedAt, String payloadDigest) {
    }

    static final class InvalidPayloadReferenceException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InvalidPayloadReferenceException() {
            super("Time Home payload reference was not found");
        }
    }

    private static InvalidPayloadReferenceException invalid() {
        return new InvalidPayloadReferenceException();
    }
}
