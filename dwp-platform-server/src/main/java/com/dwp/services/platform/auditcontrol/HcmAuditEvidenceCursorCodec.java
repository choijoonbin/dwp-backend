package com.dwp.services.platform.auditcontrol;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Component
public class HcmAuditEvidenceCursorCodec {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String VERSION = "hcm-audit-v1";
    private static final String SIGNING_DOMAIN = "dwp:hcm-audit-evidence:hcm-audit-v1\n";
    private static final int MAX_CURSOR_LENGTH = 8_192;

    private final byte[] secret;
    private final Duration ttl;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public HcmAuditEvidenceCursorCodec(
            @Value("${dwp.platform.api-history.cursor-secret:}") String secret,
            @Value("${dwp.platform.hcm-audit.cursor-ttl:PT15M}") Duration ttl,
            ObjectMapper objectMapper) {
        this(secret, ttl, objectMapper, Clock.systemUTC());
    }

    HcmAuditEvidenceCursorCodec(
            String secret, Duration ttl, ObjectMapper objectMapper, Clock clock) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    String encode(
            AccessContext access,
            HcmAuditEvidenceCriteria criteria,
            Instant occurredAt,
            UUID eventId) {
        requireConfigured();
        CursorPayload payload = new CursorPayload(
                access.tenantId(),
                access.routeContractKey(),
                access.contextKey(),
                access.scopeKey(),
                access.subjectPublicId(),
                access.fieldPolicyRevision(),
                access.authorizationRevision(),
                criteria.filterDigest(),
                criteria.snapshotAt(),
                occurredAt,
                eventId,
                clock.instant().plus(ttl).getEpochSecond());
        try {
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(payload));
            String signed = VERSION + "." + encoded;
            String signature = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(sign(signed));
            return signed + "." + signature;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("HCM audit cursor serialization failed.", exception);
        }
    }

    CursorPosition decode(
            String cursor,
            AccessContext access,
            HcmAuditEvidenceCriteria criteria) {
        requireConfigured();
        try {
            if (cursor == null || cursor.length() > MAX_CURSOR_LENGTH) throw invalid();
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 3 || !VERSION.equals(parts[0])) throw invalid();
            String signed = parts[0] + "." + parts[1];
            if (!MessageDigest.isEqual(
                    sign(signed), Base64.getUrlDecoder().decode(parts[2]))) {
                throw invalid();
            }
            CursorPayload payload = objectMapper.readValue(
                    Base64.getUrlDecoder().decode(parts[1]), CursorPayload.class);
            if (!access.tenantId().equals(payload.tenantId())
                    || !access.routeContractKey().equals(payload.routeContractKey())
                    || !access.contextKey().equals(payload.contextKey())
                    || !access.scopeKey().equals(payload.scopeKey())
                    || !access.subjectPublicId().equals(payload.subjectPublicId())
                    || access.fieldPolicyRevision() != payload.fieldPolicyRevision()
                    || access.authorizationRevision() != payload.authorizationRevision()
                    || !criteria.filterDigest().equals(payload.filterDigest())
                    || payload.expiresAtEpochSecond() < clock.instant().getEpochSecond()
                    || payload.occurredAt() == null
                    || payload.eventId() == null
                    || payload.occurredAt().isAfter(payload.snapshotAt())) {
                throw invalid();
            }
            return new CursorPosition(
                    payload.snapshotAt(), payload.occurredAt(), payload.eventId());
        } catch (IllegalArgumentException | IOException exception) {
            throw invalid();
        }
    }

    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal((SIGNING_DOMAIN + value).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HCM audit cursor signing failed.", exception);
        }
    }

    private void requireConfigured() {
        if (secret.length < 24 || ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "HCM audit cursor signing is not configured.");
        }
    }

    private BaseException invalid() {
        return new BaseException(
                ErrorCode.INVALID_INPUT_VALUE,
                "The HCM audit evidence cursor is invalid or expired.");
    }

    record AccessContext(
            Long tenantId,
            String routeContractKey,
            String contextKey,
            String scopeKey,
            UUID subjectPublicId,
            long fieldPolicyRevision,
            long authorizationRevision) {
    }

    record CursorPosition(Instant snapshotAt, Instant occurredAt, UUID eventId) {
    }

    private record CursorPayload(
            Long tenantId,
            String routeContractKey,
            String contextKey,
            String scopeKey,
            UUID subjectPublicId,
            long fieldPolicyRevision,
            long authorizationRevision,
            String filterDigest,
            Instant snapshotAt,
            Instant occurredAt,
            UUID eventId,
            long expiresAtEpochSecond) {
    }
}
