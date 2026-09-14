package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Owner-issued source proofs are independent of the transport token and client authority headers. */
@Component
public final class ApprovalFormUserSourceProofVerifier {
    public static final String PURPOSE = "APPROVAL_FORM_TENANT_PEOPLE_V1";
    public static final String ISSUER = "dwp-approval-server:form-user-source:v1";
    public static final String AUDIENCE = "dwp-auth-server:approval-form-user-directory:v1";
    private static final Set<String> CLAIMS = Set.of("iss", "aud", "purpose", "jti", "iat", "nbf", "exp",
            "tenantId", "actorId", "formId", "formVersionId", "schemaSha256", "contextKey", "contextScopeKey",
            "decisionRevision", "routeContractKey", "accessMode",
            "referencePurpose", "operation", "requestDigest");
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<String, RSAKey> trustedKeys;

    @Autowired
    public ApprovalFormUserSourceProofVerifier(ObjectMapper mapper,
            @Value("${dwp.auth.approval-form-user-proof-jwks:}") String trustedPublicJwks) {
        this(mapper, trustedPublicJwks, Clock.systemUTC());
    }

    public ApprovalFormUserSourceProofVerifier(ObjectMapper mapper, String trustedPublicJwks, Clock clock) {
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).disable(SerializationFeature.INDENT_OUTPUT);
        this.clock = clock;
        this.trustedKeys = publicKeys(trustedPublicJwks);
    }

    public VerifiedSourceProof verify(String token, String operation, String requestDigest) {
        if (trustedKeys.isEmpty()) throw unavailable();
        try {
            JsonNode claims = verifySignedClaims(token, CLAIMS);
            match(claims, "iss", ISSUER);
            match(claims, "aud", AUDIENCE);
            match(claims, "purpose", PURPOSE);
            match(claims, "operation", operation);
            match(claims, "requestDigest", requestDigest);
            long iat = number(claims, "iat"), nbf = number(claims, "nbf"), exp = number(claims, "exp");
            long now = clock.instant().getEpochSecond();
            if (iat > now || nbf != iat || exp <= now || exp <= iat || exp - iat > 60) throw rejected();
            String mode = text(claims, "accessMode"), reference = text(claims, "referencePurpose");
            if (!Set.of("NORMAL", "ELEVATED").contains(mode)
                    || !Set.of("CREATE_REFERENCE", "FORM_PREVIEW").contains(reference)
                    || !Set.of("SEARCH", "RESOLVE").contains(operation)) throw rejected();
            String schema = text(claims, "schemaSha256"), revision = text(claims, "decisionRevision");
            if (!schema.matches("[a-f0-9]{64}") || !requestDigest.matches("[a-f0-9]{64}")
                    || !revision.matches("psr-[a-f0-9]{64}")) throw rejected();
            return new VerifiedSourceProof(number(claims, "tenantId"), number(claims, "actorId"),
                    uuid(claims, "formId"), uuid(claims, "formVersionId"), schema,
                    text(claims, "contextKey"), text(claims, "contextScopeKey"),
                    revision,
                    text(claims, "routeContractKey"), mode, reference, operation, requestDigest,
                    uuid(claims, "jti"), Instant.ofEpochSecond(exp), null);
        } catch (BaseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw rejected();
        }
    }

    JsonNode verifySignedClaims(String token, Set<String> fields) {
        if (trustedKeys.isEmpty()) throw unavailable();
        try {
            if (token == null || token.length() > 16384 || !token.equals(token.strip())) throw rejected();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) throw rejected();
            JsonNode header = decode(parts[0]);
            exact(header, Set.of("alg", "typ", "kid"));
            if (!"RS256".equals(text(header, "alg")) || !"JWT".equals(text(header, "typ"))) throw rejected();
            RSAKey key = trustedKeys.get(text(header, "kid"));
            if (key == null || !SignedJWT.parse(token).verify(new RSASSAVerifier(key))) throw rejected();
            JsonNode claims = decode(parts[1]);
            exact(claims, fields);
            return claims;
        } catch (BaseException exception) { throw exception;
        } catch (Exception exception) { throw rejected(); }
    }

    boolean referenceProfile(String token) {
        try {
            return "APPROVAL_FORM_REFERENCE_RESOLVE_V1".equals(decode(token.split("\\.", -1)[1]).path("purpose").asText());
        } catch (Exception exception) { return false; }
    }

    Instant now() { return clock.instant(); }

    public String requestDigest(String operation, Object requestBody) {
        try {
            // A closed envelope binds both the operation and the exact ordered request data.
            var envelope = new java.util.TreeMap<String, Object>();
            envelope.put("operation", operation);
            envelope.put("request", canonical(mapper.valueToTree(requestBody)));
            byte[] json = mapper.writeValueAsBytes(envelope);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    private Object canonical(JsonNode node) {
        if (node.isObject()) {
            var fields = new java.util.TreeMap<String, Object>();
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), canonical(entry.getValue())));
            return fields;
        }
        if (node.isArray()) {
            var values = new java.util.ArrayList<Object>();
            node.forEach(value -> values.add(canonical(value)));
            return values;
        }
        return node;
    }

    private Map<String, RSAKey> publicKeys(String json) {
        if (json == null || json.isBlank() || json.length() > 65536) return Map.of();
        try {
            JsonNode raw = mapper.readTree(json);
            exact(raw, Set.of("keys"));
            var keys = new LinkedHashMap<String, RSAKey>();
            for (JWK item : JWKSet.parse(json).getKeys()) {
                if (!(item instanceof RSAKey key) || key.isPrivate() || key.size() < 2048
                        || !KeyUse.SIGNATURE.equals(key.getKeyUse())
                        || !JWSAlgorithm.RS256.equals(key.getAlgorithm()) || key.getKeyID() == null
                        || !key.getKeyID().matches("[A-Za-z0-9._-]{1,80}")
                        || keys.putIfAbsent(key.getKeyID(), key) != null) return Map.of();
            }
            return keys.size() > 8 ? Map.of() : Map.copyOf(keys);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private JsonNode decode(String encoded) throws Exception {
        if (encoded.isEmpty() || !encoded.matches("[A-Za-z0-9_-]+")) throw rejected();
        return mapper.readTree(Base64.getUrlDecoder().decode(encoded));
    }

    private void exact(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) throw rejected();
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(expected)) throw rejected();
    }

    private String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank()
                || value.textValue().length() > 512 || !value.textValue().equals(value.textValue().strip())
                || value.textValue().codePoints().anyMatch(Character::isISOControl)) throw rejected();
        return value.textValue();
    }

    private void match(JsonNode node, String key, String expected) {
        if (!text(node, key).equals(expected)) throw rejected();
    }

    private long number(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) throw rejected();
        return value.longValue();
    }

    private UUID uuid(JsonNode node, String key) {
        String value = text(node, key);
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw rejected();
        return id;
    }

    private BaseException rejected() {
        return new BaseException(ErrorCode.FORBIDDEN, "The exact approval person source proof was rejected.");
    }

    private BaseException unavailable() {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Approval person source verification is unavailable.");
    }

    public record VerifiedSourceProof(long tenantId, long actorId, UUID formId, UUID formVersionId,
            String schemaSha256, String contextKey, String contextScopeKey,
            String decisionRevision, String routeContractKey, String accessMode,
            String referencePurpose, String operation, String requestDigest, UUID proofId, Instant expiresAt,
            ApprovalFormReferenceBinding mutation) { }
}
