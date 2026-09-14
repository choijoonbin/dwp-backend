package com.dwp.services.auth.approvalpolicyimpact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class PolicyImpactProtocolFixture {
    static final Instant NOW = Instant.parse("2026-09-14T06:00:00Z");
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final PolicyImpactJson json = new PolicyImpactJson(new ObjectMapper().findAndRegisterModules());
    final RSAKey owner = rsa("impact-owner"), transport = rsa("impact-transport"), attestation = rsa("impact-attestation");
    PolicyImpactKeys keys() { return new PolicyImpactKeys(json, jwks(owner), jwks(transport), attestation.toJSONString(), jwks(attestation), List.of()); }
    PolicyImpactProofVerifier verifier() { return new PolicyImpactProofVerifier(json, keys(), clock); }
    Map<String, Object> binding() {
        var fields = new LinkedHashMap<String, Object>();
        var policy = UUID.fromString("11111111-1111-4111-8111-111111111111");
        fields.put("tenantId", 42L); fields.put("actorId", 99L);
        fields.put("personPublicId", "22222222-2222-4222-8222-222222222222"); fields.put("policyId", policy.toString());
        fields.put("expectedVersion", 0L); fields.put("sourceDigest", "a".repeat(64));
        fields.put("method", "GET"); fields.put("path", "/v1/admin/policies/" + policy + "/impact");
        fields.put("rawQuerySha256", PolicyImpactJson.sha("expectedVersion=0")); fields.put("routeContractKey", PolicyImpactProtocol.ROUTE);
        fields.put("contextKey", "context-bound-original"); fields.put("contextScopeKey", "opaque-original"); fields.put("resourceSetKey", "RS_APPROVALS");
        fields.put("decisionRevision", "psr-" + "b".repeat(64)); fields.put("rolloutState", "110"); fields.put("accessMode", "NORMAL");
        fields.put("authorityValidUntil", NOW.plusSeconds(60).toString()); return fields;
    }
    Map<String, Object> standard(String issuer, String audience, String purpose, String jti) {
        var claims = new LinkedHashMap<String, Object>(); claims.put("iss", issuer); claims.put("aud", audience);
        claims.put("sub", "dwp-approval-server"); claims.put("jti", jti); claims.put("iat", NOW.getEpochSecond());
        claims.put("nbf", NOW.getEpochSecond()); claims.put("exp", NOW.plusSeconds(30).getEpochSecond()); claims.put("purpose", purpose);
        return claims;
    }
    Exchange exchange(Map<String, Object> bindings) {
        String sourceJti = UUID.randomUUID().toString(), transportJti = UUID.randomUUID().toString();
        var claims = standard(PolicyImpactProtocol.OWNER_ISSUER, PolicyImpactProtocol.OWNER_AUDIENCE, PolicyImpactProtocol.OWNER_PURPOSE, sourceJti);
        claims.put("bindings", bindings); claims.put("bindingsSha256", json.digest(bindings));
        return exchange(bindings, token(owner, claims), sourceJti, transportJti);
    }
    Exchange exchange(Map<String, Object> bindings, String source, String sourceJti, String transportJti) {
        byte[] body = json.bytes(Map.of("sourceProof", source, "bindings", bindings));
        var claims = standard(PolicyImpactProtocol.TRANSPORT_ISSUER, PolicyImpactProtocol.TRANSPORT_AUDIENCE,
                PolicyImpactProtocol.TRANSPORT_PURPOSE, transportJti);
        claims.put("method", "POST"); claims.put("path", PolicyImpactProtocol.PATH); claims.put("sourceProofJti", sourceJti);
        claims.put("bodySha256", PolicyImpactJson.sha(body)); claims.put("bindingsSha256", json.digest(bindings));
        return new Exchange(body, token(transport, claims), sourceJti, transportJti);
    }
    String token(RSAKey key, Object claims) { return rawToken(key, new String(json.bytes(claims), StandardCharsets.UTF_8)); }
    static String rawToken(RSAKey key, String claims) {
        try {
            var jwt = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(), new Payload(claims));
            jwt.sign(new RSASSASigner(key)); return jwt.serialize();
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    static RSAKey rsa(String id) {
        try { return new RSAKeyGenerator(2048).keyID(id).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate(); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    static String jwks(RSAKey key) { return new JWKSet(key.toPublicJWK()).toString(); }
    record Exchange(byte[] body, String token, String sourceJti, String transportJti) { }
}
