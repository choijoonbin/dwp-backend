package com.dwp.services.notification.cursor;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Component
public class NotificationCursorCodec {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final Duration ttl;

    public NotificationCursorCodec(
            @Value("${dwp.notification.cursor-secret:}") String secret,
            @Value("${dwp.notification.cursor-ttl:24h}") Duration ttl) {
        if (secret == null || secret.length() < 24) {
            throw new IllegalStateException(
                    "dwp.notification.cursor-secret must contain at least 24 characters.");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
    }

    public String encodeInbox(
            NotificationRequestContext.Actor actor,
            Instant lastActivityAt,
            UUID notificationId) {
        return encode(String.join("|",
                "I",
                Long.toString(actor.tenantId()),
                Long.toString(actor.userId()),
                Long.toString(lastActivityAt.toEpochMilli()),
                notificationId.toString(),
                Long.toString(Instant.now().plus(ttl).getEpochSecond())));
    }

    public InboxCursor decodeInbox(NotificationRequestContext.Actor actor, String token) {
        String[] values = decode(token, "I", actor, 6);
        try {
            return new InboxCursor(
                    Instant.ofEpochMilli(Long.parseLong(values[3])), UUID.fromString(values[4]));
        } catch (RuntimeException exception) {
            throw invalidCursor();
        }
    }

    private String[] decode(
            String token,
            String expectedKind,
            NotificationRequestContext.Actor actor,
            int expectedLength) {
        if (token == null || token.isBlank() || token.length() > 1024) throw invalidCursor();
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) throw invalidCursor();
        byte[] expectedSignature = sign(parts[0]);
        byte[] actualSignature;
        byte[] payload;
        try {
            actualSignature = Base64.getUrlDecoder().decode(parts[1]);
            payload = Base64.getUrlDecoder().decode(parts[0]);
        } catch (IllegalArgumentException exception) {
            throw invalidCursor();
        }
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) throw invalidCursor();
        String[] values = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
        if (values.length != expectedLength || !expectedKind.equals(values[0])) throw invalidCursor();
        try {
            if (Long.parseLong(values[1]) != actor.tenantId()
                    || Long.parseLong(values[2]) != actor.userId()
                    || Long.parseLong(values[expectedLength - 1]) < Instant.now().getEpochSecond()) {
                throw invalidCursor();
            }
        } catch (NumberFormatException exception) {
            throw invalidCursor();
        }
        return values;
    }

    private String encode(String payload) {
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String encodedSignature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sign(encodedPayload));
        return encodedPayload + "." + encodedSignature;
    }

    private byte[] sign(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to sign notification cursor.", exception);
        }
    }

    private NotificationException invalidCursor() {
        return new NotificationException(NotificationErrorCode.NOTIFICATION_INVALID_CURSOR);
    }

    public record InboxCursor(Instant lastActivityAt, UUID notificationId) {
    }
}
