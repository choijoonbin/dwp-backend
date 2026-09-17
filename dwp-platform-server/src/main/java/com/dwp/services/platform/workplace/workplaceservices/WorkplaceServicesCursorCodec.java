package com.dwp.services.platform.workplace.workplaceservices;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

@Component
public class WorkplaceServicesCursorCodec {
    private static final String VERSION = "v1";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final byte[] AAD = "dwp-workplace-services-cursor-v1"
            .getBytes(StandardCharsets.UTF_8);
    private static final Duration TTL = Duration.ofHours(1);

    private final byte[] configuredSecret;
    private final byte[] encryptionKey;
    private final Clock clock;
    private final SecureRandom random;
    private final String environment;

    @Autowired
    public WorkplaceServicesCursorCodec(
            @Value("${dwp.platform.api-history.cursor-secret:}") String secret,
            @Value("${DWP_ENVIRONMENT:local}") String environment) {
        this(secret, Clock.systemUTC(), new SecureRandom(), environment);
    }

    WorkplaceServicesCursorCodec(String secret, Clock clock) {
        this(secret, clock, new SecureRandom(), "test");
    }

    WorkplaceServicesCursorCodec(String secret, Clock clock, SecureRandom random) {
        this(secret, clock, random, "test");
    }

    WorkplaceServicesCursorCodec(
            String secret, Clock clock, SecureRandom random, String environment) {
        this.configuredSecret = secret == null
                ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.encryptionKey = sha256(configuredSecret);
        this.clock = clock;
        this.random = random;
        this.environment = environment == null ? "local" : environment;
    }

    @PostConstruct
    void validateProductionReadiness() {
        String normalized = environment.trim().toLowerCase(Locale.ROOT);
        if (("prod".equals(normalized) || "production".equals(normalized))
                && configuredSecret.length < 32) {
            throw new IllegalStateException(
                    "Production Workplace Services cursor encryption requires a 32-byte secret.");
        }
    }

    public String encode(
            long tenantId, String scope, OffsetDateTime timestamp, UUID id) {
        requireConfigured();
        String payload = String.join("\n", Long.toString(tenantId), scope,
                timestamp.toString(), id.toString(),
                Long.toString(clock.instant().plus(TTL).getEpochSecond()));
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, nonce,
                payload.getBytes(StandardCharsets.UTF_8));
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return VERSION + '.' + encoder.encodeToString(nonce) + '.'
                + encoder.encodeToString(encrypted);
    }

    public CursorPosition decode(String cursor, long tenantId, String expectedScope) {
        requireConfigured();
        if (cursor == null || cursor.isBlank() || cursor.length() > 2_048) {
            throw invalid();
        }
        try {
            String[] token = cursor.split("\\.");
            if (token.length != 3 || !VERSION.equals(token[0])) throw invalid();
            byte[] nonce = Base64.getUrlDecoder().decode(token[1]);
            if (nonce.length != 12) throw invalid();
            String[] payload = new String(crypt(Cipher.DECRYPT_MODE, nonce,
                    Base64.getUrlDecoder().decode(token[2])), StandardCharsets.UTF_8)
                    .split("\\n", -1);
            if (payload.length != 5 || tenantId != Long.parseLong(payload[0])
                    || !expectedScope.equals(payload[1])
                    || Long.parseLong(payload[4]) < clock.instant().getEpochSecond()) {
                throw invalid();
            }
            return new CursorPosition(OffsetDateTime.parse(payload[2]),
                    UUID.fromString(payload[3]));
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private byte[] crypt(int mode, byte[] nonce, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(mode, new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException exception) {
            if (mode == Cipher.DECRYPT_MODE) throw invalid();
            throw new IllegalStateException("Workplace Services cursor encryption failed.", exception);
        }
    }

    private void requireConfigured() {
        if (configuredSecret.length < 32) {
            throw new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Workplace Services cursor encryption is not configured.");
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private static BaseException invalid() {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                "The Workplace Services cursor is invalid or expired.");
    }

    public record CursorPosition(OffsetDateTime timestamp, UUID id) { }
}
