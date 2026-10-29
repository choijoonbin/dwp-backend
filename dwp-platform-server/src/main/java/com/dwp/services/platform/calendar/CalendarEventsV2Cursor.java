package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

@Component
final class CalendarEventsV2Cursor {

    private static final String VERSION = "v1";
    private static final byte[] AAD = "dwp-calendar-events-v2-cursor-v1"
            .getBytes(StandardCharsets.UTF_8);
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final String LOCAL_SECRET =
            "dwp-calendar-events-local-cursor-secret-v1";

    private final byte[] configuredSecret;
    private final byte[] encryptionKey;
    private final byte[] signingKey;
    private final Clock clock;
    private final SecureRandom random;
    private final String environment;

    @Autowired
    CalendarEventsV2Cursor(
            @Value("${dwp.platform.calendar.events.cursor-secret:}") String secret,
            @Value("${DWP_ENVIRONMENT:local}") String environment) {
        this(secret, environment, Clock.systemUTC(), new SecureRandom());
    }

    CalendarEventsV2Cursor(String secret, Clock clock) {
        this(secret, "test", clock, new SecureRandom());
    }

    CalendarEventsV2Cursor(
            String secret, String environment, Clock clock, SecureRandom random) {
        this.environment = environment == null ? "local" : environment;
        this.configuredSecret = bytes(secret);
        byte[] effective = configuredSecret.length == 0 && !production(this.environment)
                ? bytes(LOCAL_SECRET) : configuredSecret;
        this.encryptionKey = digest("encryption", effective);
        this.signingKey = digest("signing", effective);
        this.clock = clock;
        this.random = random;
    }

    @PostConstruct
    void validateProductionReadiness() {
        if (production(environment) && configuredSecret.length < 32) {
            throw new IllegalStateException(
                    "Production Calendar event cursors require a 32-byte secret.");
        }
    }

    String encode(String scopeHash, Position position) {
        String body = String.join("\n",
                scopeHash,
                position.startsAt().toString(),
                position.eventId().toString().toLowerCase(Locale.ROOT),
                position.recurrenceId().toString(),
                Long.toString(clock.instant().plus(TTL).getEpochSecond()));
        String payload = body + "\n" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sign(body));
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        byte[] ciphertext = crypt(
                Cipher.ENCRYPT_MODE, nonce, payload.getBytes(StandardCharsets.UTF_8));
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return VERSION + "." + encoder.encodeToString(nonce) + "."
                + encoder.encodeToString(ciphertext);
    }

    Position decode(String token, String expectedScopeHash) {
        if (token == null || token.isBlank() || token.length() > 4096) throw invalid();
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || !VERSION.equals(parts[0])) throw invalid();
            byte[] nonce = decodeCanonical(parts[1]);
            if (nonce.length != 12) throw invalid();
            String[] fields = new String(crypt(
                    Cipher.DECRYPT_MODE,
                    nonce,
                    decodeCanonical(parts[2])), StandardCharsets.UTF_8)
                    .split("\\n", -1);
            if (fields.length != 6 || !expectedScopeHash.equals(fields[0])) throw invalid();
            String body = String.join("\n", fields[0], fields[1], fields[2], fields[3], fields[4]);
            if (!MessageDigest.isEqual(
                    sign(body), Base64.getUrlDecoder().decode(fields[5]))) throw invalid();
            if (Long.parseLong(fields[4]) < clock.instant().getEpochSecond()) throw invalid();
            return new Position(
                    OffsetDateTime.parse(fields[1]),
                    UUID.fromString(fields[2].toLowerCase(Locale.ROOT)),
                    OffsetDateTime.parse(fields[3]));
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private byte[] crypt(int mode, byte[] nonce, byte[] value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            return cipher.doFinal(value);
        } catch (GeneralSecurityException exception) {
            if (mode == Cipher.DECRYPT_MODE) throw invalid();
            throw new IllegalStateException("Calendar cursor encryption failed.", exception);
        }
    }

    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Calendar cursor signing failed.", exception);
        }
    }

    private static byte[] digest(String purpose, byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(purpose.getBytes(StandardCharsets.UTF_8));
            return digest.digest(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static byte[] bytes(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] decodeCanonical(String value) {
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
            throw invalid();
        }
        return decoded;
    }

    private static boolean production(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "prod".equals(normalized) || "production".equals(normalized);
    }

    private static BaseException invalid() {
        return new BaseException(
                ErrorCode.INVALID_INPUT_VALUE,
                "The Calendar events cursor is invalid or expired.");
    }

    record Position(OffsetDateTime startsAt, UUID eventId, OffsetDateTime recurrenceId) {
    }
}
