package com.dwp.services.auth.service;

import static com.dwp.services.auth.service.ApprovalWorkflowRoleBinding.*;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The ROLE workload assertion is independent of USER-directory transport and signing keys. */
@Component
public final class ApprovalWorkflowRoleProofVerifier {
    public static final String ISSUER = "dwp-approval-server:workflow-role-authority:v1";
    public static final String AUDIENCE = "dwp-auth-server:approval-role-authority:v1";
    public static final String PURPOSE = "APPROVAL_WORKFLOW_ROLE_AUTHORITY_V1";
    public static final String OPERATION = ApprovalWorkflowRoleProtocol.ROLE_BINDINGS;
    private static final Set<String> CLAIMS = Set.of("iss", "aud", "purpose", "jti", "iat", "nbf", "exp",
            "operation", "requestDigest", "bindings");
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<String, RSAKey> keys;
    private final ApprovalWorkflowRoleWorkloadVerifier workloads;

    @Autowired
    public ApprovalWorkflowRoleProofVerifier(ObjectMapper mapper,
            @Value("${dwp.auth.approval-workflow-role-proof-jwks:}") String publicJwks,
            @Value("${dwp.auth.approval-form-user-proof-jwks:}") String userJwks,
            @Value("${dwp.auth.approval-workflow-role-transport-jwks:}") String transportJwks) {
        this(mapper, publicJwks, userJwks, transportJwks, Clock.systemUTC());
    }

    public ApprovalWorkflowRoleProofVerifier(ObjectMapper mapper, String publicJwks, String userJwks,
            String transportJwks, Clock clock) {
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).disable(SerializationFeature.INDENT_OUTPUT);
        this.clock = clock;
        this.keys = keys(publicJwks, userJwks, transportJwks);
        this.workloads = new ApprovalWorkflowRoleWorkloadVerifier(this.mapper, clock, transportJwks, publicJwks, userJwks);
    }

    public VerifiedProof verify(String transportToken, String rawBody) {
        try {
            if (rawBody == null || rawBody.length() > 524288) throw denied();
            JsonNode body = mapper.readTree(rawBody);
            exact(body, Set.of("operation", "sourceProof", "bindings"));
            if (!OPERATION.equals(text(body, "operation"))) throw denied();
            var source = body.get("sourceProof");
            if (!source.isTextual()) throw denied();
            String token = source.textValue();
            if (token.isEmpty() || token.length() > 262144 || !token.equals(token.strip())) throw denied();
            var workload = workloads.verify(transportToken, body);
            var binding = ApprovalWorkflowRoleBinding.parse(body.get("bindings"), mapper);
            String digest = digest(binding);
            if (keys.isEmpty()) throw unavailable();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) throw denied();
            JsonNode header = decode(parts[0]);
            exact(header, Set.of("alg", "typ", "kid"));
            if (!"RS256".equals(text(header, "alg")) || !"JWT".equals(text(header, "typ"))) throw denied();
            RSAKey key = keys.get(text(header, "kid"));
            ApprovalWorkflowRoleWorkloadVerifier.canonicalPart(parts[2]);
            if (key == null || !SignedJWT.parse(token).verify(new RSASSAVerifier(key))) throw denied();
            JsonNode claims = decode(parts[1]);
            exact(claims, CLAIMS);
            if (!ISSUER.equals(text(claims, "iss")) || !AUDIENCE.equals(text(claims, "aud"))
                    || !PURPOSE.equals(text(claims, "purpose")) || !OPERATION.equals(text(claims, "operation"))
                    || !digest.equals(text(claims, "requestDigest")) || !claims.get("bindings").equals(body.get("bindings"))) throw denied();
            long issued = integer(claims, "iat", 1), starts = integer(claims, "nbf", 1), expiry = integer(claims, "exp", 1);
            long now = clock.instant().getEpochSecond();
            if (issued > now || starts != issued || expiry <= now || expiry <= issued || expiry - issued > 30) throw denied();
            return new VerifiedProof(binding, uuid(claims, "jti"), digest,
                    Instant.ofEpochSecond(Math.min(expiry, workload.expiresAt().getEpochSecond())),
                    workload.id(), workload.bodySha256());
        } catch (BaseException exception) { throw exception;
        } catch (Exception exception) { throw denied(); }
    }

    String digest(ApprovalWorkflowRoleBinding binding) {
        return sha256(json(mapper, new TreeMap<>(Map.of("operation", OPERATION, "bindings", binding.sealed()))));
    }

    private JsonNode decode(String encoded) throws java.io.IOException {
        return mapper.readTree(ApprovalWorkflowRoleWorkloadVerifier.canonicalPart(encoded));
    }

    private Map<String, RSAKey> keys(String publicJwks, String... prohibitedJwks) {
        if (publicJwks == null || publicJwks.isBlank() || publicJwks.length() > 65536) return Map.of();
        try {
            exact(mapper.readTree(publicJwks), Set.of("keys"));
            var prohibited = new java.util.HashSet<String>();
            var prohibitedIds = new java.util.HashSet<String>();
            for (String raw : prohibitedJwks) if (raw != null && !raw.isBlank()) {
                if (raw.length() > 65536) return Map.of();
                exact(mapper.readTree(raw), Set.of("keys"));
                for (JWK key : JWKSet.parse(raw).getKeys()) {
                    prohibited.add(key.computeThumbprint().toString()); prohibitedIds.add(key.getKeyID());
                }
            }
            var result = new HashMap<String, RSAKey>();
            for (JWK raw : JWKSet.parse(publicJwks).getKeys()) {
                if (!(raw instanceof RSAKey key) || key.isPrivate() || key.size() < 2048
                        || !KeyUse.SIGNATURE.equals(key.getKeyUse()) || !JWSAlgorithm.RS256.equals(key.getAlgorithm())
                        || key.getKeyID() == null || !key.getKeyID().matches("[A-Za-z0-9._-]{1,80}")
                        || prohibited.contains(key.computeThumbprint().toString()) || prohibitedIds.contains(key.getKeyID())
                        || result.putIfAbsent(key.getKeyID(), key) != null) return Map.of();
            }
            return result.isEmpty() || result.size() > 8 ? Map.of() : Map.copyOf(result);
        } catch (Exception exception) { return Map.of(); }
    }

    public record VerifiedProof(ApprovalWorkflowRoleBinding binding, UUID proofId, String requestDigest, Instant expiresAt,
            UUID transportId, String bodySha256) { }
}
