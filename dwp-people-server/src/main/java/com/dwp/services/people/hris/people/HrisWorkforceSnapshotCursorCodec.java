package com.dwp.services.people.hris.people;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hris.contracts.workforce.v1.VerifiedWorkforceSnapshotCursor;
import com.dwp.services.people.hris.contracts.workforce.v1.VerifiedWorkforceSnapshotRequest;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotProjection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/** Authenticated, opaque cursor codec owned by HRM. */
@Component
final class HrisWorkforceSnapshotCursorCodec {

    private static final String VERSION = "1";
    private static final String ALGORITHM = "HmacSHA256";
    private final byte[] secret;

    HrisWorkforceSnapshotCursorCodec(
            @Value("${dwp.people.workforce-snapshot.cursor-secret:}") String configuredSecret) {
        secret = configuredSecret == null
                ? new byte[0] : configuredSecret.getBytes(StandardCharsets.UTF_8);
    }

    String issue(
            VerifiedWorkforceSnapshotRequest request,
            long ownerRevision,
            String position) {
        requireConfigured();
        String payload = String.join("\n",
                VERSION,
                Long.toString(request.tenantId()),
                request.callerModule(),
                request.purposeCode(),
                request.projection().name(),
                request.asOf().toString(),
                Long.toString(ownerRevision),
                position);
        byte[] encoded = payload.getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encoded)
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(encoded));
    }

    VerifiedWorkforceSnapshotCursor verify(String token) {
        requireConfigured();
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2) throw invalidCursor();
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            if (!java.security.MessageDigest.isEqual(mac(payload), signature)) {
                throw invalidCursor();
            }
            String[] fields = new String(payload, StandardCharsets.UTF_8).split("\n", -1);
            if (fields.length != 8 || !VERSION.equals(fields[0])) throw invalidCursor();
            String digest = digest(token);
            return new VerifiedWorkforceSnapshotCursor(
                    Long.parseLong(fields[1]), fields[2], fields[3],
                    WorkforceSnapshotProjection.valueOf(fields[4]),
                    Instant.parse(fields[5]), Long.parseLong(fields[6]), fields[7], digest);
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidCursor();
        }
    }

    private byte[] mac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable.", exception);
        }
    }

    private String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private void requireConfigured() {
        if (secret.length < 32) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "The HRM workforce snapshot cursor authority is not configured.");
        }
    }

    private BaseException invalidCursor() {
        return new BaseException(
                ErrorCode.FORBIDDEN,
                "The HRM workforce snapshot cursor is invalid or no longer authoritative.");
    }
}
