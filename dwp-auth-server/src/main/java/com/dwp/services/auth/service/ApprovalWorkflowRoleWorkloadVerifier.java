package com.dwp.services.auth.service;

import static com.dwp.services.auth.service.ApprovalWorkflowRoleBinding.*;

import com.dwp.services.auth.config.ApprovalWorkflowRoleSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A compact, independently keyed transport token binds the complete owner proof in the HTTP body. */
final class ApprovalWorkflowRoleWorkloadVerifier {
    static final String ISSUER = "dwp-approval-server:workflow-role-transport:v1";
    static final String AUDIENCE = "dwp-auth-server:workflow-role-transport:v1";
    static final String PURPOSE = "APPROVAL_WORKFLOW_ROLE_TRANSPORT_V1";
    private static final Set<String> CLAIMS = Set.of("iss", "aud", "purpose", "jti", "iat", "nbf", "exp",
            "operation", "httpMethod", "httpPath", "bodySha256", "sourceProofSha256");
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<String, RSAKey> keys;

    ApprovalWorkflowRoleWorkloadVerifier(ObjectMapper mapper, Clock clock, String jwks, String... prohibitedJwks) {
        this.mapper = mapper; this.clock = clock; this.keys = keys(jwks, prohibitedJwks);
    }

    VerifiedWorkload verify(String token, JsonNode body) throws Exception {
        if (token == null || token.isEmpty() || token.length() > 2048 || !token.equals(token.strip())) throw denied();
        if (keys.isEmpty()) throw unavailable();
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) throw denied();
        JsonNode header = decode(parts[0]); exact(header, Set.of("alg", "typ", "kid"));
        if (!"RS256".equals(text(header, "alg")) || !"JWT".equals(text(header, "typ"))) throw denied();
        RSAKey key = keys.get(text(header, "kid"));
        canonicalPart(parts[2]);
        if (key == null || !SignedJWT.parse(token).verify(new RSASSAVerifier(key))) throw denied();
        JsonNode claims = decode(parts[1]); exact(claims, CLAIMS);
        String bodyDigest = sha256(json(mapper, canonical(body)));
        if (!ISSUER.equals(text(claims, "iss")) || !AUDIENCE.equals(text(claims, "aud"))
                || !PURPOSE.equals(text(claims, "purpose"))
                || !ApprovalWorkflowRoleProtocol.ROLE_BINDINGS.equals(text(claims, "operation"))
                || !"POST".equals(text(claims, "httpMethod"))
                || !ApprovalWorkflowRoleSecurityConfig.PATH.equals(text(claims, "httpPath"))
                || !bodyDigest.equals(text(claims, "bodySha256"))
                || !sha256(body.get("sourceProof").textValue()).equals(text(claims, "sourceProofSha256"))) throw denied();
        long issued = integer(claims, "iat", 1), starts = integer(claims, "nbf", 1), expiry = integer(claims, "exp", 1);
        if (issued > clock.instant().getEpochSecond() || starts != issued || expiry <= clock.instant().getEpochSecond()
                || expiry <= issued || expiry - issued > 30) throw denied();
        return new VerifiedWorkload(uuid(claims, "jti"), bodyDigest, Instant.ofEpochSecond(expiry));
    }

    private JsonNode decode(String encoded) throws java.io.IOException { return mapper.readTree(canonicalPart(encoded)); }

    static byte[] canonicalPart(String encoded) {
        if (encoded.isEmpty() || !encoded.matches("[A-Za-z0-9_-]+")) throw denied();
        byte[] raw = Base64.getUrlDecoder().decode(encoded);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(encoded)) throw denied();
        return raw;
    }

    private Map<String, RSAKey> keys(String raw, String[] prohibitedJwks) {
        if (raw == null || raw.isBlank() || raw.length() > 65536) return Map.of();
        try {
            exact(mapper.readTree(raw), Set.of("keys"));
            var prohibited = new java.util.HashSet<String>(); var prohibitedIds = new java.util.HashSet<String>();
            for (String other : prohibitedJwks) if (other != null && !other.isBlank()) {
                if (other.length() > 65536) return Map.of();
                exact(mapper.readTree(other), Set.of("keys"));
                for (var key : JWKSet.parse(other).getKeys()) {
                    prohibited.add(key.computeThumbprint().toString()); prohibitedIds.add(key.getKeyID());
                }
            }
            var result = new HashMap<String, RSAKey>();
            for (var rawKey : JWKSet.parse(raw).getKeys()) {
                if (!(rawKey instanceof RSAKey key) || key.isPrivate() || key.size() < 2048
                        || !KeyUse.SIGNATURE.equals(key.getKeyUse()) || !JWSAlgorithm.RS256.equals(key.getAlgorithm())
                        || key.getKeyID() == null || !key.getKeyID().matches("[A-Za-z0-9._-]{1,80}")
                        || prohibited.contains(key.computeThumbprint().toString()) || prohibitedIds.contains(key.getKeyID())
                        || result.putIfAbsent(key.getKeyID(), key) != null) return Map.of();
            }
            return result.isEmpty() || result.size() > 8 ? Map.of() : Map.copyOf(result);
        } catch (Exception exception) { return Map.of(); }
    }

    record VerifiedWorkload(UUID id, String bodySha256, Instant expiresAt) { }
}
