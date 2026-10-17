package com.dwp.services.people.hris.workbench.hrm007;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/** HMAC cursor carrying every sealed IA-query scope and revision binding. */
@Component
@ConditionalOnProperty(prefix = Hrm007RolloutGate.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = false)
final class Hrm007CursorCodec {

    private static final String ALGORITHM = "HmacSHA256";
    private static final byte VERSION = 1;
    private static final int SIGNATURE_LENGTH = 32;
    private static final byte[] DOMAIN =
            "dwp.hris.hrm007.entry-query.cursor.v1\n".getBytes(StandardCharsets.UTF_8);

    private final byte[] secret;
    private final Duration ttl;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    Hrm007CursorCodec(
            @Value("${dwp.hris.hrm007.cursor-secret:${dwp.people.cursor-secret:}}")
            String secret,
            @Value("${dwp.hris.hrm007.cursor-ttl:PT15M}") Duration ttl,
            ObjectMapper mapper) {
        this(secret, ttl, mapper, Clock.systemUTC());
    }

    Hrm007CursorCodec(String secret, Duration ttl, ObjectMapper mapper, Clock clock) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = Objects.requireNonNull(ttl);
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("HRM-007 cursor TTL must be in (0, PT24H].");
        }
    }

    String issue(Binding binding) {
        requireConfigured();
        requireBinding(binding, true);
        try {
            byte[] payload = mapper.writeValueAsBytes(new Payload(
                    binding, clock.instant().plus(ttl).getEpochSecond()));
            byte[] signature = sign(payload);
            ByteBuffer envelope = ByteBuffer.allocate(
                    1 + Integer.BYTES + payload.length + signature.length);
            envelope.put(VERSION).putInt(payload.length).put(payload).put(signature);
            String cursor = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(envelope.array());
            if (cursor.length() < 16 || cursor.length() > 1024) {
                throw Hrm007Exception.unavailable("HRM-007 cursor envelope is too large.");
            }
            return cursor;
        } catch (JsonProcessingException exception) {
            throw Hrm007Exception.unavailable("HRM-007 cursor serialization failed.");
        }
    }

    String verify(String cursor, Binding expected) {
        return verifyBinding(cursor, expected).lastItemPublicId();
    }

    Binding verifyBinding(String cursor, Binding expected) {
        requireConfigured();
        requireBinding(expected, false);
        try {
            if (cursor == null || !cursor.matches("[A-Za-z0-9_-]{16,1024}")) {
                throw denied();
            }
            byte[] envelope = Base64.getUrlDecoder().decode(cursor);
            if (envelope.length <= 1 + Integer.BYTES + SIGNATURE_LENGTH) throw denied();
            ByteBuffer buffer = ByteBuffer.wrap(envelope);
            if (buffer.get() != VERSION) throw denied();
            int payloadLength = buffer.getInt();
            if (payloadLength <= 0
                    || payloadLength != buffer.remaining() - SIGNATURE_LENGTH) {
                throw denied();
            }
            byte[] payloadBytes = new byte[payloadLength];
            byte[] signature = new byte[SIGNATURE_LENGTH];
            buffer.get(payloadBytes).get(signature);
            if (!MessageDigest.isEqual(sign(payloadBytes), signature)) throw denied();
            Payload payload = mapper.readValue(payloadBytes, Payload.class);
            requireBinding(payload.binding(), true);
            if (payload.expiresAtEpochSecond() <= clock.instant().getEpochSecond()
                    || !matches(expected, payload.binding())) {
                throw denied();
            }
            return payload.binding();
        } catch (Hrm007Exception exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw denied();
        }
    }

    static String digest(String source) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256")
                            .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private boolean matches(Binding expected, Binding actual) {
        return expected.tenantId() == actual.tenantId()
                && expected.pageLimit() == actual.pageLimit()
                && Objects.equals(expected.queryId(), actual.queryId())
                && Objects.equals(expected.targetPopulation(), actual.targetPopulation())
                && Objects.equals(expected.scopeDecisionRevision(),
                actual.scopeDecisionRevision())
                && Objects.equals(expected.authorizationDecisionRevision(),
                actual.authorizationDecisionRevision())
                && (expected.asOf() == null || Objects.equals(expected.asOf(), actual.asOf()))
                && Objects.equals(expected.sort(), actual.sort())
                && Objects.equals(expected.filterDigest(), actual.filterDigest())
                && Objects.equals(expected.locale(), actual.locale())
                && Objects.equals(expected.timeZone(), actual.timeZone())
                && (expected.lastItemPublicId() == null
                || Objects.equals(expected.lastItemPublicId(), actual.lastItemPublicId()));
    }

    private void requireBinding(Binding binding, boolean requireLastItem) {
        if (binding == null || binding.tenantId() < 1
                || binding.queryId() == null
                || !binding.queryId().matches("IAQ-[0-9]{3}")
                || blank(binding.targetPopulation())
                || blank(binding.scopeDecisionRevision())
                || blank(binding.authorizationDecisionRevision())
                || requireLastItem && binding.asOf() == null
                || binding.sort() == null || binding.sort().isEmpty()
                || binding.sort().size() > 4
                || binding.pageLimit() < 1 || binding.pageLimit() > 100
                || blank(binding.filterDigest())
                || blank(binding.locale()) || blank(binding.timeZone())
                || (requireLastItem && blank(binding.lastItemPublicId()))
                || binding.lastItemPublicId() != null
                && !binding.lastItemPublicId().matches("[A-Za-z0-9._:-]{1,128}")) {
            throw denied();
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank() || value.length() > 512;
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            mac.update(DOMAIN);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HRM-007 cursor signing failed.", exception);
        }
    }

    private void requireConfigured() {
        if (secret.length < 24) {
            throw Hrm007Exception.unavailable(
                    "HRM-007 cursor authority is not configured.");
        }
    }

    private Hrm007Exception denied() {
        return Hrm007Exception.denied(
                "The HRM-007 cursor is invalid, stale, or outside this scope.");
    }

    record Binding(
            long tenantId,
            String queryId,
            String targetPopulation,
            String scopeDecisionRevision,
            String authorizationDecisionRevision,
            Instant asOf,
            List<String> sort,
            int pageLimit,
            String lastItemPublicId,
            String filterDigest,
            String locale,
            String timeZone) {

        Binding {
            sort = sort == null ? null : List.copyOf(sort);
        }

        Binding withLastItemPublicId(String value) {
            return new Binding(
                    tenantId, queryId, targetPopulation, scopeDecisionRevision,
                    authorizationDecisionRevision, asOf, sort, pageLimit, value,
                    filterDigest, locale, timeZone);
        }
    }

    private record Payload(Binding binding, long expiresAtEpochSecond) {
    }
}
