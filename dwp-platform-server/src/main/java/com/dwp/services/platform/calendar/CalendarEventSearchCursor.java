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
final class CalendarEventSearchCursor {

    private static final String VERSION = "s1";
    private static final byte[] AAD = "dwp-calendar-event-search-cursor-v1"
            .getBytes(StandardCharsets.UTF_8);
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final String LOCAL_SECRET =
            "dwp-calendar-event-search-local-secret-v1";

    private final byte[] configuredSecret;
    private final byte[] encryptionKey;
    private final byte[] signingKey;
    private final Clock clock;
    private final SecureRandom random;
    private final String environment;

    @Autowired
    CalendarEventSearchCursor(
            @Value("${dwp.platform.calendar.events.cursor-secret:}") String secret,
            @Value("${DWP_ENVIRONMENT:local}") String environment) {
        this(secret, environment, Clock.systemUTC(), new SecureRandom());
    }

    CalendarEventSearchCursor(String secret, Clock clock) {
        this(secret, "test", clock, new SecureRandom());
    }

    CalendarEventSearchCursor(
            String secret, String environment, Clock clock, SecureRandom random) {
        this.environment = environment == null ? "local" : environment;
        this.configuredSecret = bytes(secret);
        byte[] effective = configuredSecret.length == 0 && !production(this.environment)
                ? bytes(LOCAL_SECRET) : configuredSecret;
        this.encryptionKey = digest("search-encryption", effective);
        this.signingKey = digest("search-signing", effective);
        this.clock = clock;
        this.random = random;
    }

    @PostConstruct
    void validateProductionReadiness() {
        if (production(environment) && configuredSecret.length < 32) {
            throw new IllegalStateException(
                    "Production Calendar event search cursors require a 32-byte secret.");
        }
    }

    String encode(String scopeHash, Position position) {
        String body = String.join("\n",
                scopeHash,
                Integer.toString(position.relevance()),
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
            if (fields.length != 7 || !expectedScopeHash.equals(fields[0])) throw invalid();
            String body = String.join("\n",
                    fields[0], fields[1], fields[2], fields[3], fields[4], fields[5]);
            if (!MessageDigest.isEqual(
                    sign(body), Base64.getUrlDecoder().decode(fields[6]))) throw invalid();
            if (Long.parseLong(fields[5]) < clock.instant().getEpochSecond()) throw invalid();
            int relevance = Integer.parseInt(fields[1]);
            if (relevance < 1 || relevance > 600) throw invalid();
            return new Position(
                    relevance,
                    OffsetDateTime.parse(fields[2]),
                    UUID.fromString(fields[3].toLowerCase(Locale.ROOT)),
                    OffsetDateTime.parse(fields[4]));
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
            throw new IllegalStateException("Calendar search cursor encryption failed.", exception);
        }
    }

    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Calendar search cursor signing failed.", exception);
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
                "The Calendar event search cursor is invalid or expired.");
    }

    record Position(
            int relevance,
            OffsetDateTime startsAt,
            UUID eventId,
            OffsetDateTime recurrenceId) {
    }
}
