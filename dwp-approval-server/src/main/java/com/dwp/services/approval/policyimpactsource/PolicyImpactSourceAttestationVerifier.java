package com.dwp.services.approval.policyimpactsource;

import static com.dwp.services.approval.policyimpactsource.PolicyImpactSourceJson.*;
import static com.dwp.services.approval.policyimpactsource.PolicyImpactSourceProtocol.*;

import com.dwp.services.approval.policyimpact.ApprovalPolicyImpactAuthority;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;

public final class PolicyImpactSourceAttestationVerifier {
    private static final Set<String> AUTHORITY = Set.of("ownerAuthRevision", "ownerPolicyRevision", "sourceRevision", "sourceVectorSha256", "evaluatedAt", "expiresAt");
    private static final Set<String> GRANT = Set.of("capabilityContractKey", "resolvedCapabilityCode", "resourceSetKey", "contextScopeKey", "expiresAt");
    private final PolicyImpactSourceKeys keys;
    private final PolicyImpactSourceJson json;
    private final Clock clock;
    public PolicyImpactSourceAttestationVerifier(PolicyImpactSourceKeys keys, PolicyImpactSourceJson json, Clock clock) {
        this.keys = keys; this.json = json; this.clock = clock;
    }
    public static final class Verified {
        private final ApprovalPolicyImpactAuthority.Window window;
        private final String vector, authRevision, policyRevision;
        private Verified(ApprovalPolicyImpactAuthority.Window window, String vector, String authRevision, String policyRevision) {
            this.window = window; this.vector = vector; this.authRevision = authRevision; this.policyRevision = policyRevision;
        }
        public ApprovalPolicyImpactAuthority.Window window() { return window; }
        public boolean sameSource(Verified other) {
            return vector.equals(other.vector) && authRevision.equals(other.authRevision) && policyRevision.equals(other.policyRevision)
                    && window.equals(other.window);
        }
    }
    public Verified verify(byte[] response, PolicyImpactSourceProofIssuer.Exchange exchange) {
        var envelope = json.parse(response); keys(envelope, Set.of("sourceAttestation"));
        String token = text(envelope, "sourceAttestation", OWNER_LIMIT); String[] parts = token.split("\\.", -1);
        if (parts.length != 3) throw denied();
        var header = json.parse(part(parts[0])); keys(header, Set.of("alg", "typ", "kid"));
        if (!"RS256".equals(text(header, "alg", 5)) || !"JWT".equals(text(header, "typ", 3))) throw denied();
        var key = keys.attestation(text(header, "kid", 80)); part(parts[2]);
        try { if (!SignedJWT.parse(token).verify(new RSASSAVerifier(key))) throw denied(); }
        catch (com.dwp.core.exception.BaseException error) { throw error; } catch (Exception error) { throw denied(); }
        var claims = json.parse(part(parts[1])); var expected = new java.util.HashSet<>(STANDARD);
        expected.addAll(Set.of("purpose", "sourceProofJti", "transportProofJti", "bodySha256", "bindingsSha256", "bindings", "authority", "grants"));
        keys(claims, expected);
        keys(claims.get("bindings"), BINDINGS); keys(exchange.bindings(), BINDINGS);
        if (!ATTESTATION_ISSUER.equals(text(claims, "iss", 160)) || !ATTESTATION_AUDIENCE.equals(text(claims, "aud", 160))
                || !ATTESTATION_PURPOSE.equals(text(claims, "purpose", 100)) || !"dwp-auth-server".equals(text(claims, "sub", 30))
                || !exchange.sourceJti().equals(text(claims, "sourceProofJti", 80)) || !exchange.transportJti().equals(text(claims, "transportProofJti", 80))
                || !exchange.bodyHash().equals(hash(claims, "bodySha256")) || !exchange.bindingsHash().equals(hash(claims, "bindingsSha256"))
                || !json.digest(exchange.bindings()).equals(json.digest(claims.get("bindings")))) throw denied();
        String jti = text(claims, "jti", 80); if (!jti.matches("[A-Za-z0-9_-]{20,80}")) throw denied();
        long issued = integer(claims, "iat", true), starts = integer(claims, "nbf", true), expires = integer(claims, "exp", true);
        long now = clock.instant().getEpochSecond();
        if (issued > now || starts != issued || expires <= now || expires <= issued || expires - issued > 30
                || expires > exchange.expiresAt().getEpochSecond()) throw denied();
        var authority = claims.get("authority"); keys(authority, AUTHORITY);
        String authRevision = text(authority, "ownerAuthRevision", 200), policyRevision = text(authority, "ownerPolicyRevision", 200);
        String vector = hash(authority, "sourceVectorSha256");
        if (!authRevision.matches("auth-[a-f0-9]{64}") || !policyRevision.matches("policy-9-[1-9][0-9]*-[a-f0-9]{64}")
                || !("apia-" + vector).equals(text(authority, "sourceRevision", 69))
                || instant(authority, "evaluatedAt").isBefore(Instant.ofEpochSecond(issued))
                || instant(authority, "evaluatedAt").isAfter(clock.instant())
                || !instant(authority, "expiresAt").equals(Instant.ofEpochSecond(expires))) throw denied();
        var bindings = exchange.bindings(); keys(bindings, BINDINGS);
        var grants = claims.get("grants"); if (grants == null || !grants.isArray() || grants.size() != 3) throw denied();
        String resourceSet = text(bindings, "resourceSetKey", 80), scope = text(bindings, "contextScopeKey", 512);
        var actual = new java.util.HashMap<String, ApprovalPolicyImpactAuthority.Grant>();
        for (var grant : grants) {
            keys(grant, GRANT); String capability = text(grant, "capabilityContractKey", 100);
            if (!REQUIRED.containsKey(capability) || !REQUIRED.get(capability).equals(text(grant, "resolvedCapabilityCode", 100))
                    || !resourceSet.equals(text(grant, "resourceSetKey", 80)) || !scope.equals(text(grant, "contextScopeKey", 512))
                    || !instant(grant, "expiresAt").equals(Instant.ofEpochSecond(expires))
                    || actual.put(capability, new ApprovalPolicyImpactAuthority.Grant(resourceSet, REQUIRED.get(capability))) != null) throw denied();
        }
        // decisionRevision is the original Gateway aggregate, never the raw Auth revision above.
        var window = new ApprovalPolicyImpactAuthority.Window(integer(bindings, "tenantId", true), integer(bindings, "actorId", true),
                resourceSet, text(bindings, "contextKey", 512), scope, text(bindings, "decisionRevision", 68), ROUTE,
                text(bindings, "rolloutState", 3), Instant.ofEpochSecond(expires), text(bindings, "accessMode", 20), false, false, true, actual);
        return new Verified(window, vector, authRevision, policyRevision);
    }
    private byte[] part(String value) {
        if (value == null || value.isEmpty() || value.length() > BODY_LIMIT || !value.matches("[A-Za-z0-9_-]+")) throw denied();
        try {
            byte[] decoded = java.util.Base64.getUrlDecoder().decode(value);
            if (!java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) throw denied(); return decoded;
        } catch (IllegalArgumentException error) { throw denied(); }
    }
}
