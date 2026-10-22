package com.dwp.services.people.hris.workbench.hrm012;

import com.dwp.services.people.hris.contracts.hrm012.Hrm012CanonicalJson;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/** Opaque HMAC cursor bound to every IAQ-012 authority and query dimension. */
@Component
@ConditionalOnProperty(prefix = "dwp.hris.data-quality-workbench",
        name = "enabled", havingValue = "true", matchIfMissing = false)
public final class Hrm012CursorCodec {
    private static final byte VERSION = 1;
    private static final int SIGNATURE_SIZE = 32;
    private static final byte[] DOMAIN =
            "dwp.hris.hrm012.iaq012.cursor.v1\n".getBytes(StandardCharsets.UTF_8);

    private final byte[] secret;
    private final Duration ttl;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public Hrm012CursorCodec(
            @Value("${dwp.hris.data-quality-workbench.cursor-secret:${dwp.people.cursor-secret:}}")
            String secret,
            @Value("${dwp.hris.data-quality-workbench.cursor-ttl:PT15M}") Duration ttl,
            ObjectMapper mapper) {
        this(secret, ttl, mapper, Clock.systemUTC());
    }

    Hrm012CursorCodec(String secret, Duration ttl, ObjectMapper mapper, Clock clock) {
        this.secret = secret == null ? new byte[0]
                : secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = Objects.requireNonNull(ttl);
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("IAQ-012 cursor TTL must be in (0,PT24H].");
        }
    }

    public String issue(Binding binding) {
        requireConfigured();
        requireBinding(binding, true);
        try {
            byte[] payload = mapper.writeValueAsBytes(
                    new Payload(binding, clock.instant().plus(ttl).getEpochSecond()));
            byte[] signature = sign(payload);
            ByteBuffer envelope = ByteBuffer.allocate(
                    1 + Integer.BYTES + payload.length + signature.length);
            envelope.put(VERSION).putInt(payload.length).put(payload).put(signature);
            String value = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(envelope.array());
            if (!value.matches("[A-Za-z0-9_-]{16,1024}")) throw denied();
            return value;
        } catch (Hrm012Problem problem) {
            throw problem;
        } catch (RuntimeException exception) {
            throw Hrm012Problem.unavailable("IAQ-012 cursor issuance failed.");
        } catch (Exception exception) {
            throw Hrm012Problem.unavailable("IAQ-012 cursor issuance failed.");
        }
    }

    public Binding verify(String cursor, Binding expected) {
        requireConfigured();
        requireBinding(expected, false);
        try {
            if (cursor == null || !cursor.matches("[A-Za-z0-9_-]{16,1024}")) {
                throw denied();
            }
            ByteBuffer envelope = ByteBuffer.wrap(Base64.getUrlDecoder().decode(cursor));
            if (envelope.remaining() <= 1 + Integer.BYTES + SIGNATURE_SIZE
                    || envelope.get() != VERSION) throw denied();
            int length = envelope.getInt();
            if (length <= 0 || length != envelope.remaining() - SIGNATURE_SIZE) {
                throw denied();
            }
            byte[] payload = new byte[length];
            byte[] signature = new byte[SIGNATURE_SIZE];
            envelope.get(payload).get(signature);
            if (!MessageDigest.isEqual(sign(payload), signature)) throw denied();
            Payload decoded = mapper.readValue(payload, Payload.class);
            requireBinding(decoded.binding(), true);
            if (decoded.expiresAtEpochSecond() <= clock.instant().getEpochSecond()
                    || !matches(expected, decoded.binding())) throw denied();
            return decoded.binding();
        } catch (Hrm012Problem problem) {
            throw problem;
        } catch (Exception exception) {
            throw denied();
        }
    }

    private boolean matches(Binding expected, Binding actual) {
        return expected.tenantId() == actual.tenantId()
                && expected.limit() == actual.limit()
                && Objects.equals(expected.scopeRevision(), actual.scopeRevision())
                && Objects.equals(expected.authorizationRevision(),
                actual.authorizationRevision())
                && Objects.equals(expected.populationRevision(), actual.populationRevision())
                && Objects.equals(expected.fieldPolicyRevision(),
                actual.fieldPolicyRevision())
                && (expected.asOf() == null || Objects.equals(expected.asOf(), actual.asOf()))
                && Objects.equals(expected.sort(), actual.sort())
                && Objects.equals(expected.filterDigest(), actual.filterDigest())
                && Objects.equals(expected.locale(), actual.locale())
                && Objects.equals(expected.timeZone(), actual.timeZone())
                && (expected.lastRunPublicId() == null
                || Objects.equals(expected.lastRunPublicId(), actual.lastRunPublicId()));
    }

    private void requireBinding(Binding value, boolean complete) {
        if (value == null || value.tenantId() < 1 || value.limit() < 1
                || value.limit() > 100
                || !digest(value.scopeRevision())
                || !digest(value.authorizationRevision())
                || !digest(value.populationRevision())
                || !digest(value.fieldPolicyRevision())
                || !digest(value.filterDigest()) || blank(value.locale())
                || blank(value.timeZone()) || value.sort() == null
                || value.sort().isEmpty() || value.sort().size() > 3
                || complete && value.asOf() == null
                || complete && blank(value.lastRunPublicId())) {
            throw denied();
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank() || value.length() > 512;
    }

    private boolean digest(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    private static String authorityDigest(String value) {
        if (value == null || value.isBlank()) return value;
        if (value.matches("[a-f0-9]{64}")) return value;
        return Hrm012CanonicalJson.sha256(
                "DWP_HRM012_IAQ_CURSOR_AUTHORITY_V1\n" + value);
    }

    private byte[] sign(byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        mac.update(DOMAIN);
        return mac.doFinal(payload);
    }

    private void requireConfigured() {
        if (secret.length < 24) {
            throw Hrm012Problem.unavailable("IAQ-012 cursor authority is not configured.");
        }
    }

    private Hrm012Problem denied() {
        return Hrm012Problem.denied(
                "The IAQ-012 cursor is invalid, stale, or outside this scope.");
    }

    public record Binding(
            long tenantId,
            String scopeRevision,
            String authorizationRevision,
            String populationRevision,
            String fieldPolicyRevision,
            Instant asOf,
            List<String> sort,
            int limit,
            String lastRunPublicId,
            String filterDigest,
            String locale,
            String timeZone) {
        public Binding {
            scopeRevision = authorityDigest(scopeRevision);
            authorizationRevision = authorityDigest(authorizationRevision);
            populationRevision = authorityDigest(populationRevision);
            fieldPolicyRevision = authorityDigest(fieldPolicyRevision);
            sort = sort == null ? null : List.copyOf(sort);
        }

        public Binding withLastRunPublicId(String value) {
            return new Binding(tenantId, scopeRevision, authorizationRevision,
                    populationRevision, fieldPolicyRevision, asOf, sort, limit,
                    value, filterDigest, locale, timeZone);
        }
    }

    private record Payload(Binding binding, long expiresAtEpochSecond) {
    }
}
