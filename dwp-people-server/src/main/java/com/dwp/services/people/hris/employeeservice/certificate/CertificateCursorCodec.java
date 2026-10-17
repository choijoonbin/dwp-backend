package com.dwp.services.people.hris.employeeservice.certificate;

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

/** HMAC cursor carrying every sealed query, scope, revision, and request binding. */
@Component
@ConditionalOnProperty(prefix = Hrm009RolloutGate.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = false)
final class CertificateCursorCodec {

    private static final String ALGORITHM = "HmacSHA256";
    private static final byte VERSION = 1;
    private static final int SIGNATURE_LENGTH = 32;
    private static final byte[] DOMAIN =
            "dwp.hris.hrm009.entry-query.cursor.v1\n"
                    .getBytes(StandardCharsets.UTF_8);

    private final byte[] secret;
    private final Duration ttl;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    CertificateCursorCodec(
            @Value("${dwp.hris.certificate-workbench.cursor-secret:${dwp.people.cursor-secret:}}")
            String secret,
            @Value("${dwp.hris.certificate-workbench.cursor-ttl:PT15M}") Duration ttl,
            ObjectMapper mapper) {
        this(secret, ttl, mapper, Clock.systemUTC());
    }

    CertificateCursorCodec(
            String secret,
            Duration ttl,
            ObjectMapper mapper,
            Clock clock) {
        this.secret = secret == null ? new byte[0]
                : secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = Objects.requireNonNull(ttl);
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
        if (ttl.isZero() || ttl.isNegative()
                || ttl.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException(
                    "HRM-009 cursor TTL must be in (0, PT24H].");
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
            if (cursor.length() < 16 || cursor.length() > 1024) throw denied();
            return cursor;
        } catch (CertificateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw CertificateException.unavailable(
                    "HRM-009 cursor issuance failed.");
        }
    }

    Binding verify(String cursor, Binding expected) {
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
            int length = buffer.getInt();
            if (length <= 0 || length != buffer.remaining() - SIGNATURE_LENGTH) {
                throw denied();
            }
            byte[] payload = new byte[length];
            byte[] signature = new byte[SIGNATURE_LENGTH];
            buffer.get(payload).get(signature);
            if (!MessageDigest.isEqual(sign(payload), signature)) throw denied();
            Payload decoded = mapper.readValue(payload, Payload.class);
            requireBinding(decoded.binding(), true);
            if (decoded.expiresAtEpochSecond() <= clock.instant().getEpochSecond()
                    || !matches(expected, decoded.binding())) throw denied();
            return decoded.binding();
        } catch (CertificateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw denied();
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

    private void requireBinding(Binding binding, boolean complete) {
        if (binding == null || binding.tenantId() < 1
                || binding.queryId() == null
                || !binding.queryId().matches("IAQ-(003|010)")
                || blank(binding.targetPopulation())
                || blank(binding.scopeDecisionRevision())
                || blank(binding.authorizationDecisionRevision())
                || complete && binding.asOf() == null
                || binding.sort() == null || binding.sort().isEmpty()
                || binding.sort().size() > 3
                || binding.pageLimit() < 1 || binding.pageLimit() > 100
                || blank(binding.filterDigest())
                || blank(binding.locale()) || blank(binding.timeZone())
                || complete && blank(binding.lastItemPublicId())
                || binding.lastItemPublicId() != null
                && !binding.lastItemPublicId().matches("[A-Za-z0-9._:-]{1,128}")) {
            throw denied();
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank() || value.length() > 512;
    }

    private byte[] sign(byte[] payload) throws Exception {
        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(new SecretKeySpec(secret, ALGORITHM));
        mac.update(DOMAIN);
        return mac.doFinal(payload);
    }

    private void requireConfigured() {
        if (secret.length < 24) {
            throw CertificateException.unavailable(
                    "HRM-009 cursor authority is not configured.");
        }
    }

    private CertificateException denied() {
        return CertificateException.denied(
                "The HRM-009 cursor is invalid, stale, or outside this scope.");
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
