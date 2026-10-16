package com.dwp.services.people.hris.home;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/** Canonical digest plus opaque tenant-bound stateless HMAC reference. */
@Component
@ConditionalOnProperty(prefix = ConfiguredPeopleHomeAuthority.PREFIX,
        name = "enabled", havingValue = "true", matchIfMissing = false)
final class PeopleHomePayloadCodec {

    private static final String PREFIX = "href_";
    private static final byte VERSION = 1;
    private static final int BODY_BYTES = 1 + Long.BYTES + 32;
    private static final int REFERENCE_BYTES = BODY_BYTES + 32;
    private final byte[] secret;

    PeopleHomePayloadCodec(Environment environment) {
        this(secret(environment.getProperty(
                ConfiguredPeopleHomeAuthority.PREFIX + "payload-ref-secret", "")));
    }

    PeopleHomePayloadCodec(byte[] secret) {
        if (secret == null || secret.length < 32) {
            throw new IllegalStateException(
                    "Enabled HRM Home producer requires a 256-bit payload reference secret.");
        }
        this.secret = secret.clone();
    }

    String digest(
            PeopleHomeAuthorityPort.CurrentAuthority authority,
            PeopleHomeSourcePort.Snapshot snapshot) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                text(output, "PeopleHomeContribution.v1");
                output.writeLong(authority.tenantId());
                text(output, authority.subjectPrincipalPublicId().toString());
                text(output, authority.capability());
                text(output, authority.browserAudience().name());
                text(output, authority.ownerPopulationScope().name());
                output.writeLong(authority.scopeRevision());
                text(output, authority.populationScopeDigest());
                output.writeLong(authority.effectivePermissionRevision());
                output.writeLong(authority.fieldPolicyRevision());
                text(output, authority.widgetKey());
                for (var person : authority.populationPersonPublicIds().stream()
                        .sorted(Comparator.comparing(Object::toString)).toList()) {
                    text(output, person.toString());
                }
                for (Map.Entry<String, ?> decision : authority.fieldDecisions().entrySet()
                        .stream().sorted(Map.Entry.comparingByKey()).toList()) {
                    text(output, decision.getKey());
                    text(output, decision.getValue().toString());
                }
                optional(output, snapshot.employmentStatus());
                optional(output, snapshot.organizationLabel());
                optional(output, snapshot.pendingActionCount().map(Object::toString));
                output.writeLong(snapshot.sourceVersion());
                text(output, snapshot.asOf().toString());
            }
            return HexFormat.of().formatHex(sha256(bytes.toByteArray()));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot canonicalize People Home payload.", exception);
        }
    }

    String seal(long tenantId, Instant generatedAt, String digest) {
        if (tenantId < 1 || generatedAt == null || digest == null
                || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Canonical People Home reference inputs required.");
        }
        byte[] digestBytes = HexFormat.of().parseHex(digest);
        ByteBuffer body = ByteBuffer.allocate(BODY_BYTES)
                .put(VERSION).putLong(generatedAt.toEpochMilli()).put(digestBytes);
        byte[] bodyBytes = body.array();
        byte[] mac = hmac(tenantId, bodyBytes);
        ByteBuffer reference = ByteBuffer.allocate(REFERENCE_BYTES)
                .put(bodyBytes).put(mac);
        return PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(reference.array());
    }

    Opened open(long tenantId, String reference) {
        if (tenantId < 1 || reference == null || !reference.startsWith(PREFIX)) {
            throw new UnknownReference();
        }
        try {
            String encoded = reference.substring(PREFIX.length());
            if (encoded.indexOf('=') >= 0) throw new UnknownReference();
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            if (decoded.length != REFERENCE_BYTES
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded)
                    .equals(encoded)) throw new UnknownReference();
            byte[] body = java.util.Arrays.copyOfRange(decoded, 0, BODY_BYTES);
            byte[] actualMac = java.util.Arrays.copyOfRange(decoded, BODY_BYTES, decoded.length);
            if (!MessageDigest.isEqual(hmac(tenantId, body), actualMac)) {
                throw new UnknownReference();
            }
            ByteBuffer input = ByteBuffer.wrap(body);
            if (input.get() != VERSION) throw new UnknownReference();
            Instant generatedAt = Instant.ofEpochMilli(input.getLong());
            byte[] digest = new byte[32];
            input.get(digest);
            return new Opened(generatedAt, HexFormat.of().formatHex(digest));
        } catch (RuntimeException exception) {
            if (exception instanceof UnknownReference unknown) throw unknown;
            throw new UnknownReference();
        }
    }

    private byte[] hmac(long tenantId, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update("PeopleHomeContributionRef.v1\n".getBytes(StandardCharsets.UTF_8));
            mac.update(ByteBuffer.allocate(Long.BYTES).putLong(tenantId).array());
            return mac.doFinal(body);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot authenticate People Home reference.", exception);
        }
    }

    private static void optional(DataOutputStream output, Optional<String> value)
            throws java.io.IOException {
        output.writeBoolean(value.isPresent());
        if (value.isPresent()) text(output, value.get());
    }

    private static void text(DataOutputStream output, String value)
            throws java.io.IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static byte[] secret(String raw) {
        if (raw == null || raw.isBlank() || raw.indexOf('=') >= 0) return null;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(raw);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(raw)
                    ? decoded : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    record Opened(Instant generatedAt, String payloadDigest) {
    }

    static final class UnknownReference extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
