package com.dwp.core.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

/** Strict single-key verifier. Replay remains an owner-receipt concern. */
public final class Hrm012OwnerReceiptAuthorityVerifier {

    private static final int MAX_TOKEN_BYTES = 8_192;
    private static final int MAX_CLOCK_SKEW_SECONDS = 5;
    private static final int MAX_TTL_SECONDS = 30;

    private final ObjectMapper mapper;
    private final Clock clock;
    private final String keyId;
    private final PublicKey publicKey;

    public Hrm012OwnerReceiptAuthorityVerifier(
            ObjectMapper objectMapper, Clock clock, String keyId, PublicKey publicKey) {
        this.mapper = objectMapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.clock = clock;
        this.keyId = keyId == null ? "" : keyId;
        this.publicKey = publicKey;
    }

    public Evidence verify(String compactJws, Expected expected) {
        requireConfigured();
        validateExpected(expected);
        if (compactJws == null || compactJws.isBlank()
                || !compactJws.equals(compactJws.strip())
                || compactJws.getBytes(StandardCharsets.UTF_8).length > MAX_TOKEN_BYTES
                || compactJws.indexOf(',') >= 0 || hasControl(compactJws)) {
            throw denied("Owner-receipt evidence format is invalid.");
        }
        String[] parts = compactJws.split("\\.", -1);
        if (parts.length != 3 || !segment(parts[0]) || !segment(parts[1])
                || !segment(parts[2])) throw denied("Owner-receipt evidence is not compact JWS.");
        JsonNode header = decode(parts[0]);
        exactFields(header, Hrm012OwnerReceiptAuthorityContract.HEADER_FIELDS);
        textEquals(header, "typ", Hrm012OwnerReceiptAuthorityContract.TYPE);
        textEquals(header, "alg", Hrm012OwnerReceiptAuthorityContract.ALGORITHM);
        textEquals(header, "kid", keyId);
        verifySignature(parts);

        JsonNode claims = decode(parts[1]);
        exactFields(claims, Hrm012OwnerReceiptAuthorityContract.CLAIM_FIELDS);
        long iat = integer(claims, "iat");
        long nbf = integer(claims, "nbf");
        long exp = integer(claims, "exp");
        long now = clock.instant().getEpochSecond();
        if (exp <= iat || nbf < iat - MAX_CLOCK_SKEW_SECONDS || nbf >= exp
                || exp - iat > MAX_TTL_SECONDS || iat > now + MAX_CLOCK_SKEW_SECONDS
                || nbf > now + MAX_CLOCK_SKEW_SECONDS || exp <= now - MAX_CLOCK_SKEW_SECONDS) {
            throw denied("Owner-receipt evidence time window is invalid.");
        }
        textEquals(claims, "iss", Hrm012OwnerReceiptAuthorityContract.ISSUER);
        textEquals(claims, "aud", Hrm012OwnerReceiptAuthorityContract.AUDIENCE);
        textEquals(claims, "state", Hrm012OwnerReceiptAuthorityContract.STATE);
        textEquals(claims, "route_contract_key", Hrm012OwnerReceiptAuthorityContract.ROUTE);
        textEquals(claims, "command_method", Hrm012OwnerReceiptAuthorityContract.METHOD);
        textEquals(claims, "command_path", Hrm012OwnerReceiptAuthorityContract.SERVICE_PATH);
        textEquals(claims, "feature_key", Hrm012OwnerReceiptAuthorityContract.FEATURE);
        long tenantId = integer(claims, "tenant_id");
        if (tenantId != expected.tenantId()) throw denied("Owner-receipt tenant mismatch.");
        textEquals(claims, "on_behalf_of_subject", expected.onBehalfOfSubject());
        textEquals(claims, "correlation_id", expected.correlationId());
        textEquals(claims, "idempotency_key_sha256", expected.idempotencyKeySha256());
        String app = requiredText(claims, "accepted_app_entitlement", 64);
        if (!Hrm012OwnerReceiptAuthorityContract.APP_ENTITLEMENTS.contains(app)
                || !expected.acceptedAppEntitlements().contains(app)) {
            throw denied("Owner-receipt app entitlement mismatch.");
        }
        String challenge = nullableText(claims, "opaque_step_up_challenge_ref", 8192);
        return new Evidence(
                tenantId, expected.onBehalfOfSubject(), requiredText(claims, "jti", 160),
                Instant.ofEpochSecond(exp), app,
                requiredText(claims, "feature_decision_revision", 500),
                expected.correlationId(), expected.idempotencyKeySha256(), challenge,
                Hrm012OwnerReceiptAuthorityContract.sha256(compactJws));
    }

    private void requireConfigured() {
        if (publicKey == null || keyId.isBlank()) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Owner-receipt verification is not configured.");
        }
    }

    private void validateExpected(Expected expected) {
        if (expected == null || expected.tenantId() <= 0
                || !canonical(expected.onBehalfOfSubject(), 160)
                || !canonical(expected.correlationId(), 200)
                || expected.idempotencyKeySha256() == null
                || !expected.idempotencyKeySha256().matches("[a-f0-9]{64}")
                || expected.acceptedAppEntitlements().isEmpty()
                || !Hrm012OwnerReceiptAuthorityContract.APP_ENTITLEMENTS
                        .containsAll(expected.acceptedAppEntitlements())) {
            throw denied("Trusted owner-receipt request binding is invalid.");
        }
    }

    private boolean canonical(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum
                && value.equals(value.strip()) && !hasControl(value)
                && value.indexOf(',') < 0;
    }

    private JsonNode decode(String segment) {
        try {
            JsonNode value = mapper.readTree(Base64.getUrlDecoder().decode(segment));
            if (value == null || !value.isObject() || value.size() > 24) throw denied("Invalid JWS JSON.");
            return value;
        } catch (BaseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw denied("Owner-receipt evidence encoding is invalid.");
        }
    }

    private void exactFields(JsonNode node, Set<String> expected) {
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw denied("Owner-receipt evidence schema is invalid.");
    }

    private void verifySignature(String[] parts) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                throw denied("Owner-receipt evidence signature is invalid.");
            }
        } catch (BaseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Owner-receipt signature verification is unavailable.", exception);
        }
    }

    private long integer(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw denied("Owner-receipt evidence integer claim is invalid.");
        }
        return value.longValue();
    }

    private void textEquals(JsonNode node, String name, String expected) {
        if (!expected.equals(requiredText(node, name, 500))) {
            throw denied("Owner-receipt evidence claim mismatch.");
        }
    }

    private String requiredText(JsonNode node, String name, int maximum) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) throw denied("Owner-receipt evidence claim is missing.");
        String text = value.textValue();
        if (text.isBlank() || text.length() > maximum || !text.equals(text.strip())
                || hasControl(text)) throw denied("Owner-receipt evidence claim is invalid.");
        return text;
    }

    private String nullableText(JsonNode node, String name, int maximum) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) return null;
        return requiredText(node, name, maximum);
    }

    private boolean segment(String value) {
        return value != null && !value.isEmpty()
                && value.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_');
    }

    private boolean hasControl(String value) {
        return value.chars().anyMatch(c -> c < 0x20 || c == 0x7f);
    }

    private BaseException denied(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    public static PublicKey parsePublicKey(String pem) {
        if (pem == null || pem.isBlank()) return null;
        try {
            String encoded = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        } catch (Exception exception) {
            throw new IllegalStateException("Configured HRM-012 public key is invalid.", exception);
        }
    }

    public record Expected(
            long tenantId,
            String onBehalfOfSubject,
            String correlationId,
            String idempotencyKeySha256,
            Set<String> acceptedAppEntitlements) {
        public Expected {
            acceptedAppEntitlements = acceptedAppEntitlements == null
                    ? Set.of() : Set.copyOf(acceptedAppEntitlements);
        }
    }

    public record Evidence(
            long tenantId,
            String onBehalfOfSubject,
            String jti,
            Instant expiresAt,
            String acceptedAppEntitlement,
            String featureDecisionRevision,
            String correlationId,
            String idempotencyKeySha256,
            String opaqueStepUpChallengeRef,
            String markerSha256) {
    }
}
