package com.dwp.observability.api;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/** Produces tenant-safe correlation hashes without retaining raw network identifiers. */
public final class ApiHistoryPrivacyHasher {

    private final byte[] secret;

    public ApiHistoryPrivacyHasher(String secret) {
        this.secret = secret == null || secret.isBlank()
                ? null
                : secret.getBytes(StandardCharsets.UTF_8);
    }

    public String hash(String value) {
        if (secret == null || value == null || value.isBlank()) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is not available.", exception);
        }
    }
}
