package com.dwp.services.auth.approvalpolicyimpact;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public final class PolicyImpactAuthorityIssuer {
    private final PolicyImpactJson json;
    private final Supplier<PolicyImpactKeys> keys;
    private final Clock clock;
    public PolicyImpactAuthorityIssuer(PolicyImpactJson json, Supplier<PolicyImpactKeys> keys, Clock clock) {
        this.json = json; this.keys = keys; this.clock = clock;
    }
    public String issue(PolicyImpactProofVerifier.Verified proof, PolicyImpactAuthorityPort.Current authority) {
        Instant now = clock.instant();
        if (!authority.expiresAt().isAfter(now) || authority.expiresAt().isAfter(proof.expiresAt())
                || authority.expiresAt().isAfter(now.plusSeconds(30)) || !authority.sourceVectorSha256().matches("[a-f0-9]{64}")
                || !("apia-" + authority.sourceVectorSha256()).equals(authority.sourceRevision())
                || !authority.ownerAuthRevision().matches("auth-[a-f0-9]{64}") || !authority.ownerPolicyRevision().matches("policy-9-[1-9][0-9]*-[a-f0-9]{64}")
                || authority.evaluatedAt().isAfter(now) || authority.evaluatedAt().isBefore(now.minusSeconds(30))
                || !authority.expiresAt().equals(Instant.ofEpochSecond(authority.expiresAt().getEpochSecond()))
                || authority.grants().size() != 3) throw PolicyImpactJson.unavailable();
        var seen = new java.util.HashSet<String>();
        for (var grant : authority.grants()) {
            if (!PolicyImpactProtocol.REQUIRED.containsKey(grant.capabilityContractKey()) || !seen.add(grant.capabilityContractKey())
                    || !PolicyImpactProtocol.REQUIRED.get(grant.capabilityContractKey()).equals(grant.resolvedCapabilityCode())
                    || !proof.bindings().resourceSetKey().equals(grant.resourceSetKey())
                    || !proof.bindings().contextScopeKey().equals(grant.contextScopeKey())
                    || !authority.expiresAt().equals(grant.expiresAt())) throw PolicyImpactJson.unavailable();
        }
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", PolicyImpactProtocol.ATTESTATION_ISSUER); claims.put("aud", PolicyImpactProtocol.ATTESTATION_AUDIENCE);
        claims.put("sub", "dwp-auth-server"); claims.put("jti", UUID.randomUUID().toString());
        long issued = authority.evaluatedAt().getEpochSecond();
        if (authority.expiresAt().getEpochSecond() - issued > 30) throw PolicyImpactJson.unavailable();
        claims.put("iat", issued); claims.put("nbf", issued); claims.put("exp", authority.expiresAt().getEpochSecond());
        claims.put("purpose", PolicyImpactProtocol.ATTESTATION_PURPOSE); claims.put("sourceProofJti", proof.sourceJti());
        claims.put("transportProofJti", proof.transportJti()); claims.put("bodySha256", proof.bodySha256());
        claims.put("bindingsSha256", proof.bindingsSha256()); claims.put("bindings", proof.bindingJson());
        claims.put("authority", Map.of("ownerAuthRevision", authority.ownerAuthRevision(), "ownerPolicyRevision", authority.ownerPolicyRevision(),
                "sourceRevision", authority.sourceRevision(), "sourceVectorSha256", authority.sourceVectorSha256(),
                "evaluatedAt", authority.evaluatedAt().toString(), "expiresAt", authority.expiresAt().toString()));
        claims.put("grants", authority.grants());
        try {
            var key = keys.get().signer();
            var jwt = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(),
                    new Payload(new String(json.bytes(claims), java.nio.charset.StandardCharsets.UTF_8)));
            jwt.sign(new RSASSASigner(key.toRSAPrivateKey())); var token = jwt.serialize();
            if (token.length() > PolicyImpactProtocol.OWNER_LIMIT) throw PolicyImpactJson.unavailable();
            return token;
        } catch (Exception error) { throw PolicyImpactJson.unavailable(); }
    }
}
