package com.dwp.services.people.hris.workbench.hrm011;

import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractException;
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

/** HMAC cursor bound to tenant, population, revisions, filters, and ordering. */
@Component
@ConditionalOnProperty(prefix = "dwp.hris.contract-compensation-workbench",
        name = "enabled", havingValue = "true", matchIfMissing = false)
public final class Hrm011CursorCodec {
    private static final byte VERSION = 1;
    private static final int SIGNATURE = 32;
    private static final byte[] DOMAIN =
            "dwp.hris.hrm011.iaq008.cursor.v1\n".getBytes(StandardCharsets.UTF_8);

    private final byte[] secret;
    private final Duration ttl;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public Hrm011CursorCodec(
            @Value("${dwp.hris.contract-compensation-workbench.cursor-secret:${dwp.people.cursor-secret:}}")
            String secret,
            @Value("${dwp.hris.contract-compensation-workbench.cursor-ttl:PT15M}")
            Duration ttl,
            ObjectMapper mapper) {
        this(secret, ttl, mapper, Clock.systemUTC());
    }

    Hrm011CursorCodec(String secret, Duration ttl, ObjectMapper mapper, Clock clock) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = Objects.requireNonNull(ttl);
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("IAQ-008 cursor TTL must be in (0,PT24H].");
        }
    }

    public String issue(Binding binding) {
        requireConfigured();
        requireBinding(binding, true);
        try {
            byte[] payload = mapper.writeValueAsBytes(
                    new Payload(binding, clock.instant().plus(ttl).getEpochSecond()));
            byte[] signature = sign(payload);
            ByteBuffer envelope = ByteBuffer.allocate(1 + Integer.BYTES
                    + payload.length + signature.length);
            envelope.put(VERSION).putInt(payload.length).put(payload).put(signature);
            String cursor = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(envelope.array());
            if (!cursor.matches("[A-Za-z0-9_-]{16,1024}")) throw denied();
            return cursor;
        } catch (EmploymentContractException exception) {
            throw exception;
        } catch (Exception exception) {
            throw EmploymentContractException.unavailable("IAQ-008 cursor issuance failed.");
        }
    }

    public Binding verify(String cursor, Binding expected) {
        requireConfigured();
        requireBinding(expected, false);
        try {
            if (cursor == null || !cursor.matches("[A-Za-z0-9_-]{16,1024}")) throw denied();
            ByteBuffer envelope = ByteBuffer.wrap(Base64.getUrlDecoder().decode(cursor));
            if (envelope.remaining() <= 1 + Integer.BYTES + SIGNATURE
                    || envelope.get() != VERSION) throw denied();
            int length = envelope.getInt();
            if (length <= 0 || length != envelope.remaining() - SIGNATURE) throw denied();
            byte[] payload = new byte[length];
            byte[] signature = new byte[SIGNATURE];
            envelope.get(payload).get(signature);
            if (!MessageDigest.isEqual(sign(payload), signature)) throw denied();
            Payload decoded = mapper.readValue(payload, Payload.class);
            requireBinding(decoded.binding(), true);
            if (decoded.expiresAtEpochSecond() <= clock.instant().getEpochSecond()
                    || !matches(expected, decoded.binding())) throw denied();
            return decoded.binding();
        } catch (EmploymentContractException exception) {
            throw exception;
        } catch (Exception exception) {
            throw denied();
        }
    }

    private boolean matches(Binding expected, Binding actual) {
        return expected.tenantId() == actual.tenantId()
                && expected.limit() == actual.limit()
                && Objects.equals(expected.scopeRevision(), actual.scopeRevision())
                && Objects.equals(expected.authorizationRevision(), actual.authorizationRevision())
                && Objects.equals(expected.populationRevision(), actual.populationRevision())
                && (expected.asOf() == null || Objects.equals(expected.asOf(), actual.asOf()))
                && Objects.equals(expected.sort(), actual.sort())
                && Objects.equals(expected.filterDigest(), actual.filterDigest())
                && Objects.equals(expected.locale(), actual.locale())
                && Objects.equals(expected.timeZone(), actual.timeZone())
                && (expected.lastItemPublicId() == null
                || Objects.equals(expected.lastItemPublicId(), actual.lastItemPublicId()));
    }

    private void requireBinding(Binding value, boolean complete) {
        if (value == null || value.tenantId() < 1 || value.limit() < 1 || value.limit() > 100
                || blank(value.scopeRevision()) || blank(value.authorizationRevision())
                || blank(value.populationRevision()) || blank(value.filterDigest())
                || blank(value.locale()) || blank(value.timeZone())
                || value.sort() == null || value.sort().isEmpty() || value.sort().size() > 3
                || complete && value.asOf() == null
                || complete && blank(value.lastItemPublicId())) {
            throw denied();
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank() || value.length() > 512;
    }

    private byte[] sign(byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        mac.update(DOMAIN);
        return mac.doFinal(payload);
    }

    private void requireConfigured() {
        if (secret.length < 24) {
            throw EmploymentContractException.unavailable(
                    "IAQ-008 cursor authority is not configured.");
        }
    }

    private EmploymentContractException denied() {
        return EmploymentContractException.denied(
                "The IAQ-008 cursor is invalid, stale, or outside this scope.");
    }

    public record Binding(
            long tenantId,
            String scopeRevision,
            String authorizationRevision,
            String populationRevision,
            Instant asOf,
            List<String> sort,
            int limit,
            String lastItemPublicId,
            String filterDigest,
            String locale,
            String timeZone) {
        public Binding {
            sort = sort == null ? null : List.copyOf(sort);
        }

        public Binding withLastItemPublicId(String value) {
            return new Binding(tenantId, scopeRevision, authorizationRevision,
                    populationRevision, asOf, sort, limit, value, filterDigest,
                    locale, timeZone);
        }
    }

    private record Payload(Binding binding, long expiresAtEpochSecond) {
    }
}
